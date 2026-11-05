package io.github.opencubicchunks.cubicchunks.server.level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/** Login preparation uses the same three-dimensional ticket and entity storage as the player's future tracking area. */
public final class CubePlayerSpawn {
    private CubePlayerSpawn() {}

    private static int radius(int chunkRadius) {
        return (chunkRadius + CubicConstants.DIAMETER_IN_SECTIONS - 1) / CubicConstants.DIAMETER_IN_SECTIONS;
    }

    public static void keepAlive(ServerLevel level, TicketType type, Vec3 position, int chunkRadius) {
        ((ServerCubeCache) level.getChunkSource()).cc_addTicketWithRadius(type, CloPos.cube(CubePos.from(BlockPos.containing(position))),
                radius(chunkRadius));
    }

    public static CompletableFuture<?> load(ServerLevel level, TicketType type, Vec3 position, int chunkRadius) {
        keepAlive(level, type, position, chunkRadius);
        var cache = (ServerCubeCache) level.getChunkSource();
        var futures = new ArrayList<CompletableFuture<?>>();
        for (CubePos pos : area(position, chunkRadius)) {
            futures.add(cache.cc_getCubeFuture(pos.getX(), pos.getY(), pos.getZ(), ChunkStatus.FULL, true).thenAccept(result -> {
                if (result.orElse(null) == null) {
                    throw new IllegalStateException("Could not load player spawn cube " + pos + ": " + result.getError());
                }
            }));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    public static void waitForEntities(ServerLevel level, Vec3 position, int chunkRadius) {
        List<CubePos> cubes = area(position, chunkRadius);
        level.getServer().managedBlock(() -> cubes.stream().allMatch(((CubicServerLevel) level)::cc_areCubeEntitiesLoaded));
    }

    private static List<CubePos> area(Vec3 position, int chunkRadius) {
        var center = CubePos.from(BlockPos.containing(position));
        int radius = radius(chunkRadius);
        var result = new ArrayList<CubePos>();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    result.add(CubePos.of(center.getX() + x, center.getY() + y, center.getZ() + z));
                }
            }
        }
        return result;
    }
}
