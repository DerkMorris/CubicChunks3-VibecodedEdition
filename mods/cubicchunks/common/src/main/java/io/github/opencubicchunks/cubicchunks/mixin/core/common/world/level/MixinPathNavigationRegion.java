package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pathfinding reads a bounded cube view; the vanilla column array stays empty in cubic worlds. */
@Mixin(PathNavigationRegion.class)
public class MixinPathNavigationRegion {
    @Unique
    private CubeMobBlockView cc_blocks;

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkSource;getChunkNow(II)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk cc_skipColumns(ChunkSource source, int x, int z, Operation<LevelChunk> original, @Local(argsOnly = true) Level level) {
        return ((CanBeCubic) level).cc_isCubic() ? null : original.call(source, x, z);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cc_cubeRegion(Level level, BlockPos start, BlockPos end, CallbackInfo ci) {
        if (((CanBeCubic) level).cc_isCubic()) {
            cc_blocks = new CubeMobBlockView(level, start, end, true);
        }
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void cc_block(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (cc_blocks != null) {
            cir.setReturnValue(cc_blocks.getBlockState(pos));
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void cc_fluid(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (cc_blocks != null) {
            cir.setReturnValue(cc_blocks.getFluidState(pos));
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void cc_blockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (cc_blocks != null) {
            cir.setReturnValue(cc_blocks.getBlockEntity(pos));
        }
    }

    @Inject(method = "getChunkForCollisions", at = @At("HEAD"), cancellable = true)
    private void cc_collisions(int x, int z, CallbackInfoReturnable<BlockGetter> cir) {
        if (cc_blocks != null) {
            cir.setReturnValue(cc_blocks);
        }
    }

    @Inject(method = "getMinY", at = @At("HEAD"), cancellable = true)
    private void cc_minY(CallbackInfoReturnable<Integer> cir) {
        if (cc_blocks != null) {
            cir.setReturnValue(cc_blocks.getMinY());
        }
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void cc_height(CallbackInfoReturnable<Integer> cir) {
        if (cc_blocks != null) {
            cir.setReturnValue(cc_blocks.getHeight());
        }
    }
}
