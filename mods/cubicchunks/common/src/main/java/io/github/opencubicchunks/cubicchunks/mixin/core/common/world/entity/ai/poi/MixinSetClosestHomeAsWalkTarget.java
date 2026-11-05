package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.entity.ai.poi;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.opencubicchunks.cubicchunks.world.level.poi.PoiRetryKeys;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.SetClosestHomeAsWalkTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SetClosestHomeAsWalkTarget.class)
public abstract class MixinSetClosestHomeAsWalkTarget {
    @Redirect(method = "create", at = @At(value = "NEW", target = "()Lit/unimi/dsi/fastutil/longs/Long2LongOpenHashMap;"))
    private static Long2LongOpenHashMap cc_cache() {
        return new PoiRetryKeys.Times();
    }

    @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;asLong()J"))
    private static long cc_key(BlockPos pos, @Local(argsOnly = true) Long2LongMap cache) {
        return ((PoiRetryKeys.Times) cache).key(pos);
    }
}
