package io.github.opencubicchunks.cubicchunks.world.level.entity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.EntityManagerAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeSaveQueue;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

/**
 * Owner-thread snapshots with ordered background writes. Normal unload retries; explicit flush/close wait for disk.
 * Resident objects are enumerated without decoding packed section Y. Vanilla visibility/ticking is a separate concern.
 */
public final class CubeEntityPersistence implements AutoCloseable {
    private final ServerLevel level;
    private final PersistentEntitySectionManager<Entity> manager;
    private final Path directory;
    private final Thread owner = Thread.currentThread();
    private final Set<CubePos> loaded = new HashSet<>();
    private final Set<CubePos> savedEmpty = new HashSet<>();
    private @Nullable CubeStorage storage;
    private @Nullable RuntimeException failure;
    private boolean closed;
    private static final int SAVES_PER_TICK = 4;
    private static final int MAX_LOADS = 32;
    private final CubeSaveQueue<CubePos> saves = new CubeSaveQueue<>();
    private final Map<CubePos, CompletableFuture<Optional<CompoundTag>>> loading = new LinkedHashMap<>();
    private final Map<CubePos, CompoundTag> remaining = new LinkedHashMap<>();
    private Map<CubePos, CompoundTag> batch;
    private Map<CubePos, CompoundTag> completed = Map.of();
    private boolean requested;

    public CubeEntityPersistence(ServerLevel level, PersistentEntitySectionManager<Entity> manager, Path directory) {
        this.level = level;
        this.manager = manager;
        this.directory = directory;
    }

    public boolean isLoaded(CubePos pos) {
        check();
        return loaded.contains(pos);
    }

    public void load(CubePos pos) {
        check();
        if (loaded.contains(pos)) {
            return;
        }
        try {
            var future = loading.remove(pos);
            install(pos, (future == null ? store().read(pos) : future).join());
        } catch (RuntimeException exception) {
            throw fail(exception);
        }
    }

    private void install(CubePos pos, Optional<CompoundTag> saved) {
        List<Entity> roots = saved.map(tag -> CubeEntityData.load(level, pos, tag)).orElseGet(List::of);
        List<Entity> all = roots.stream().flatMap(Entity::getSelfAndPassengers).toList();
        if (all.stream().anyMatch(entity -> manager.isLoaded(entity.getUUID()))) {
            throw new IllegalStateException("Saved cube contains an already resident UUID: " + pos);
        }
        manager.addLegacyChunkEntities(all.stream());
        loaded.add(pos);
        if (roots.isEmpty()) {
            savedEmpty.add(pos);
        }
    }

    public void autoSave() {
        check();
        requested = true;
        tick();
    }

    public void tick() {
        check();
        try {
            saves.poll();
            pollLoads();
            if (batch != null && remaining.isEmpty() && !saves.hasWork()) {
                completed = batch;
                batch = null;
            }
            if (batch == null && requested && prepareDestinations()) {
                batch = snapshot();
                remaining.putAll(batch);
                requested = false;
            }
            submitBatch();
        } catch (RuntimeException exception) {
            throw fail(exception);
        }
    }

