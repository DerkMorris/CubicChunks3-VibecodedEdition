package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.world.level.ChunkPos;

/** Opt-in smoke-probe counters. Normal play retains neither tile histories nor this diagnostic object. */
final class NativeTerrainDiagnostics {
    private static final int HISTORY_LIMIT = 4096;
    private final Map<ChunkPos, Integer> createCounts = new LinkedHashMap<>();
    private long requests;
    private long cacheHits;
    private long created;
    private long started;
    private long completed;
    private long failed;
    private int active;
    private int pending;
    private long evicted;
    private long recreated;
    private long historyResets;
    private long selfEvictedOnCompletion;
    private long completedEvictedWhilePendingAboveLimit;

    static @Nullable NativeTerrainDiagnostics createIfEnabled() {
        return Boolean.getBoolean("cubicchunks.smoke.nativeTerrainDiagnostics") ? new NativeTerrainDiagnostics() : null;
    }

    synchronized void requested(boolean hit) {
        requests++;
        if (hit) {
            cacheHits++;
        }
    }

    synchronized void created(ChunkPos key) {
        created++;
        pending++;
        if (!createCounts.containsKey(key) && createCounts.size() == HISTORY_LIMIT) {
            createCounts.clear();
            historyResets++;
        }
        int count = createCounts.getOrDefault(key, 0);
        if (count != 0) {
            recreated++;
        }
        createCounts.put(key, count + 1);
    }

    synchronized void started() {
        started++;
        active++;
    }

    synchronized void finished(boolean failure) {
        active--;
        pending--;
        if (failure) {
            failed++;
        } else {
            completed++;
        }
    }

    synchronized void evicted(boolean completingTile, boolean pendingAboveLimit) {
        evicted++;
        if (completingTile) {
            selfEvictedOnCompletion++;
        }
        if (pendingAboveLimit) {
            completedEvictedWhilePendingAboveLimit++;
        }
    }

    synchronized Map<String, Object> snapshot(int cachedPending, int cachedCompleted) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requests", requests);
        result.put("cacheHits", cacheHits);
        result.put("created", created);
        result.put("started", started);
        result.put("completed", completed);
        result.put("failed", failed);
        result.put("active", active);
        result.put("pending", pending);
        result.put("evicted", evicted);
        result.put("recreated", recreated);
        result.put("unique", createCounts.size());
        result.put("historyResets", historyResets);
        result.put("cachedPending", cachedPending);
        result.put("cachedCompleted", cachedCompleted);
        result.put("selfEvictedOnCompletion", selfEvictedOnCompletion);
        result.put("completedEvictedWhilePendingAboveLimit", completedEvictedWhilePendingAboveLimit);
        Map<String, Integer> counts = new HashMap<>();
        createCounts.forEach((pos, count) -> counts.put(pos.x() + "," + pos.z(), count));
        result.put("createCounts", Map.copyOf(counts));
        return Map.copyOf(result);
    }
}
