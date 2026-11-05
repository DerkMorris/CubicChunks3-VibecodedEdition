package io.github.opencubicchunks.cubicchunks.mixin.core.client.multiplayer;

import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeEntityTicking;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubicClientEntityManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TransientEntitySectionManager.class)
public abstract class MixinTransientEntitySectionManager implements CubicClientEntityManager {
    @Shadow @Final private LevelCallback<Entity> callbacks;
    @Unique
    private @Nullable ClientCubeEntityTicking cc_ticks;

    @Override public void cc_configureCubeTicks(Predicate<CubePos> loaded) {
        if (cc_ticks != null) {
            throw new IllegalStateException("Client cube entity ticking is already configured");
        }
        cc_ticks = new ClientCubeEntityTicking(callbacks, loaded);
    }

    @Override public @Nullable ClientCubeEntityTicking cc_cubeTicks() {
        return cc_ticks;
    }

    @WrapOperation(method = "addEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityAccess;setLevelCallback(Lnet/minecraft/world/level/entity/EntityInLevelCallback;)V"))
    private void cc_wrapCubeMovement(EntityAccess entity, EntityInLevelCallback callback, Operation<Void> original) {
        original.call(entity, cc_ticks == null ? callback : cc_ticks.wrap((Entity) entity, callback));
    }

    @Inject(method = "addEntity", at = @At("RETURN"))
    private void cc_activateCubeEntity(EntityAccess entity, CallbackInfo ci) {
        if (cc_ticks != null) {
            cc_ticks.activate((Entity) entity);
        }
    }

    /** Native sections stay TRACKED: the full-coordinate cube index alone enables non-player ticks. */
    @Inject(method = { "startTicking", "stopTicking" }, at = @At("HEAD"), cancellable = true)
    private void cc_skipColumnTicking(CallbackInfo ci) {
        if (cc_ticks != null) {
            ci.cancel();
        }
    }
}
