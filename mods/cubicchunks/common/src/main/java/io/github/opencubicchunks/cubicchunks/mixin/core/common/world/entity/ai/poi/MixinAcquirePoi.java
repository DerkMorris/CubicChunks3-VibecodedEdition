package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.entity.ai.poi;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.opencubicchunks.cubicchunks.world.level.poi.PoiRetryKeys;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.AcquirePoi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(AcquirePoi.class)
public abstract class MixinAcquirePoi {
    @Redirect(method = "create(Ljava/util/function/Predicate;Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;ZLjava/util/Optional;Ljava/util/function/BiPredicate;)Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;", at = @At(value = "NEW", target = "()Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;"))
    private static Long2ObjectOpenHashMap<?> cc_cache() {
        return new PoiRetryKeys.Objects<>();
    }

    @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;asLong()J"))
    private static long cc_key(BlockPos pos, @Local(argsOnly = true) Long2ObjectMap<?> cache) {
        return ((PoiRetryKeys.Objects<?>) cache).key(pos);
    }
}
