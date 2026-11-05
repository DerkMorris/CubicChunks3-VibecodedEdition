package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.entity;

import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep native fluid heights/current calculations, replacing the column-section preflight and its block reader. */
@Mixin(EntityFluidInteraction.class)
public class MixinEntityFluidInteraction {
    @Inject(method = "hasFluidAndLoaded", at = @At("HEAD"), cancellable = true)
    private static void cc_loadedFluidSections(Level level, int x0, int y0, int z0, int x1, int y1, int z1, CallbackInfoReturnable<Boolean> cir) {
        if (((CanBeCubic) level).cc_isCubic()) {
            cir.setReturnValue(cc_hasFluid(level, new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1)));
        }
    }

    private static boolean cc_hasFluid(Level level, BlockPos min, BlockPos max) {
        if (!CubicBlockBounds.contains(min) || !CubicBlockBounds.contains(max)) {
            return false;
        }
        boolean fluid = false;
        for (int x = Coords.blockToCube(min.getX()); x <= Coords.blockToCube(max.getX()); x++) {
            for (int y = Coords.blockToCube(min.getY()); y <= Coords.blockToCube(max.getY()); y++) {
                for (int z = Coords.blockToCube(min.getZ()); z <= Coords.blockToCube(max.getZ()); z++) {
                    var cube = CubeMobBlockView.loadedCube(level,
                            new BlockPos(Coords.cubeToMinBlock(x), Coords.cubeToMinBlock(y), Coords.cubeToMinBlock(z)));
                    if (cube == null) {
                        return false;
                    }
                    for (var section : cube.getSections()) {
                        fluid |= section.hasFluid();
                    }
                }
            }
        }
        return fluid;
    }

    @ModifyVariable(method = "update*", at = @At("STORE"))
    private BlockGetter cc_fluidReader(BlockGetter original) {
        return original instanceof Level level && ((CanBeCubic) level).cc_isCubic() ? new CubeMobBlockView(level) : original;
    }
}
