package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level;

import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeSpawnContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Level.class, priority = 1100)
public class MixinSpawnBlockReads {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void cc_spawnBlock(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        var context = CubeSpawnContext.current(this);
        if (context != null) {
            var cube = context.cube(pos);
            cir.setReturnValue(cube == null ? Blocks.BARRIER.defaultBlockState() : cube.getBlockState(pos));
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void cc_spawnFluid(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        var context = CubeSpawnContext.current(this);
        if (context != null) {
            var cube = context.cube(pos);
            cir.setReturnValue(cube == null ? Fluids.EMPTY.defaultFluidState() : cube.getFluidState(pos));
        }
    }

    @Inject(method = "getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I", at = @At("HEAD"), cancellable = true)
    private void cc_spawnUnknownColumn(Heightmap.Types type, int x, int z, CallbackInfoReturnable<Integer> cir) {
        var context = CubeSpawnContext.current(this);
        if (context != null) {
            // A local cube cannot establish the global surface. Predicates needing it must defer this attempt.
            context.reject();
            cir.setReturnValue(CubicBlockBounds.MIN);
        }
    }
}
