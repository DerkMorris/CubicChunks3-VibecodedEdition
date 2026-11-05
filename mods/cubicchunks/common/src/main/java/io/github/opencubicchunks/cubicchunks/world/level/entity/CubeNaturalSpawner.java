package io.github.opencubicchunks.cubicchunks.world.level.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.NaturalSpawnerAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** Bounded biome and structure spawning in loaded, entity-ticking cubes. */
public final class CubeNaturalSpawner {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private static final int CUBE_RANGE = CubeSpawnState.RANGE / DIAMETER;
    private static final int MIN_DISTANCE = 24;
    private static final int PERSISTENT_INTERVAL = 400;
    private static final int GROUPS = 3;
    private static final int INITIAL_ATTEMPTS = 4;
    private static final int WALK_RANGE = 6;
    private static final int MAX_GROUP_ATTEMPTS = 64;
    private static final float ROTATION = 360.0F;
    private static final float REDUCED_WATER_CHANCE = 0.98F;

    private CubeNaturalSpawner() {}

    public static void tick(ServerLevel level, boolean spawnEnemies) {
        if (!level.tickRateManager().runsNormally()) {
            return;
        }
        boolean spawnMobs = level.getGameRules().get(GameRules.SPAWN_MOBS);
        CubeSpawnState state = new CubeSpawnState(level, spawnMobs);
        List<LevelCube> cubes = candidates(level, state);
        RandomSource random = level.getRandom();
        Util.shuffle(cubes, random);
        for (LevelCube cube : cubes) {
            cube.incrementInhabitedTime();
            for (MobCategory category : MobCategory.values()) {
                if (spawnMobs && categoryAllowed(category, spawnEnemies, level.getGameTime())) {
                    spawnCategory(level, state, category, randomPosition(cube.cc_getCubePos(), random));
                }
            }
        }
    }

    public static boolean categoryAllowed(MobCategory category, boolean spawnEnemies, long time) {
        return category != MobCategory.MISC && (spawnEnemies || category.isFriendly())
                && (!category.isPersistent() || time % PERSISTENT_INTERVAL == 0);
    }

    public static BlockPos randomPosition(CubePos pos, RandomSource random) {
        return new BlockPos(pos.minCubeX() + random.nextInt(DIAMETER), pos.minCubeY() + random.nextInt(DIAMETER),
                pos.minCubeZ() + random.nextInt(DIAMETER));
    }

    public static List<LevelCube> candidates(ServerLevel level, CubeSpawnState state) {
        List<LevelCube> cubes = new ArrayList<>();
        Set<CubePos> visited = new HashSet<>();
        for (Vec3 player : state.playerPositions()) {
            CubePos center = CubePos.from(BlockPos.containing(player));
            for (int x = -CUBE_RANGE; x <= CUBE_RANGE; x++) {
                for (int y = -CUBE_RANGE; y <= CUBE_RANGE; y++) {
                    for (int z = -CUBE_RANGE; z <= CUBE_RANGE; z++) {
                        addCandidate(level, player, CubePos.of(center.getX() + x, center.getY() + y, center.getZ() + z), visited, cubes);
                    }
                }
            }
        }
        return cubes;
    }

    private static void addCandidate(ServerLevel level, Vec3 player, CubePos pos, Set<CubePos> visited, List<LevelCube> cubes) {
        BlockPos center = new BlockPos(pos.minCubeX() + DIAMETER / 2, pos.minCubeY() + DIAMETER / 2, pos.minCubeZ() + DIAMETER / 2);
        if (Vec3.atCenterOf(center).distanceToSqr(player) >= CubeSpawnState.RANGE * CubeSpawnState.RANGE || !visited.add(pos)) {
            return;
        }
        LevelCube cube = CubeMobBlockView.loadedCube(level, center);
        if (cube != null && active(level, center)) {
            cubes.add(cube);
        }
    }

    public static boolean active(ServerLevel level, BlockPos pos) {
        LevelCube cube = CubeMobBlockView.loadedCube(level, pos);
        return cube != null && cube.getFullStatus().isOrAfter(FullChunkStatus.ENTITY_TICKING) && level.isPositionEntityTicking(pos)
                && level.getWorldBorder().isWithinBounds(pos);
    }

    public static boolean rightDistance(ServerLevel level, CubeSpawnState state, BlockPos pos) {
        double distance = state.nearestDistance(pos);
        if (distance <= MIN_DISTANCE * MIN_DISTANCE || distance >= CubeSpawnState.RANGE * CubeSpawnState.RANGE) {
            return false;
        }
        var spawn = level.getRespawnData();
        return spawn.dimension() != level.dimension() || !spawn.pos().closerToCenterThan(Vec3.atBottomCenterOf(pos), MIN_DISTANCE);
    }

