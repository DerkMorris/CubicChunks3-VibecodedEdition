package io.github.opencubicchunks.cubicchunks.world.level.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.PatrollingMonster;
import net.minecraft.world.level.gamerules.GameRules;

public final class CubePatrolSpawning {
    private static final int DELAY = 12000;
    private static final int DELAY_VARIATION = 1200;
    private static final int CHANCE = 5;
    private static final int OFFSET = 24;
    private static final int WALK = 5;

    private CubePatrolSpawning() {}

    public static int tick(ServerLevel level, boolean enemies, int delay) {
        if (!CubeSpecialSpawnSupport.enabled(level) || !enemies || !level.getGameRules().get(GameRules.SPAWN_PATROLS)) {
            return delay;
        }
        int remaining = delay - 1;
        if (remaining > 0) {
            return remaining;
        }
        int next = remaining + DELAY + level.getRandom().nextInt(DELAY_VARIATION);
        if (level.isBrightOutside() && level.getRandom().nextInt(CHANCE) == 0) {
            spawn(level);
        }
        return next;
    }

    private static void spawn(ServerLevel level) {
        var player = CubeSpecialSpawnSupport.randomPlayer(level);
        if (player == null || CubeSpawnPoints.nearHomes(level, player.blockPosition())) {
            return;
        }
        var random = level.getRandom();
        int x = (OFFSET + random.nextInt(OFFSET)) * (random.nextBoolean() ? -1 : 1);
        int z = (OFFSET + random.nextInt(OFFSET)) * (random.nextBoolean() ? -1 : 1);
        BlockPos pos = player.blockPosition().offset(x, 0, z);
        int size = (int) Math.ceil(level.getCurrentDifficultyAt(pos).getEffectiveDifficulty()) + 1;
        for (int i = 0; i < size; i++) {
            boolean added = spawnMember(level, pos, i == 0);
            if (i == 0 && !added) {
                break;
            }
            pos = pos.offset(random.nextInt(WALK) - random.nextInt(WALK), 0, random.nextInt(WALK) - random.nextInt(WALK));
        }
    }

    public static boolean spawnMember(ServerLevel level, BlockPos reference, boolean leader) {
        var surface = CubeSpecialSpawnSupport.surface(level, reference, EntityTypes.PILLAGER);
        if (surface.isEmpty()) {
            return false;
        }
        BlockPos pos = surface.get();
        try (var context = new CubeSpawnContext(level)) {
            if (!level.environmentAttributes().getValue(EnvironmentAttributes.CAN_PILLAGER_PATROL_SPAWN, pos) || !PatrollingMonster
                    .checkPatrollingMonsterSpawnRules(EntityTypes.PILLAGER, level, EntitySpawnReason.PATROL, pos, level.getRandom())) {
                return false;
            }
            return CubeSpecialSpawnSupport.spawn(level, pos, EntityTypes.PILLAGER, EntitySpawnReason.PATROL, mob -> {
                if (leader) {
                    mob.setPatrolLeader(true);
                    mob.findPatrolTarget();
                }
            }, context) != null;
        }
    }
}
