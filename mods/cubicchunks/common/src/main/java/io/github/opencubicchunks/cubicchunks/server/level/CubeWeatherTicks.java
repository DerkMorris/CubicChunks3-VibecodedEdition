package io.github.opencubicchunks.cubicchunks.server.level;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeSpawnContext;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSource;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Native weather probabilities per horizontal 16-block column, independent of the number of stacked ticking cubes. */
public final class CubeWeatherTicks {
    private static final int SECTION_SIZE = 16;
    private static final int PRECIPITATION_CHANCE = 48;
    private static final int THUNDER_CHANCE = 100000;
    private static final int ROD_RADIUS = 128;
    private static final int ENTITY_TARGET_MARGIN = 3;
    private static final double TRAP_CHANCE = .01;
    private static final ThreadLocal<Surface> SURFACE = new ThreadLocal<>();
    private final Set<CubePos> visited = new HashSet<>();
    private long lastTick = Long.MIN_VALUE;

    public void tick(ServerLevel level, LevelCube cube, int randomTickSpeed) {
        if (!((CanBeCubic) level).cc_isCubic() || level.getChunkSource() == null || level.getChunkSource().chunkMap == null
                || !level.dimensionType().hasSkyLight()) {
            return;
        }
        long now = level.getGameTime();
        if (lastTick != now) {
            visited.clear();
            lastTick = now;
        }
        CubePos pos = cube.cc_getCubePos();
        if (!visited.add(CubePos.of(pos.getX(), 0, pos.getZ()))) {
            return;
        }
        CubeColumnSource source = (CubeColumnSource) level.getChunkSource().chunkMap;
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                int x = pos.minCubeX() + dx * SECTION_SIZE;
                int z = pos.minCubeZ() + dz * SECTION_SIZE;
                tickSection(level, source, x, z, randomTickSpeed);
            }
        }
    }

    private static void tickSection(ServerLevel level, CubeColumnSource source, int x, int z, int speed) {
        if (level.isRaining() && level.isThundering() && level.getRandom().nextInt(THUNDER_CHANCE) == 0) {
            BlockPos sample = level.getBlockRandomPos(x, 0, z, SECTION_SIZE - 1);
            BlockPos surface = surface(source, Heightmap.Types.MOTION_BLOCKING, sample);
            if (surface != null && active(level, surface, FullChunkStatus.ENTITY_TICKING)) {
                thunder(level, source, surface);
            }
        }
        for (int i = 0; i < speed; i++) {
            if (level.getRandom().nextInt(PRECIPITATION_CHANCE) == 0) {
                BlockPos sample = level.getBlockRandomPos(x, 0, z, SECTION_SIZE - 1);
                BlockPos surface = surface(source, Heightmap.Types.MOTION_BLOCKING, sample);
                if (surface != null && active(level, surface, FullChunkStatus.BLOCK_TICKING)) {
                    precipitate(level, surface);
                }
            }
        }
    }

    public static @Nullable BlockPos surface(CubeColumnSource source, Heightmap.Types type, BlockPos sample) {
        var column = source.cc_column(sample.getX(), sample.getZ());
        if (column == null) {
            return null;
        }
        var height = column.firstAvailable(type, sample.getX(), sample.getZ());
        return height.isPresent() ? new BlockPos(sample.getX(), height.getAsInt(), sample.getZ()) : null;
    }

    private static boolean active(ServerLevel level, BlockPos pos, FullChunkStatus status) {
        var upper = CubeMobBlockView.loadedCube(level, pos);
        var lower = CubeMobBlockView.loadedCube(level, pos.below());
        return upper != null && lower != null && upper.getFullStatus().isOrAfter(status) && lower.getFullStatus().isOrAfter(status);
    }

    /** Retains native biome, light, fluid, snow layers, game-rule, entity-push and cauldron precipitation behavior. */
    public static boolean precipitate(ServerLevel level, BlockPos surface) {
        if (!CubicBlockBounds.contains(surface) || !CubicBlockBounds.contains(surface.below())) {
            return false;
        }
        try (var reads = new CubeSpawnContext(level)) {
            // Freeze queries four water neighbors; snow and native shape queries need the two center cells.
            for (BlockPos pos : BlockPos.betweenClosed(surface.offset(-1, -1, -1), surface.offset(1, 0, 1))) {
                reads.cube(pos);
            }
            reads.brightness(LightLayer.BLOCK, surface);
            reads.brightness(LightLayer.BLOCK, surface.below());
            if (!reads.isKnown()) {
                return false;
            }
            Surface previous = SURFACE.get();
            SURFACE.set(new Surface(level, surface.immutable()));
            try {
                level.tickPrecipitation(surface);
            } finally {
                if (previous == null) {
                    SURFACE.remove();
                } else {
                    SURFACE.set(previous);
                }
            }
            return true;
        }
    }

    public static @Nullable BlockPos precipitationSurface(ServerLevel level) {
        Surface surface = SURFACE.get();
        return surface != null && surface.level == level ? surface.pos : null;
    }

    public static BlockPos lightningTarget(ServerLevel level, CubeColumnSource columns, BlockPos surface) {
        var rod = level.getPoiManager().findClosest(type -> type.is(PoiTypes.LIGHTNING_ROD), pos -> {
            BlockPos top = surface(columns, Heightmap.Types.WORLD_SURFACE, pos);
            return top != null && top.equals(pos.above()) && active(level, pos.above(), FullChunkStatus.ENTITY_TICKING);
        }, surface, ROD_RADIUS, PoiManager.Occupancy.ANY);
        if (rod.isPresent()) {
            return rod.get().above();
        }
        // CubeEntityGetter queries visible entities, not every section in this vertical interval.
        var box = AABB.encapsulatingFullBlocks(surface, surface.atY(CubicBlockBounds.MAX)).inflate(ENTITY_TARGET_MARGIN);
        var entities = level.getEntitiesOfClass(LivingEntity.class, box, entity -> {
            if (!entity.isAlive() || !active(level, entity.blockPosition(), FullChunkStatus.ENTITY_TICKING)) {
                return false;
            }
            BlockPos top = surface(columns, Heightmap.Types.MOTION_BLOCKING, entity.blockPosition());
            return top != null && entity.getY() >= top.getY();
        });
        return entities.isEmpty() ? surface : entities.get(level.getRandom().nextInt(entities.size())).blockPosition();
    }

    /** The native strike body after the random gate, with cubic surface and entity/rod selection. */
    public static boolean thunder(ServerLevel level, CubeColumnSource columns, BlockPos surface) {
        BlockPos target = lightningTarget(level, columns, surface);
        if (!level.isRaining() || level.getBiome(target).value().getPrecipitationAt(target, level.getSeaLevel()) != Biome.Precipitation.RAIN) {
            return false;
        }
        boolean trap = level.getGameRules().get(GameRules.SPAWN_MOBS)
                && level.getRandom().nextDouble() < level.getCurrentDifficultyAt(target).getEffectiveDifficulty() * TRAP_CHANCE
                && !level.getBlockState(target.below()).is(BlockTags.LIGHTNING_RODS);
        if (trap) {
            var horse = EntityTypes.SKELETON_HORSE.create(level, EntitySpawnReason.EVENT);
            if (horse != null) {
                horse.setTrap(true);
                horse.setAge(0);
                horse.setPos(target.getX(), target.getY(), target.getZ());
                level.addFreshEntity(horse);
            }
        }
        var lightning = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
        if (lightning == null) {
            return false;
        }
        lightning.snapTo(Vec3.atBottomCenterOf(target));
        lightning.setVisualOnly(trap);
        return level.addFreshEntity(lightning);
    }

    private record Surface(ServerLevel level, BlockPos pos) {}
}
