package io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Owner-thread bounded cache. Saved data loads asynchronously; live and pending-unload state always wins. */
public final class CubeColumnCache {
    public static final int MAX_COLUMN_CUBES = 256;
    private static final int MAX_COLUMNS = 64;
    private static final int MAX_LOADING = 4;
    private static final int RETRY_TICKS = 100;
    private static final float LOAD_FACTOR = .75f;
    private final int minY;
    private final int maxY;
    private final Function<CubePos, CompletableFuture<CubeColumnSnapshot>> load;
    private final LongSupplier clock;
    private final Map<CubePos, Entry> entries = new LinkedHashMap<>(MAX_COLUMNS, LOAD_FACTOR, true);

    public CubeColumnCache(int minY, int maxY, Function<CubePos, CompletableFuture<CubeColumnSnapshot>> load) {
        this(minY, maxY, load, () -> 0);
    }

    public CubeColumnCache(int minY, int maxY, Function<CubePos, CompletableFuture<CubeColumnSnapshot>> load, LongSupplier clock) {
        this.minY = minY;
        this.maxY = maxY;
        this.load = load;
        this.clock = clock;
    }

    /** Record the live source immediately, but copy palettes only when a consumer next asks for the column. */
    public void changed(CubeAccess cube) {
        var pos = cube.cc_getCubePos();
        Entry entry = entries.get(CubePos.of(pos.getX(), 0, pos.getZ()));
        if (entry != null && entry.admit(pos.getY())) {
            entry.live.put(pos.getY(), new Stamp(cube, Long.MIN_VALUE, cube.getPersistedStatus(), null, false));
        }
    }

    public @Nullable CubeColumnSnapshot get(int x, int z, Iterable<CubeAccess> sources) {
        CubePos key = CubePos.of(Coords.blockToCube(x), 0, Coords.blockToCube(z));
        Entry entry = entry(key);
        if (entry == null) {
            return null;
        }
        entry.observe(key, sources);
        return entry.snapshot(key, minY, maxY);
    }

    private @Nullable Entry entry(CubePos key) {
        Entry entry = entries.get(key);
        if (entry != null) {
            retry(key, entry);
            return entry;
        }
        if (loading() >= MAX_LOADING) {
            return null;
        }
        entry = new Entry(start(key));
        entries.put(key, entry);
        retry(key, entry);
        trim();
        return entry;
    }

    private void retry(CubePos key, Entry entry) {
        if (!entry.saved.isCompletedExceptionally()) {
            return;
        }
        if (entry.retryAfter == Long.MIN_VALUE) {
            entry.retryAfter = clock.getAsLong() + RETRY_TICKS;
        } else if (clock.getAsLong() >= entry.retryAfter && loading() < MAX_LOADING) {
            entry.saved = start(key);
            entry.retryAfter = Long.MIN_VALUE;
        }
    }

    private long loading() {
        return entries.values().stream().filter(value -> !value.saved.isDone()).count();
    }

    private CompletableFuture<CubeColumnSnapshot> start(CubePos key) {
        try {
            return load.apply(key);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void trim() {
        while (entries.size() > MAX_COLUMNS) {
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getValue().saved.isDone()) {
                    iterator.remove();
                    break;
                }
            }
        }
    }

    private static final class Entry {
        private CompletableFuture<CubeColumnSnapshot> saved;
        private final Map<Integer, Stamp> live = new HashMap<>();
        private boolean overBudget;
        private long retryAfter = Long.MIN_VALUE;

        private Entry(CompletableFuture<CubeColumnSnapshot> saved) {
            this.saved = saved;
        }

        private void observe(CubePos key, Iterable<CubeAccess> sources) {
            Set<Integer> present = new HashSet<>();
            for (CubeAccess cube : sources) {
                CubePos pos = cube.cc_getCubePos();
                if (pos.getX() == key.getX() && pos.getZ() == key.getZ()) {
                    present.add(pos.getY());
                    observe(cube);
                }
            }
            // Acknowledged unloads must retain their final blocks without retaining the live cube object.
            for (Stamp stamp : live.values().toArray(Stamp[]::new)) {
                if (stamp.changed()) {
                    capture(stamp.cube);
                }
            }
            live.replaceAll(
                    (y, stamp) -> present.contains(y) ? stamp : new Stamp(null, stamp.revision, stamp.status, stamp.blocks, stamp.placeholder));
        }

        private void observe(CubeAccess cube) {
            Stamp stamp = live.get(cube.cc_getCubePos().getY());
            if (stamp == null || !stamp.matches(cube)) {
                capture(cube);
            }
        }

        private boolean admit(int y) {
            if (live.size() >= MAX_COLUMN_CUBES && !live.containsKey(y)) {
                overBudget = true;
                return false;
            }
            return true;
        }

        private void capture(CubeAccess cube) {
            if (admit(cube.cc_getCubePos().getY())) {
                ChunkStatus status = cube.getPersistedStatus();
                boolean terrain = status.isOrAfter(ChunkStatus.TERRAIN);
                boolean placeholder = !terrain
                        && java.util.Arrays.stream(cube.getSections()).allMatch(net.minecraft.world.level.chunk.LevelChunkSection::hasOnlyAir);
                live.put(cube.cc_getCubePos().getY(),
                        new Stamp(cube, cube.cc_getLightRevision(), status, terrain ? CubeColumnBlocks.capture(cube) : null, placeholder));
            }
        }

        private @Nullable CubeColumnSnapshot snapshot(CubePos key, int minY, int maxY) {
            if (!saved.isDone() || saved.isCompletedExceptionally() || overBudget) {
                return null;
            }
            CubeColumnSnapshot baseline = saved.getNow(null);
            if (baseline == null) {
                return null;
            }
            var blocks = new TreeMap<>(baseline.cubes());
            var unknown = new HashSet<>(baseline.unknown());
            live.forEach((y, stamp) -> {
                // The native generator decorates before slicing at TERRAIN (CubeStatusTasks).
                // An EMPTY/BIOMES air halo is a placeholder, not a replacement for that authoritative generated terrain.
                if (stamp.placeholder) {
                    return;
                }
                blocks.remove(y);
                unknown.remove(y);
                if (stamp.blocks != null) {
                    blocks.put(y, stamp.blocks);
                } else {
                    unknown.add(y);
                }
            });
            return new CubeColumnSnapshot(key.getX(), key.getZ(), minY, maxY, blocks, unknown);
        }
    }

    private record Stamp(@Nullable CubeAccess cube, long revision, ChunkStatus status, @Nullable CubeColumnBlocks blocks, boolean placeholder) {
        private boolean matches(CubeAccess other) {
            return cube == other && revision == other.cc_getLightRevision() && status == other.getPersistedStatus();
        }

        private boolean changed() {
            return cube != null && (revision != cube.cc_getLightRevision() || status != cube.getPersistedStatus());
        }
    }
}
