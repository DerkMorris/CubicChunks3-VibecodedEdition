package io.github.opencubicchunks.cubicchunks.world.level.entity;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;

/** The native spawner retains its persisted chance/delay; only its position and entity creation path is replaced. */
public final class CubeTraderSpawning {
    private static final int PLAYER_CHANCE = 10;
    private static final int ATTEMPTS = 10;
    private static final int RANGE = 48;
    private static final int LLAMA_RANGE = 4;
    private static final int DESPAWN_DELAY = 48000;
    private static final int HOME_RADIUS = 16;

    private CubeTraderSpawning() {}

    public static boolean spawn(ServerLevel level, RandomSource random) {
        var player = CubeSpecialSpawnSupport.randomPlayer(level);
        if (player == null) {
            return true;
        }
        if (random.nextInt(PLAYER_CHANCE) != 0) {
            return false;
        }
        BlockPos reference = CubeSpawnPoints.meeting(level, player.blockPosition()).orElse(player.blockPosition());
        var position = findPosition(level, reference, RANGE, EntityTypes.WANDERING_TRADER, random);
        if (position.isEmpty()) {
            return false;
        }
        WanderingTrader trader = spawnAt(level, position.get(), reference);
        if (trader == null) {
            return false;
        }
        for (int i = 0; i < 2; i++) {
            spawnLlama(level, trader, random);
        }
        return true;
    }

    private static WanderingTrader spawnAt(ServerLevel level, BlockPos pos, BlockPos reference) {
        try (var context = new CubeSpawnContext(level)) {
            if (level.getBiome(pos).is(BiomeTags.WITHOUT_WANDERING_TRADER_SPAWNS) || !hasSpace(level, pos, context)) {
                return null;
            }
            return CubeSpecialSpawnSupport.spawn(level, pos, EntityTypes.WANDERING_TRADER, EntitySpawnReason.EVENT, trader -> {
                trader.setDespawnDelay(DESPAWN_DELAY);
                trader.setWanderTarget(reference);
                trader.setHomeTo(reference, HOME_RADIUS);
            }, context);
        }
    }

    private static boolean hasSpace(ServerLevel level, BlockPos pos, CubeSpawnContext context) {
        for (BlockPos block : BlockPos.betweenClosed(pos, pos.offset(1, 2, 1))) {
            var cube = context.cube(block);
            if (cube == null || !cube.getBlockState(block).getCollisionShape(level, block).isEmpty()) {
                return false;
            }
        }
        return context.isKnown();
    }

    private static void spawnLlama(ServerLevel level, WanderingTrader trader, RandomSource random) {
        var position = findPosition(level, trader.blockPosition(), LLAMA_RANGE, EntityTypes.TRADER_LLAMA, random);
        if (position.isPresent()) {
            try (var context = new CubeSpawnContext(level)) {
                var llama = CubeSpecialSpawnSupport.spawn(level, position.get(), EntityTypes.TRADER_LLAMA, EntitySpawnReason.EVENT, mob -> {},
                        context);
                if (llama != null) {
                    llama.setLeashedTo(trader, true);
                }
            }
        }
    }

    private static Optional<BlockPos> findPosition(ServerLevel level, BlockPos reference, int radius, EntityType<?> type, RandomSource random) {
        for (int i = 0; i < ATTEMPTS; i++) {
            BlockPos candidate = reference.offset(random.nextInt(radius * 2) - radius, 0, random.nextInt(radius * 2) - radius);
            var surface = CubeSpecialSpawnSupport.surface(level, candidate, type);
            if (surface.isPresent()) {
                return surface;
            }
        }
        return Optional.empty();
    }
}
