package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeSpecialSpawnSupport;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeTraderSpawning;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WanderingTraderSpawner.class)
public class MixinWanderingTraderSpawner {
    @Shadow @Final private RandomSource random;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cc_tick(ServerLevel level, boolean enemies, CallbackInfo ci) {
        if (((CanBeCubic) level).cc_isCubic() && !CubeSpecialSpawnSupport.enabled(level)) {
            ci.cancel();
        }
    }

    @Inject(method = "spawn", at = @At("HEAD"), cancellable = true)
    private void cc_spawn(ServerLevel level, CallbackInfoReturnable<Boolean> cir) {
        if (((CanBeCubic) level).cc_isCubic()) {
            cir.setReturnValue(CubeTraderSpawning.spawn(level, random));
        }
    }
}
