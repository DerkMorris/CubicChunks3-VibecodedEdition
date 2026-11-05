package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Enderman.class)
public abstract class MixinEnderman {
    private static final byte CC_TELEPORT_EVENT = 46;
    private static final int CC_MAX_FLOOR_SEARCH = 96;

    @WrapOperation(method = "customServerAiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Enderman;getLightLevelDependentMagicValue()F"))
    private float cc_daylightAtEyes(Enderman mob, Operation<Float> original) {
        Level level = mob.level();
        if (!((CanBeCubic) level).cc_isCubic()) {
            return original.call(mob);
        }
        BlockPos eyes = BlockPos.containing(mob.getX(), mob.getEyeY(), mob.getZ());
        try (var reads = new io.github.opencubicchunks.cubicchunks.world.level.entity.CubeSpawnContext(level)) {
            float brightness = level.getLightLevelDependentMagicValue(eyes);
            return reads.isKnown() ? brightness : 0;
        }
    }

    @WrapOperation(method = "customServerAiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;canSeeSky(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean cc_daylightSky(net.minecraft.server.level.ServerLevel level, BlockPos feet, Operation<Boolean> original) {
        if (!((CanBeCubic) level).cc_isCubic()) {
            return original.call(level, feet);
        }
        try (var reads = new io.github.opencubicchunks.cubicchunks.world.level.entity.CubeSpawnContext(level)) {
            return level.canSeeSky(feet) && reads.isKnown();
        }
    }

    @WrapOperation(method = "teleport(DDD)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Enderman;randomTeleport(DDDZLnet/minecraft/tags/TagKey;)Z"))
    private boolean cc_findTeleportFloor(
            Enderman mob, double x, double y, double z, boolean particles, TagKey<Block> forbidden, Operation<Boolean> original
    ) {
        Level level = mob.level();
        if (!((CanBeCubic) level).cc_isCubic()) {
            return original.call(mob, x, y, z, particles, forbidden);
        }
        Vec3 target = level.getWorldBorder().clampVec3ToBound(x, y, z);
        var floor = BlockPos.containing(target).mutable();
        for (int depth = 0; depth < CC_MAX_FLOOR_SEARCH; depth++) {
            floor.move(Direction.DOWN);
            var cube = CubeMobBlockView.loadedCube(level, floor);
            if (cube == null) {
                return false;
            }
            var state = cube.getBlockState(floor);
            if (state.is(BlockTags.ENTITIES_CAN_TELEPORT_TO)) {
                return !state.is(forbidden) && cc_land(mob, target.x, target.y - depth, target.z, particles, forbidden);
            }
        }
        return false;
    }

    private static boolean cc_land(Enderman mob, double x, double y, double z, boolean particles, TagKey<Block> forbidden) {
        Level level = mob.level();
        AABB box = mob.getBoundingBox().move(x - mob.getX(), y - mob.getY(), z - mob.getZ());
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(Math.nextDown(box.maxX), Math.nextDown(box.maxY), Math.nextDown(box.maxZ));
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            var cube = CubeMobBlockView.loadedCube(level, pos);
            if (cube == null) {
                return false;
            }
            var state = cube.getBlockState(pos);
            if (state.is(forbidden) || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        if (!level.noCollision(box) || !mob.canRandomlyTeleportTo(x, y, z)) {
            return false;
        }
        mob.teleportTo(x, y, z);
        if (particles) {
            level.broadcastEntityEvent(mob, CC_TELEPORT_EVENT);
        }
        mob.getNavigation().stop();
        return true;
    }
}
