package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightPredicates;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.CropBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Pending relighting must not reject crops whose required light is already proved by a current nearby emitter. */
@Mixin(CropBlock.class)
public abstract class MixinCropLightPredicates {
    private static final int SURVIVAL_LIGHT = 8;
    private static final int GROWTH_LIGHT = 9;

    @WrapOperation(method = "hasSufficientLight", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelReader;getRawBrightness(Lnet/minecraft/core/BlockPos;I)I"))
    private static int cc_survivalLight(LevelReader level, BlockPos pos, int darkening, Operation<Integer> original) {
        int brightness = original.call(level, pos, darkening);
        return brightness < SURVIVAL_LIGHT && CubeLightPredicates.atLeast(level, pos, SURVIVAL_LIGHT) ? SURVIVAL_LIGHT : brightness;
    }

    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getRawBrightness(Lnet/minecraft/core/BlockPos;I)I"))
    private int cc_growthLight(ServerLevel level, BlockPos pos, int darkening, Operation<Integer> original) {
        int brightness = original.call(level, pos, darkening);
        return brightness < GROWTH_LIGHT && CubeLightPredicates.atLeast(level, pos, GROWTH_LIGHT) ? GROWTH_LIGHT : brightness;
    }
}
