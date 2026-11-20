package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.gameevent;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.gameevent.CubeGameEvents;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameEventDispatcher.class)
public class MixinGameEventDispatcher {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "post", at = @At("HEAD"), cancellable = true)
    private void cc_dispatch(Holder<GameEvent> event, Vec3 source, GameEvent.Context context, CallbackInfo ci) {
        if (((CanBeCubic) level).cc_isCubic()) {
            CubeGameEvents.post(level, event, source, context);
            ci.cancel();
        }
    }
}