    public static int spawnCategory(ServerLevel level, CubeSpawnState state, MobCategory category, BlockPos start) {
        if (!active(level, start) || !state.canSpawn(category, start)) {
            return 0;
        }
        int total = 0;
        for (int group = 0; group < GROUPS; group++) {
            Pack pack = new Pack(start, Mth.ceil(level.getRandom().nextFloat() * INITIAL_ATTEMPTS));
            total += spawnPack(level, state, category, pack, total);
            if (pack.clusterFull) {
                break;
            }
        }
        return total;
    }

    private static int spawnPack(ServerLevel level, CubeSpawnState state, MobCategory category, Pack pack, int prior) {
        RandomSource random = level.getRandom();
        int spawned = 0;
        for (int attempt = 0; attempt < pack.attempts; attempt++) {
            pack.pos = pack.pos.offset(random.nextInt(WALK_RANGE) - random.nextInt(WALK_RANGE), 0,
                    random.nextInt(WALK_RANGE) - random.nextInt(WALK_RANGE));
            Mob mob = attempt(level, state, category, pack);
            if (mob == null) {
                continue;
            }
            spawned++;
            if (prior + spawned >= mob.getMaxSpawnClusterSize()) {
                pack.clusterFull = true;
                break;
            }
            if (mob.isMaxGroupSizeReached(spawned)) {
                break;
            }
        }
        return spawned;
    }

    private static @Nullable Mob attempt(ServerLevel level, CubeSpawnState state, MobCategory category, Pack pack) {
        BlockPos pos = pack.pos;
        if (!eligible(level, state, category, pos)) {
            return null;
        }
        try (CubeSpawnContext context = new CubeSpawnContext(level)) {
            context.brightness(LightLayer.BLOCK, pos);
            if (!context.isKnown() || !chooseData(level, state, category, pack) || !validType(level, state, category, pack)) {
                return null;
            }
            Mob mob = NaturalSpawnerAccess.cc_createMob(level, pack.data.type());
            if (mob == null) {
                return null;
            }
            Vec3 center = Vec3.atBottomCenterOf(pos);
            mob.snapTo(center.x, center.y, center.z, level.getRandom().nextFloat() * ROTATION, 0.0F);
            if (!NaturalSpawnerAccess.cc_validMob(level, mob, state.nearestDistance(pos)) || !context.isKnown()) {
                return null;
            }
            return finish(level, state, pack, mob, context) ? mob : null;
        }
    }

    private static boolean eligible(ServerLevel level, CubeSpawnState state, MobCategory category, BlockPos pos) {
        return active(level, pos) && rightDistance(level, state, pos) && state.canSpawn(category, pos);
    }

    private static boolean chooseData(ServerLevel level, CubeSpawnState state, MobCategory category, Pack pack) {
        var spawns = CubeStructureSpawns.mobsAt(level, category, pack.pos);
        if (spawns.isEmpty()) {
            return false;
        }
        if (pack.data == null) {
            if (category == MobCategory.WATER_AMBIENT && level.getBiome(pack.pos).is(BiomeTags.REDUCED_WATER_AMBIENT_SPAWNS)
                    && level.getRandom().nextFloat() < REDUCED_WATER_CHANCE) {
                return false;
            }
            pack.data = spawns.get().getRandom(level.getRandom()).orElse(null);
            if (pack.data == null) {
                pack.attempts = 0;
                return false;
            }
            pack.attempts = Math.min(MAX_GROUP_ATTEMPTS, pack.data.count().sample(level.getRandom()));
        }
        return spawns.get().contains(pack.data);
    }

    private static boolean validType(ServerLevel level, CubeSpawnState state, MobCategory category, Pack pack) {
        EntityType<?> type = pack.data.type();
        if (!type.canSummon() || type.getCategory() != category || !state.withinCost(type, pack.pos)) {
            return false;
        }
        if (!type.canSpawnFarFromPlayer()
                && state.nearestDistance(pack.pos) > (double) category.getDespawnDistance() * category.getDespawnDistance()) {
            return false;
        }
        Vec3 center = Vec3.atBottomCenterOf(pack.pos);
        return SpawnPlacements.isSpawnPositionOk(type, level, pack.pos)
                && SpawnPlacements.checkSpawnRules(type, level, EntitySpawnReason.NATURAL, pack.pos, level.getRandom())
                && level.noCollision(type.getSpawnAABB(center.x, center.y, center.z));
    }

    private static boolean finish(ServerLevel level, CubeSpawnState state, Pack pack, Mob mob, CubeSpawnContext context) {
        SpawnGroupData group = mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pack.pos), EntitySpawnReason.NATURAL, pack.group);
        if (!context.isKnown() || !active(level, pack.pos) || !level.tryAddFreshEntityWithPassengers(mob)) {
            return false;
        }
        pack.group = group;
        mob.getSelfAndPassengers().forEach(state::count);
        return true;
    }

    private static final class Pack {
        private BlockPos pos;
        private int attempts;
        private @Nullable MobSpawnSettings.SpawnerData data;
        private @Nullable SpawnGroupData group;
        private boolean clusterFull;

        private Pack(BlockPos pos, int attempts) {
            this.pos = pos;
            this.attempts = attempts;
        }
    }
}
