package io.github.opencubicchunks.cubicchunks.world.level.entity;

import java.util.Optional;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** Local surfaces are relative to an actor's full Y; they make no claim about the global top of a column. */
public final class CubeSpecialSpawnSupport {
    public static final int SURFACE_RANGE = 32;

    private CubeSpecialSpawnSupport() {}

    public static boolean enabled(ServerLevel level) {
        return level.tickRateManager().runsNormally() && level.getGameRules().get(GameRules.SPAWN_MOBS);
    }

    public static @Nullable ServerPlayer randomPlayer(ServerLevel level) {
        var players = level.players().stream().filter(player -> !player.isSpectator() && !player.isRemoved() && player.level() == level).toList();
        return players.isEmpty() ? null : players.get(level.getRandom().nextInt(players.size()));
    }

    public static boolean ready(ServerLevel level, BlockPos pos, CubeSpawnContext context) {
        if (!CubeNaturalSpawner.active(level, pos)) {
            return false;
        }
        context.brightness(LightLayer.BLOCK, pos);
        return context.isKnown();
    }

    public static Optional<BlockPos> surface(ServerLevel level, BlockPos reference, EntityType<?> type) {
        if (CubeMobBlockView.loadedCube(level, reference) == null) {
            return Optional.empty();
        }
        boolean up = true;
        boolean down = true;
        for (int distance = 0; distance <= SURFACE_RANGE; distance++) {
            BlockPos below = reference.below(distance);
            BlockPos above = reference.above(distance);
            down &= CubeMobBlockView.loadedCube(level, below) != null;
            up &= CubeMobBlockView.loadedCube(level, above) != null;
            if (down && surfaceAt(level, below, type)) {
                return Optional.of(below);
            }
            if (up && distance != 0 && surfaceAt(level, above, type)) {
                return Optional.of(above);
            }
        }
        return Optional.empty();
    }

    private static boolean surfaceAt(ServerLevel level, BlockPos pos, EntityType<?> type) {
        try (var context = new CubeSpawnContext(level)) {
            return CubicBlockBounds.contains(pos.above()) && ready(level, pos, context) && SpawnPlacements.isSpawnPositionOk(type, level, pos)
                    && context.isKnown();
        }
    }

    public static <T extends Mob> @Nullable T spawn(
            ServerLevel level, BlockPos pos, EntityType<T> type, EntitySpawnReason reason, Consumer<T> configure, CubeSpawnContext context
    ) {
        Vec3 center = Vec3.atBottomCenterOf(pos);
        if (!ready(level, pos, context) || !level.noCollision(type.getSpawnAABB(center.x, center.y, center.z))) {
            return null;
        }
        T mob = type.create(level, reason);
        if (mob == null) {
            return null;
        }
        mob.snapTo(center.x, center.y, center.z, 0, 0);
        configure.accept(mob);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), reason, null);
        if (!context.isKnown() || !CubeNaturalSpawner.active(level, pos) || !level.tryAddFreshEntityWithPassengers(mob)) {
            return null;
        }
        return mob;
    }
}
