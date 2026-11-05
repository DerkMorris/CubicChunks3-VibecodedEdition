package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.entity;

import java.io.IOException;
import java.nio.file.Path;

import javax.annotation.Nullable;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeEntityPersistence;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeEntityVisibility;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubicEntityManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PersistentEntitySectionManager.class)
public abstract class MixinPersistentEntitySectionManager implements CubicEntityManager {
    @Shadow @Final private EntityPersistentStorage<?> permanentStorage;
    @Unique
    private @Nullable ServerLevel cc_level;
    @Unique
    private @Nullable CubeEntityPersistence cc_persistence;
    @Unique
    private @Nullable CubeEntityVisibility cc_visibility;

    @Override public void cc_configureEntityStorage(ServerLevel level, Path directory) {
        if (cc_persistence != null) {
            throw new IllegalStateException("Entity storage already configured");
        }
        cc_level = level;
        cc_persistence = new CubeEntityPersistence(level, (PersistentEntitySectionManager<Entity>) (Object) this, directory);
        cc_visibility = new CubeEntityVisibility((PersistentEntitySectionManager<Entity>) (Object) this);
    }

    @Override public @Nullable CubeEntityPersistence cc_entityPersistence() {
        return cc_level != null && ((CanBeCubic) cc_level).cc_isCubic() ? cc_persistence : null;
    }

    @Override public @Nullable CubeEntityVisibility cc_entityVisibility() {
        return cc_level != null && ((CanBeCubic) cc_level).cc_isCubic() ? cc_visibility : null;
    }

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

    @ModifyReturnValue(method = "getEntityGetter", at = @At("RETURN"))
    private LevelEntityGetter<Entity> cc_fullHeightQueries(LevelEntityGetter<Entity> original) {
        CubeEntityVisibility visibility = cc_entityVisibility();
        return visibility == null ? original : visibility.getter();
    }

    @Inject(method = "saveAll", at = @At("HEAD"), cancellable = true)
    private void cc_saveCubes(CallbackInfo ci) {
        CubeEntityPersistence cubes = cc_entityPersistence();
        if (cubes != null) {
            cubes.saveAll();
            ci.cancel();
        }
    }

    @Inject(method = "autoSave", at = @At("HEAD"), cancellable = true)
    private void cc_autoSaveCubes(CallbackInfo ci) {
        var cubes = cc_entityPersistence();
        if (cubes != null) {
            cubes.autoSave();
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cc_tickCubeSaves(CallbackInfo ci) {
        var cubes = cc_entityPersistence();
        if (cubes != null) {
            cubes.tick();
            ci.cancel();
        }
    }

    /** Column visibility must not queue vanilla disk reads/writes or unload entities in other cube heights. */
    @Inject(method = { "processPendingLoads", "updateChunkStatus(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/server/level/FullChunkStatus;)V",
        "updateChunkStatus(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/entity/Visibility;)V" }, at = @At("HEAD"), cancellable = true)
    private void cc_skipColumnPersistence(CallbackInfo ci) {
        if (cc_entityPersistence() != null) {
            ci.cancel();
        }
    }

    @WrapMethod(method = "close")
    private void cc_closeCubes(Operation<Void> original) throws IOException {
        CubeEntityPersistence cubes = cc_entityPersistence();
        if (cubes == null) {
            original.call();
            return;
        }
        try (CubeEntityPersistence closeCubes = cubes) {
            this.permanentStorage.close();
        }
    }
}
