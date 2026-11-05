package io.github.opencubicchunks.cubicchunks.world.level.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.phys.AABB;

public final class CubeCatSpawning {
    private static final int DELAY = 1200;
    private static final int OFFSET = 8;
    private static final int OFFSET_RANGE = 24;
    private static final int REQUIRED_HOMES = 5;
    private static final int VILLAGE_CAP = 5;
    private static final int VILLAGE_RANGE = 48;
    private static final int HUT_RANGE = 16;
    private static final int VERTICAL_RANGE = 8;

    private CubeCatSpawning() {}

    public static int tick(ServerLevel level, int delay) {
        if (!CubeSpecialSpawnSupport.enabled(level)) {
            return delay;
        }
        if (delay > 1) {
            return delay - 1;
        }
        var player = CubeSpecialSpawnSupport.randomPlayer(level);
        if (player != null) {
            var random = level.getRandom();
            int x = (OFFSET + random.nextInt(OFFSET_RANGE)) * (random.nextBoolean() ? -1 : 1);
            int z = (OFFSET + random.nextInt(OFFSET_RANGE)) * (random.nextBoolean() ? -1 : 1);
            spawnAt(level, player.blockPosition().offset(x, 0, z));
        }
        return DELAY;
    }

    public static boolean spawnAt(ServerLevel level, BlockPos pos) {
        try (var context = new CubeSpawnContext(level)) {
            if (!CubeSpecialSpawnSupport.ready(level, pos, context) || !SpawnPlacements.isSpawnPositionOk(EntityTypes.CAT, level, pos)) {
                return false;
            }
            var homes = CubeSpawnPoints.occupiedHomes(level, pos);
            boolean village = CubeSpawnPoints.nearHomes(level, pos);
            if (village) {
                return homes.size() >= REQUIRED_HOMES && cats(level, pos, VILLAGE_RANGE) < VILLAGE_CAP && spawn(level, pos, false, context);
            }
            return hut(level, pos) && cats(level, pos, HUT_RANGE) == 0 && spawn(level, pos, true, context);
        }
    }

    private static boolean hut(ServerLevel level, BlockPos pos) {
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var starts = CubeStructureSpawns.starts(level, pos,
                type -> registry.get(registry.getId(type)).map(holder -> holder.is(StructureTags.CATS_SPAWN_IN)).orElse(false));
        return starts.isPresent() && starts.get().stream().anyMatch(start -> CubeStructureSpawns.hasPiece(start, pos));
    }

    private static int cats(ServerLevel level, BlockPos pos, int radius) {
        return level.getEntitiesOfClass(Cat.class, new AABB(pos).inflate(radius, VERTICAL_RANGE, radius)).size();
    }

    private static boolean spawn(ServerLevel level, BlockPos pos, boolean persistent, CubeSpawnContext context) {
        return CubeSpecialSpawnSupport.spawn(level, pos, EntityTypes.CAT, EntitySpawnReason.NATURAL, cat -> {
            if (persistent) {
                cat.setPersistenceRequired();
            }
        }, context) != null;
    }
}
