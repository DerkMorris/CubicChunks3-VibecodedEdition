package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.entity.ai.poi;

import io.github.opencubicchunks.cubicchunks.world.level.poi.PoiRetryKeys;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.sensing.NearestBedSensor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NearestBedSensor.class)
public abstract class MixinNearestBedSensor {
    @Shadow @Final private Long2LongMap batchCache;

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "()Lit/unimi/dsi/fastutil/longs/Long2LongOpenHashMap;"))
    private Long2LongOpenHashMap cc_cache() {
        return new PoiRetryKeys.Times();
    }

    @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;asLong()J"))
    private long cc_key(BlockPos pos) {
        return ((PoiRetryKeys.Times) batchCache).key(pos);
    }
}
