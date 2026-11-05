package io.github.opencubicchunks.cubicchunks.world.level.entity;

import java.util.Optional;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.SpawnUtilAccess;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SpawnUtil;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Villager summons retain native sampling and floor rules, but cannot load terrain or overlook pending cube entities. */
public final class CubeGolemSpawning {
    private CubeGolemSpawning() {}

    public static Optional<IronGolem> spawn(
            EntityType<IronGolem> type, EntitySpawnReason reason, ServerLevel level, BlockPos start, int attempts, int rangeXZ, int rangeY,
            SpawnUtil.Strategy strategy, boolean checkCollisions
    ) {
        if (!CubicBlockBounds.contains(start)) {
            return Optional.empty();
        }
        var random = level.getRandom();
        for (int attempt = 0; attempt < attempts; attempt++) {
            int dx = Mth.randomBetweenInclusive(random, -rangeXZ, rangeXZ);
            int dz = Mth.randomBetweenInclusive(random, -rangeXZ, rangeXZ);
            var pos = start.offset(dx, rangeY, dz).mutable();
            try (var context = new CubeSpawnContext(level)) {
                if (!CubicBlockBounds.contains(pos) || !level.getWorldBorder().isWithinBounds(pos)
                        || !SpawnUtilAccess.cc_findFloor(level, rangeY, pos, strategy)) {
                    continue;
                }
                var result = attempt(type, reason, level, pos, checkCollisions, context);
                if (result.isPresent()) {
                    return result;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<IronGolem> attempt(
            EntityType<IronGolem> type, EntitySpawnReason reason, ServerLevel level, BlockPos pos, boolean checkCollisions, CubeSpawnContext context
    ) {
        Vec3 center = Vec3.atBottomCenterOf(pos);
        AABB box = type.getSpawnAABB(center.x, center.y, center.z);
        if (!context.isKnown() || !ready(level, box) || checkCollisions && !level.noCollision(box)) {
            return Optional.empty();
        }
        IronGolem golem = type.create(level, null, pos, reason, false, false);
        if (golem == null) {
            return Optional.empty();
        }
        if (finish(level, reason, golem, context)) {
            // Native iron-golem finalization creates one entity, without passengers. Use the insertion result, not a void helper.
            golem.playAmbientSound();
            return Optional.of(golem);
        }
        golem.discard();
        return Optional.empty();
    }

    private static boolean finish(ServerLevel level, EntitySpawnReason reason, IronGolem golem, CubeSpawnContext context) {
        return golem.checkSpawnRules(level, reason) && golem.checkSpawnObstruction(level) && context.isKnown() && ready(level, golem.getBoundingBox())
                && level.addFreshEntity(golem);
    }

    private static boolean ready(ServerLevel level, AABB box) {
        BlockPos min = BlockPos.containing(box.getMinPosition());
        BlockPos max = BlockPos.containing(box.getMaxPosition());
        if (!CubicBlockBounds.contains(min) || !CubicBlockBounds.contains(max)) {
            return false;
        }
        CubePos from = CubePos.from(min);
        CubePos to = CubePos.from(max);
        for (int x = from.getX(); x <= to.getX(); x++) {
            for (int y = from.getY(); y <= to.getY(); y++) {
                for (int z = from.getZ(); z <= to.getZ(); z++) {
                    CubePos key = CubePos.of(x, y, z);
                    if (CubeMobBlockView.loadedCube(level, key.asBlockPos()) == null || !((CubicServerLevel) level).cc_areCubeEntitiesLoaded(key)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
