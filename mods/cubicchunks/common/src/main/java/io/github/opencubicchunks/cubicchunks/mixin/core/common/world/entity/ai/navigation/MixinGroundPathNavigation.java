package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.entity.ai.navigation;

import java.util.Set;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Surface adjustment is local and stops at missing terrain instead of scanning a vanilla column. */
@Mixin(GroundPathNavigation.class)
public abstract class MixinGroundPathNavigation extends PathNavigation {
    private static final int cc_TARGET_SEARCH_PADDING = 8;
    @Shadow private boolean canPathToTargetsBelowSurface;

    protected MixinGroundPathNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Inject(method = "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("HEAD"), cancellable = true)
    private void cc_cubeTarget(BlockPos pos, int range, CallbackInfoReturnable<Path> cir) {
        if (!((CanBeCubic) level).cc_isCubic()) {
            return;
        }
        BlockPos target = cc_surface(pos);
        cir.setReturnValue(target == null ? null : createPath(Set.of(target), cc_TARGET_SEARCH_PADDING, false, range));
    }

    private BlockPos cc_surface(BlockPos pos) {
        CubeMobBlockView blocks = new CubeMobBlockView(level);
        if (CubeMobBlockView.loadedCube(level, pos) == null) {
            return null;
        }
        if (canPathToTargetsBelowSurface) {
            return pos;
        }
        int direction = blocks.getBlockState(pos).isAir() ? -1 : 1;
        BlockPos current = pos;
        for (int step = 0; step <= CubeMobBlockView.MAX_PATH_LENGTH; step++) {
            if (CubeMobBlockView.loadedCube(level, current) == null) {
                return null;
            }
            var state = blocks.getBlockState(current);
            if (direction < 0 && !state.isAir()) {
                return current.above();
            }
            if (direction > 0 && !state.isSolid()) {
                return current;
            }
            current = current.offset(0, direction, 0);
        }
        return null;
    }
}
