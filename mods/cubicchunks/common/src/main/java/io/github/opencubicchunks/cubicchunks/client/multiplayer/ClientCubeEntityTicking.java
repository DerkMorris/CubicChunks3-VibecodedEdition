package io.github.opencubicchunks.cubicchunks.client.multiplayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.LevelCallback;

/** Enables the native client tick list for resident cubes, without marking an entire vertical column as ticking. */
public final class ClientCubeEntityTicking {
    private final LevelCallback<Entity> callbacks;
    private final Predicate<CubePos> loaded;
    private final Map<Entity, Entry> entries = new IdentityHashMap<>();
    private final Map<CubePos, Set<Entry>> cubes = new HashMap<>();

    public ClientCubeEntityTicking(LevelCallback<Entity> callbacks, Predicate<CubePos> loaded) {
        this.callbacks = callbacks;
        this.loaded = loaded;
    }

    public EntityInLevelCallback wrap(Entity entity, EntityInLevelCallback original) {
        if (entity.isAlwaysTicking()) {
            return original;
        }
        Entry entry = new Entry(entity, original);
        entries.put(entity, entry);
        cubes.computeIfAbsent(entry.cube, key -> new HashSet<>()).add(entry);
        return entry;
    }

    /** Preserve native creation/tracking order before starting ticks. */
    public void activate(Entity entity) {
        Entry entry = entries.get(entity);
        if (entry != null) {
            entry.active = true;
            entry.refresh();
        }
    }

    public void refresh(CubePos pos) {
        for (Entry entry : List.copyOf(cubes.getOrDefault(pos, Set.of()))) {
            entry.refresh();
        }
    }

    /** A view-center/radius change can hide a retained cache slot before its forget packet arrives. */
    public void refreshAll() {
        for (Entry entry : List.copyOf(entries.values())) {
            entry.refresh();
        }
    }

    private void detach(Entry entry) {
        Set<Entry> bucket = cubes.get(entry.cube);
        if (bucket != null && bucket.remove(entry) && bucket.isEmpty()) {
            cubes.remove(entry.cube);
        }
    }

    private final class Entry implements EntityInLevelCallback {
        private final Entity entity;
        private final EntityInLevelCallback original;
        private CubePos cube;
        private boolean active;
        private boolean ticking;

        private Entry(Entity entity, EntityInLevelCallback original) {
            this.entity = entity;
            this.original = original;
            this.cube = CubePos.from(entity.blockPosition());
        }

        private void refresh() {
            boolean next = active && !entity.isRemoved() && CubicBlockBounds.contains(entity.blockPosition()) && loaded.test(cube);
            if (next != ticking) {
                ticking = next;
                if (next) {
                    callbacks.onTickingStart(entity);
                } else {
                    callbacks.onTickingEnd(entity);
                }
            }
        }

        @Override public void onMove() {
            original.onMove();
            if (entries.get(entity) != this) {
                return;
            }
            CubePos next = CubePos.from(entity.blockPosition());
            if (!next.equals(cube)) {
                detach(this);
                cube = next;
                cubes.computeIfAbsent(cube, key -> new HashSet<>()).add(this);
                refresh();
            }
        }

        @Override public void onRemove(Entity.RemovalReason reason) {
            active = false;
            refresh();
            detach(this);
            entries.remove(entity);
            original.onRemove(reason);
        }
    }
}
