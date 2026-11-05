package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.level;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.BiomeGenerationRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WorldGenRegion.class)
public abstract class MixinWorldGenRegion {
    /** The published cube reconciles its POIs on load. A staging chunk must not modify the real world's POI store. */
    @WrapWithCondition(method = "setBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;updatePOIOnBlockStateChange(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private boolean cc_livePoiUpdates(ServerLevel level, BlockPos pos, BlockState previous, BlockState next) {
        return !((Object) this instanceof BiomeGenerationRegion);
    }
}
