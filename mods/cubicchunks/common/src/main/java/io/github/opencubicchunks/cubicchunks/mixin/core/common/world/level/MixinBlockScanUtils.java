package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockScan;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Continuation;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.blockscan.BlockScanUtils;
import net.minecraft.world.level.blockscan.BlockStateConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockScanUtils.class)
public abstract class MixinBlockScanUtils {
    @Inject(method = "findBlocks(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Ljava/util/function/Predicate;Lnet/minecraft/world/level/blockscan/BlockStateConsumer;)Z", at = @At("HEAD"), cancellable = true)
    private static void cc_scanBox(
            LevelReader level, BlockPos from, BlockPos to, Predicate<BlockState> filter, BlockStateConsumer consumer,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (level instanceof CanBeCubic cubic && cubic.cc_isCubic()) {
            cir.setReturnValue(CubicBlockScan.box((CubicLevelReader) level, from, to, filter, consumer));
        }
    }

    @Inject(method = "findBlocksWithCache(Lnet/minecraft/world/level/LevelReader;Ljava/lang/Iterable;Ljava/util/function/Predicate;Lnet/minecraft/world/level/blockscan/BlockStateConsumer;)Z", at = @At("HEAD"), cancellable = true)
    private static void cc_scanOrdered(
            LevelReader level, Iterable<BlockPos> positions, Predicate<BlockState> filter, BlockStateConsumer consumer,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (level instanceof CanBeCubic cubic && cubic.cc_isCubic()) {
            cir.setReturnValue(CubicBlockScan.ordered((CubicLevelReader) level, positions, filter, consumer));
        }
    }

    // NeoForge retains the vanilla methods and adds overloads with a position predicate.
    @Inject(method = "findBlocks(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Ljava/util/function/Predicate;Ljava/util/function/BiPredicate;Lnet/minecraft/world/level/blockscan/BlockStateConsumer;)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void cc_scanPositionBox(
            LevelReader level, BlockPos from, BlockPos to, Predicate<BlockState> filter, BiPredicate<BlockState, BlockPos> position,
            BlockStateConsumer consumer, CallbackInfoReturnable<Boolean> cir
    ) {
        if (level instanceof CanBeCubic cubic && cubic.cc_isCubic()) {
            cir.setReturnValue(CubicBlockScan.box((CubicLevelReader) level, from, to, filter,
                    (pos, state) -> position.test(state, pos) ? consumer.apply(pos, state) : Continuation.CONTINUE));
        }
    }

    @Inject(method = "findBlocksWithCache(Lnet/minecraft/world/level/LevelReader;Ljava/lang/Iterable;Ljava/util/function/Predicate;Ljava/util/function/BiPredicate;Lnet/minecraft/world/level/blockscan/BlockStateConsumer;)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void cc_scanPositionOrdered(
            LevelReader level, Iterable<BlockPos> positions, Predicate<BlockState> filter, BiPredicate<BlockState, BlockPos> position,
            BlockStateConsumer consumer, CallbackInfoReturnable<Boolean> cir
    ) {
        if (level instanceof CanBeCubic cubic && cubic.cc_isCubic()) {
            cir.setReturnValue(CubicBlockScan.ordered((CubicLevelReader) level, positions, filter,
                    (pos, state) -> position.test(state, pos) ? consumer.apply(pos, state) : Continuation.CONTINUE));
        }
    }
}
