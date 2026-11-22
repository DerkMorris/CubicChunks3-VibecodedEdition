package io.github.opencubicchunks.smoke;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.server.level.ServerCubeCache;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Server-thread fixture lifetime. Retains only the scene's FULL cubes without requesting block ticking. */
final class LightProbeScene implements AutoCloseable {
    private final ServerCubeCache cache;
    private final Map<CubePos, CubeAccess> cubes = new LinkedHashMap<>();
    private boolean closed;

    private LightProbeScene(ServerLevel level, BlockPos from, BlockPos to) {
        cache = (ServerCubeCache) level.getChunkSource();
        CubePos min = CubePos.from(from);
        CubePos max = CubePos.from(to);
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    CubePos pos = CubePos.of(x, y, z);
                    cubes.put(pos, null);
                    // Radius zero is level 33: FULL, preserving the non-ticking-cube acknowledgement case.
                    cache.cc_addTicketWithRadius(TicketType.FORCED, CloPos.cube(pos), 0);
                }
            }
        }
        try {
            cubes.replaceAll((pos, ignored) -> cache.cc_getCube(pos.getX(), pos.getY(), pos.getZ(), ChunkStatus.FULL, true));
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    static LightProbeScene retain(ServerLevel level, BlockPos from, BlockPos to) {
        return new LightProbeScene(level, from, to);
    }

    /** Reads the retained live cube directly; observations must never add tickets through Level.getBlockState. */
    BlockState getBlockState(BlockPos pos) {
        CubeAccess cube = cubes.get(CubePos.from(pos));
        if (closed || cube == null) {
            throw new IllegalStateException("Block is outside the retained light probe scene: " + pos);
        }
        return cube.getBlockState(pos);
    }

    @Override public void close() {
        if (closed) return;
        cubes.keySet().forEach(pos -> cache.cc_removeTicketWithRadius(TicketType.FORCED, CloPos.cube(pos), 0));
        closed = true;
    }
}
