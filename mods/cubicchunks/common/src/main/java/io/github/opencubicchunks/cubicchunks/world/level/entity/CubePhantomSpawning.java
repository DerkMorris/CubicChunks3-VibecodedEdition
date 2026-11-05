package io.github.opencubicchunks.cubicchunks.world.level.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.lighting.LightEngine;

/** Vanilla insomnia and cooldown rules, with local sky exposure instead of the dimension's absolute sea-level cutoff. */
public final class CubePhantomSpawning {
    private static final int DELAY_SECONDS = 60;
    private static final int TICKS_PER_SECOND = 20;
    private static final int NIGHT_DARKENING = 5;
    private static final int REST_THRESHOLD = 72000;
    private static final float DIFFICULTY_CHANCE = 3.0F;
    private static final int HEIGHT = 20;
    private static final int HEIGHT_VARIATION = 15;
    private static final int HORIZONTAL_RANGE = 10;

    private CubePhantomSpawning() {}

    public static int tick(ServerLevel level, boolean enemies, int delay) {
        if (!CubeSpecialSpawnSupport.enabled(level) || !enemies || !level.getGameRules().get(GameRules.SPAWN_PHANTOMS)) {
            return delay;
        }
        int remaining = delay - 1;
        if (remaining > 0) {
            return remaining;
        }
        int next = remaining + (DELAY_SECONDS + level.getRandom().nextInt(DELAY_SECONDS)) * TICKS_PER_SECOND;
        if (level.getSkyDarken() >= NIGHT_DARKENING || !level.dimensionType().hasSkyLight()) {
            for (ServerPlayer player : level.players()) {
                spawnForPlayer(level, player);
            }
        }
        return next;
    }

    public static int spawnForPlayer(ServerLevel level, ServerPlayer player) {
        if (player.isSpectator() || player.isRemoved() || player.level() != level) {
            return 0;
        }
        BlockPos origin = player.blockPosition();
        try (var context = new CubeSpawnContext(level)) {
            if (!CubeSpecialSpawnSupport.ready(level, origin, context) || !exposed(level, origin, context)) {
                return 0;
            }
            DifficultyInstance difficulty = level.getCurrentDifficultyAt(origin);
            if (!eligible(level, player, difficulty)) {
                return 0;
            }
            var random = level.getRandom();
            BlockPos pos = origin.above(HEIGHT + random.nextInt(HEIGHT_VARIATION)).offset(random.nextInt(HORIZONTAL_RANGE * 2 + 1) - HORIZONTAL_RANGE,
                    0, random.nextInt(HORIZONTAL_RANGE * 2 + 1) - HORIZONTAL_RANGE);
            var block = level.getBlockState(pos);
            if (!NaturalSpawner.isValidEmptySpawnBlock(level, pos, block, block.getFluidState(), EntityTypes.PHANTOM)) {
                return 0;
            }
            int count = 1 + random.nextInt(difficulty.getDifficulty().getId() + 1);
            int spawned = 0;
            for (int i = 0; i < count; i++) {
                if (CubeSpecialSpawnSupport.spawn(level, pos, EntityTypes.PHANTOM, EntitySpawnReason.NATURAL, mob -> {}, context) != null) {
                    spawned++;
                }
            }
            return spawned;
        }
    }

    private static boolean exposed(ServerLevel level, BlockPos pos, CubeSpawnContext context) {
        return !level.dimensionType().hasSkyLight() || context.brightness(LightLayer.SKY, pos) == LightEngine.MAX_LEVEL;
    }

    private static boolean eligible(ServerLevel level, ServerPlayer player, DifficultyInstance difficulty) {
        if (!difficulty.isHarderThan(level.getRandom().nextFloat() * DIFFICULTY_CHANCE)) {
            return false;
        }
        int rest = Mth.clamp(player.getStats().getValue(Stats.CUSTOM.get(Stats.TIME_SINCE_REST)), 1, Integer.MAX_VALUE);
        return level.getRandom().nextInt(rest) >= REST_THRESHOLD;
    }
}