    private void pollLoads() {
        var iterator = loading.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().isDone()) {
                install(entry.getKey(), entry.getValue().join());
                iterator.remove();
            }
        }
    }

    private boolean prepareDestinations() {
        boolean ready = true;
        for (Entity root : roots()) {
            CubePos pos = CubePos.from(root.blockPosition());
            if (!loaded.contains(pos)) {
                ready = false;
                if (loading.size() < MAX_LOADS) {
                    loading.computeIfAbsent(pos, key -> store().read(key));
                }
            }
        }
        return ready;
    }

    private Map<CubePos, CompoundTag> snapshot() {
        Map<CubePos, List<Entity>> groups = new HashMap<>();
        loaded.stream().filter(pos -> !savedEmpty.contains(pos)).forEach(pos -> groups.put(pos, new ArrayList<>()));
        roots().forEach(entity -> groups.computeIfAbsent(CubePos.from(entity.blockPosition()), key -> new ArrayList<>()).add(entity));
        Map<CubePos, CompoundTag> snapshots = new LinkedHashMap<>();
        long bytes = 0;
        // Validate the entire moving-root batch before replacing any old file.
        for (var entry : groups.entrySet()) {
            CompoundTag tag = CubeEntityData.save(entry.getKey(), entry.getValue());
            bytes += tag.sizeInBytes();
            if (bytes > CubeSaveQueue.MAX_BYTES) {
                throw new IllegalStateException("Entity snapshot batch exceeds the bounded save memory budget");
            }
            snapshots.put(entry.getKey(), tag);
        }
        return snapshots;
    }

    private void submitBatch() {
        var iterator = remaining.entrySet().iterator();
        int count = 0;
        while (iterator.hasNext() && count < SAVES_PER_TICK) {
            var entry = iterator.next();
            CubePos pos = entry.getKey();
            CompoundTag tag = entry.getValue();
            if (!saves.submit(pos, tag, data -> store().write(pos, data), () -> {
                if (tag.getList("Entities").orElseThrow().isEmpty()) {
                    savedEmpty.add(pos);
                } else {
                    savedEmpty.remove(pos);
                }
            }, () -> {})) {
                break;
            }
            iterator.remove();
            count++;
        }
    }

    public void saveAll() {
        check();
        requested = true;
        try {
            do {
                tick();
                CompletableFuture.allOf(loading.values().toArray(CompletableFuture[]::new)).join();
                saves.awaitAll();
            } while (requested || batch != null);
            if (storage != null) {
                storage.flush().join();
            }
        } catch (RuntimeException exception) {
            throw fail(exception);
        }
    }

    /** Retain entities until their current snapshot and all moved-root counterparts are acknowledged. */
    public boolean tryUnload(CubePos pos) {
        load(pos);
        tick();
        if (batch != null || requested) {
            return false;
        }
        var current = roots().stream().filter(entity -> CubePos.from(entity.blockPosition()).equals(pos)).toList();
        boolean unchanged = current.isEmpty() && savedEmpty.contains(pos) || CubeEntityData.save(pos, current).equals(completed.get(pos));
        if (!unchanged) {
            requested = true;
            tick();
            return false;
        }
        release(pos);
        return true;
    }

    /** Save moved roots and their old cubes together before releasing any object or UUID. */
    public void unload(CubePos pos) {
        load(pos);
        saveAll();
        release(pos);
    }

    private void release(CubePos pos) {
        List<Entity> departing = roots().stream().filter(entity -> CubePos.from(entity.blockPosition()).equals(pos))
                .filter(entity -> entity.getSelfAndPassengers().noneMatch(passenger -> passenger instanceof Player))
                .flatMap(Entity::getSelfAndPassengers).toList();
        // Snapshot the tree before removal dismounts passengers. Never unload players or their player-owned vehicles.
        departing.stream().filter(entity -> !(entity instanceof Player)).forEach(entity -> entity.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK));
        if (roots().stream().noneMatch(entity -> CubePos.from(entity.blockPosition()).equals(pos))) {
            loaded.remove(pos);
            savedEmpty.remove(pos);
        }
    }

    private List<Entity> roots() {
        var sections = ((EntityManagerAccess) manager).cc_sectionStorage();
        return sections.getAllChunksWithExistingSections().longStream().boxed().flatMap(sections::getExistingSectionsInChunk)
                .flatMap(section -> section.getEntities())
                .filter(entity -> entity.level() == level && !(entity instanceof Player) && !entity.isRemoved())
                .filter(entity -> entity.shouldBeSaved() && entity.getType().canSerialize()).toList();
    }

    private CubeStorage store() {
        if (storage == null) {
            try {
                storage = new CubeStorage(directory);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return storage;
    }

    private void check() {
        if (Thread.currentThread() != owner || closed) {
            throw new IllegalStateException("Cube entity storage is closed or used off its owner thread");
        }
        if (failure != null) {
            throw new IllegalStateException("An earlier cube entity operation failed", failure);
        }
    }

    private RuntimeException fail(RuntimeException exception) {
        if (failure == null) {
            failure = exception;
        }
        return exception;
    }

    @Override public void close() throws IOException {
        if (closed) {
            return;
        }
        if (Thread.currentThread() != owner) {
            throw new IOException("Cannot close cube entity storage off its owner thread");
        }
        IOException error = null;
        try {
            saveAll();
        } catch (RuntimeException exception) {
            error = new IOException("Cannot save cube entities", exception);
        }
        closed = true;
        if (storage != null) {
            try {
                storage.close();
            } catch (IOException exception) {
                if (error == null) {
                    error = exception;
                } else {
                    error.addSuppressed(exception);
                }
            }
        }
        if (error != null) {
            throw error;
        }
    }
}
