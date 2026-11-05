package io.github.opencubicchunks.cubicchunks.world.level.entity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.EntityManagerAccess;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

/** Cube access controls native tracking; native callbacks retain UUIDs, resident storage, and always-ticking player behavior. */
public final class CubeEntityVisibility {
    private final EntityManagerAccess access;
    private final LevelEntityGetter<Entity> getter;
    private final Map<Entity, Entry> entries = new IdentityHashMap<>();
    private final Map<CubePos, Set<Entry>> cubes = new HashMap<>();
    private final Set<CubePos> accessible = new HashSet<>();
    private final Set<CubePos> ticking = new HashSet<>();

    public CubeEntityVisibility(PersistentEntitySectionManager<Entity> manager) {
        access = (EntityManagerAccess) manager;
        getter = new CubeEntityGetter<>(access.cc_entityGetter());
    }

    public LevelEntityGetter<Entity> getter() {
        return getter;
    }

    public EntityInLevelCallback wrap(Entity entity, EntityInLevelCallback original) {
        Entry entry = new Entry(entity, original);
        entries.put(entity, entry);
        cubes.computeIfAbsent(entry.cube, key -> new HashSet<>()).add(entry);
        return entry;
    }

    /** Called after native onCreated and initial player tracking, preserving their ordering. */
    public void activate(Entity entity) {
        Entry entry = entries.get(entity);
        if (entry != null) {
            entry.active = true;
            entry.refresh();
        }
    }

    public boolean canTick(BlockPos pos) {
        return CubicBlockBounds.contains(pos) && ticking.contains(CubePos.from(pos));
    }

    public void update(CubePos cube, FullChunkStatus status) {
        if (status != FullChunkStatus.INACCESSIBLE) {
            accessible.add(cube);
        } else {
            accessible.remove(cube);
        }
        if (status == FullChunkStatus.ENTITY_TICKING) {
            ticking.add(cube);
        } else {
            ticking.remove(cube);
        }
        for (Entry entry : List.copyOf(cubes.getOrDefault(cube, Set.of()))) {
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
        private SectionPos section;
        private boolean active;
        private boolean visible;
        private boolean ticked;

        private Entry(Entity entity, EntityInLevelCallback original) {
            this.entity = entity;
            this.original = original;
            cube = CubePos.from(entity.blockPosition());
            section = SectionPos.of(entity.blockPosition());
        }

        private void refresh() {
            if (entity.isAlwaysTicking()) {
                return;
            }
            if (!shouldTrack() || !ticking.contains(cube)) {
                updateTicking(false);
            }
            updateTracking(shouldTrack());
            updateTicking(visible && shouldTrack() && ticking.contains(cube));
        }

        private boolean shouldTrack() {
            return active && !entity.isRemoved() && CubicBlockBounds.contains(entity.blockPosition()) && accessible.contains(cube);
        }

        private void updateTracking(boolean next) {
            if (next != visible) {
                visible = next;
                if (next) {
                    access.cc_startTracking(entity);
                } else {
                    access.cc_stopTracking(entity);
                }
            }
        }

        private void updateTicking(boolean next) {
            if (ticked != next) {
                ticked = next;
                if (next) {
                    access.cc_startTicking(entity);
                } else {
                    access.cc_stopTicking(entity);
                }
            }
        }

        @Override public void onMove() {
            original.onMove();
            if (entries.get(entity) != this) {
                return;
            }
            SectionPos next = SectionPos.of(entity.blockPosition());
            if (next.equals(section)) {
                return;
            }
            SectionPos previous = section;
            section = next;
            CubePos nextCube = CubePos.from(entity.blockPosition());
            if (!nextCube.equals(cube)) {
                detach(this);
                cube = nextCube;
                cubes.computeIfAbsent(cube, key -> new HashSet<>()).add(this);
            }
            refresh();
            // Native callbacks already handle players except when two full section positions pack to the same long.
            if (visible || entity.isAlwaysTicking() && previous.asLong() == next.asLong()) {
                access.cc_callbacks().onSectionChange(entity);
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
