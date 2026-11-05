package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.levelgen.feature;

import io.github.opencubicchunks.cubicchunks.world.level.levelgen.BiomeGenerationRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({ OreFeature.class, ScatteredOreFeature.class })
public abstract class MixinOreFeature {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void cc_customProfile(
            WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos pos, CallbackInfoReturnable<Boolean> cir
    ) {
        if (level instanceof BiomeGenerationRegion region && region.replaces((AbstractOreFeature) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
