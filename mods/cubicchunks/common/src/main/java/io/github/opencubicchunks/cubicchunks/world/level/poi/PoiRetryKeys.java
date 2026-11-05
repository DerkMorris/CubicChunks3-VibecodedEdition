package io.github.opencubicchunks.cubicchunks.world.level.poi;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongPredicate;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;

/** Per-behavior opaque keys, used ONLY for native retry caches; never interpreted as world positions. */
public final class PoiRetryKeys {
    private static final int CLEANUP_THRESHOLD = 256;
    private final Map<BlockPos, Long> keys = new HashMap<>();
    private long next;

    public long key(BlockPos pos, LongPredicate retained) {
        if (keys.size() > CLEANUP_THRESHOLD) {
            keys.values().removeIf(value -> !retained.test(value));
        }
        return keys.computeIfAbsent(pos.immutable(), ignored -> ++next);
    }

    public static final class Objects<V> extends Long2ObjectOpenHashMap<V> {
        private final PoiRetryKeys keys = new PoiRetryKeys();

        public long key(BlockPos pos) {
            return keys.key(pos, this::containsKey);
        }

        @Override public void clear() {
            super.clear();
            keys.keys.clear();
        }
    }

    public static final class Times extends Long2LongOpenHashMap {
        private final PoiRetryKeys keys = new PoiRetryKeys();

        public long key(BlockPos pos) {
            return keys.key(pos, this::containsKey);
        }

        @Override public void clear() {
            super.clear();
            keys.keys.clear();
        }
    }
}
