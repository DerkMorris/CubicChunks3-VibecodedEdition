package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeEntityVisibility;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubicEntityManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Register cubic entity callbacks after this loader's entity-join event has accepted the entity. */
@Mixin(PersistentEntitySectionManager.class)
public abstract class MixinPersistentEntityRegistration implements CubicEntityManager {
    @WrapOperation(method = "addEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityAccess;setLevelCallback(Lnet/minecraft/world/level/entity/EntityInLevelCallback;)V"))
    private void cc_wrapEntityCallback(EntityAccess entity, EntityInLevelCallback callback, Operation<Void> original) {
        CubeEntityVisibility visibility = cc_entityVisibility();
        original.call(entity, visibility == null ? callback : visibility.wrap((Entity) entity, callback));
    }

    @Inject(method = "addEntity", at = @At("RETURN"))
    private void cc_activateEntity(EntityAccess entity, boolean loaded, CallbackInfoReturnable<Boolean> cir) {
        CubeEntityVisibility visibility = cc_entityVisibility();
        if (visibility != null && cir.getReturnValue()) {
            visibility.activate((Entity) entity);
        }
    }

}
