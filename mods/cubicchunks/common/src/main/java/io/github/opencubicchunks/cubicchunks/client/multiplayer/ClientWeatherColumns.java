package io.github.opencubicchunks.cubicchunks.client.multiplayer;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundWeatherColumnPacket;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.resources.Identifier;

/** One instance belongs to one ClientLevel; reconnect and dimension changes cannot reuse its surfaces. */
public final class ClientWeatherColumns {
    private static final int MAX_COLUMNS = 64;
    private static final float LOAD_FACTOR = .75f;
    private final Identifier dimension;
    private final Map<CubePos, Entry> columns = new LinkedHashMap<>(MAX_COLUMNS, LOAD_FACTOR, true);

    public ClientWeatherColumns(Identifier dimension) {
        this.dimension = dimension;
    }

    public synchronized boolean apply(CCClientboundWeatherColumnPacket packet) {
        if (!dimension.equals(packet.dimension())) {
            return false;
        }
        var key = CubePos.of(packet.cubeX(), 0, packet.cubeZ());
        var previous = columns.get(key);
        if (previous != null && packet.epoch() < previous.epoch) {
            return false;
        }
        columns.put(key, new Entry(packet.epoch(), packet.heights()));
        while (columns.size() > MAX_COLUMNS) {
            columns.remove(columns.keySet().iterator().next());
        }
        return true;
    }

    /** UNKNOWN suppresses native draw/splash/sound sampling instead of consulting an unrelated vanilla column. */
    public synchronized int height(int x, int z) {
        if (!CubicBlockBounds.contains(x) || !CubicBlockBounds.contains(z)) {
            return CCClientboundWeatherColumnPacket.UNKNOWN;
        }
        var entry = columns.get(CubePos.of(Coords.blockToCube(x), 0, Coords.blockToCube(z)));
        return entry == null || entry.heights == null ? CCClientboundWeatherColumnPacket.UNKNOWN
                : entry.heights[Coords.blockToLocal(x) + CubicConstants.DIAMETER_IN_BLOCKS * Coords.blockToLocal(z)];
    }

    private record Entry(long epoch, @Nullable int[] heights) {}
}
