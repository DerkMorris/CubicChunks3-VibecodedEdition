package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.entity;

import java.util.function.Predicate;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.EntityBoundsAccess;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chorus fruit and other native random teleports must search loaded cubes below the dimension's ordinary floor. */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntityTeleport {
    @Inject(method = "randomTeleport(DDDZLjava/util/function/Predicate;)Z", at = @At("HEAD"), cancellable = true)
    private void cc_randomTeleport(
            double x, double y, double z, boolean particles, Predicate<BlockState> forbidden, CallbackInfoReturnable<Boolean> cir
    ) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (((CanBeCubic) entity.level()).cc_isCubic()) {
            cir.setReturnValue(cc_findLoadedFloor(entity, x, y, z, particles, forbidden));
        }
    }

    private boolean cc_findLoadedFloor(LivingEntity entity, double x, double y, double z, boolean particles, Predicate<BlockState> forbidden) {
        Level level = entity.level();
        Vec3 target = level.getWorldBorder().clampVec3ToBound(x, y, z);
        var floor = BlockPos.containing(target).mutable();
        if (CubeMobBlockView.loadedCube(level, floor) == null) {
            return false;
        }
        double landingY = target.y;
        while (floor.getY() > CubicBlockBounds.MIN) {
            floor.move(Direction.DOWN);
            var cube = CubeMobBlockView.loadedCube(level, floor);
            // A gap ends the search; do not load terrain or scan the whole cubic height range.
            if (cube == null) {
                return false;
            }
            BlockState state = cube.getBlockState(floor);
            if (state.is(BlockTags.ENTITIES_CAN_TELEPORT_TO)) {
                return !forbidden.test(state) && cc_land(entity, target.x, landingY, target.z, particles, forbidden);
            }
            landingY--;
        }
        return false;
    }

    private boolean cc_land(LivingEntity entity, double x, double y, double z, boolean particles, Predicate<BlockState> forbidden) {
        Level level = entity.level();
        AABB box = ((EntityBoundsAccess) entity).cc_makeBoundingBox(new Vec3(x, y, z));
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(Math.nextDown(box.maxX), Math.nextDown(box.maxY), Math.nextDown(box.maxZ));
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            var cube = CubeMobBlockView.loadedCube(level, pos);
            if (cube == null) {
                return false;
            }
            BlockState state = cube.getBlockState(pos);
            if (!state.getFluidState().isEmpty() || forbidden.test(state)) {
                return false;
            }
        }
        if (!level.noCollision(box) || !entity.canRandomlyTeleportTo(x, y, z)) {
            return false;
        }
        entity.teleportTo(x, y, z);
        if (particles) {
            level.broadcastEntityEvent(entity, EntityEvent.TELEPORT);
        }
        if (entity instanceof PathfinderMob mob) {
            mob.getNavigation().stop();
        }
        return true;
    }
}
