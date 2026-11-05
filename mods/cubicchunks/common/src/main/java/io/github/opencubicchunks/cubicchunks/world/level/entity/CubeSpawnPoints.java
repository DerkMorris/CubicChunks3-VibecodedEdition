package io.github.opencubicchunks.cubicchunks.world.level.entity;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;

/** Special spawners share the persistent native POI API and its occupied-ticket village sources. */
public final class CubeSpawnPoints {
    public static final int RADIUS = 48;
    private static final int VILLAGE_SECTIONS = 2;

    private CubeSpawnPoints() {}

    public static Optional<BlockPos> meeting(ServerLevel level, BlockPos center) {
        return level.getPoiManager().findClosest(type -> type.is(PoiTypes.MEETING), center, RADIUS, PoiManager.Occupancy.ANY);
    }

    public static boolean nearHomes(ServerLevel level, BlockPos center) {
        return level.getPoiManager().sectionsToVillage(SectionPos.of(center)) <= VILLAGE_SECTIONS;
    }

    public static Set<BlockPos> occupiedHomes(ServerLevel level, BlockPos center) {
        return level.getPoiManager().findAll(type -> type.is(PoiTypes.HOME), pos -> true, center, RADIUS, PoiManager.Occupancy.IS_OCCUPIED)
                .collect(Collectors.toSet());
    }
}
