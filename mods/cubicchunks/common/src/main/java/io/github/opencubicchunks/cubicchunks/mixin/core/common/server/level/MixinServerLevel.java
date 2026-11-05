package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.level;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddMethodToSets;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddTransformToSets;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.notstirred.dasm.api.annotations.transform.TransformFromMethod;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.MixinLevel;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ChunkToCloSet;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ChunkToCubeSet;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.server.level.ServerCubeCache;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.LevelClo;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeEntityPersistence;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubicEntityManager;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.NativeBiomeTerrain;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.ColumnSurfaceResult;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.ColumnSkyLightResult;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBounds;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightQuery;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightStatus;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightUpdate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Dasm(value = ChunkToCloSet.class, target = @Ref(ServerLevel.class))
@Mixin(ServerLevel.class)
public abstract class MixinServerLevel extends MixinLevel implements CubicServerLevel {
    @Unique
    private NativeBiomeTerrain cc_nativeTerrain;

    @Override public synchronized NativeBiomeTerrain cc_nativeBiomeTerrain() {
        if (cc_nativeTerrain == null) {
            cc_nativeTerrain = new NativeBiomeTerrain((ServerLevel) (Object) this);
        }
        return cc_nativeTerrain;
    }

    @Override public CompletableFuture<CubeLightStatus> cc_getCubeLightStatus() {
        return ((ServerCubeCache) this.chunkSource).cc_getCubeLightStatus();
    }

    @Shadow @Final private ServerChunkCache chunkSource;

    @Override public CompletableFuture<CubeLightUpdate> cc_rebuildCubeLighting(CubeLightBounds bounds) {
        return ((ServerCubeCache) this.chunkSource).cc_rebuildCubeLighting(bounds);
    }

    @Override public CompletableFuture<CubeLightQuery> cc_queryCubeLight(LightLayer layer, BlockPos pos) {
        return ((ServerCubeCache) this.chunkSource).cc_queryCubeLight(layer, pos);
    }

    @Override public CompletableFuture<ColumnSkyLightResult> cc_querySkyLightColumn(int blockX, int blockZ, int minCubeY, int maxCubeY) {
        return ((ServerCubeCache) this.chunkSource).cc_querySkyLightColumn(blockX, blockZ, minCubeY, maxCubeY);
    }

    @Override public CompletableFuture<ColumnSurfaceResult> cc_queryColumnSurface(Heightmap.Types type, int blockX, int blockZ, int minCubeY, int maxCubeY) {
        return ((ServerCubeCache) this.chunkSource).cc_queryColumnSurface(type, blockX, blockZ, minCubeY, maxCubeY);
    }

    @Shadow @Final private PersistentEntitySectionManager<Entity> entityManager;

    @Inject(method = "<init>", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerLevel;entityManager:Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void cc_configureCubeEntities(
            MinecraftServer server, Executor dispatcher, LevelStorageSource.LevelStorageAccess storage, ServerLevelData data,
            ResourceKey<net.minecraft.world.level.Level> dimension, LevelStem stem, boolean debug, long seed, List spawners, boolean tickTime,
            CallbackInfo ci
    ) {
        ((CubicEntityManager) entityManager).cc_configureEntityStorage((ServerLevel) (Object) this,
                storage.getDimensionPath(dimension).resolve("cube_entities"));
    }

    @Override public void cc_onCubeEntityStatus(CubePos pos, FullChunkStatus status) {
        CubeEntityPersistence cubes = ((CubicEntityManager) entityManager).cc_entityPersistence();
        if (cubes != null && status != FullChunkStatus.INACCESSIBLE) {
            cubes.load(pos);
        }
        var visibility = ((CubicEntityManager) entityManager).cc_entityVisibility();
        if (visibility != null) {
            visibility.update(pos, status);
        }
    }

    @Override public void cc_unloadCubeEntities(CubePos pos) {
        CubeEntityPersistence cubes = ((CubicEntityManager) entityManager).cc_entityPersistence();
        if (cubes != null) {
            cubes.unload(pos);
        }
        var visibility = ((CubicEntityManager) entityManager).cc_entityVisibility();
        if (visibility != null) {
            visibility.update(pos, FullChunkStatus.INACCESSIBLE);
        }
    }

    @Override public boolean cc_tryUnloadCubeEntities(CubePos pos) {
        CubeEntityPersistence cubes = ((CubicEntityManager) entityManager).cc_entityPersistence();
        if (cubes != null && !cubes.tryUnload(pos)) {
            return false;
        }
        var visibility = ((CubicEntityManager) entityManager).cc_entityVisibility();
        if (visibility != null) {
            visibility.update(pos, FullChunkStatus.INACCESSIBLE);
        }
        return true;
    }

    @Override public boolean cc_areCubeEntitiesLoaded(CubePos pos) {
        CubeEntityPersistence cubes = ((CubicEntityManager) entityManager).cc_entityPersistence();
        return cubes != null && cubes.isLoaded(pos);
    }

    /** Close entity storage even if closing the cube cache reports a failed save. */
    @WrapMethod(method = "close")
    private void cc_closeEntitiesAfterFailedSave(Operation<Void> original) throws IOException {
        if (!this.cc_isCubic) {
            original.call();
            return;
        }
        try (PersistentEntitySectionManager<Entity> closeEntities = this.entityManager) {
            this.chunkSource.close();
        }
    }

    @Inject(method = "<init>", at = @At("CTOR_HEAD"))
    private void cc_onInit(
            MinecraftServer server, Executor dispatcher, LevelStorageSource.LevelStorageAccess levelStorageAccess, ServerLevelData serverLevelData,
            ResourceKey dimension, LevelStem levelStem, boolean isDebug, long biomeZoomSeed, List customSpawners, boolean tickTime, CallbackInfo ci
    ) {
        // TODO conditionally mark as cubic based on dimension, config, level data, etc
    }

    /** Local difficulty is a ServerLevel operation in 26.3; use that level's spatial moon brightness and overworld clock. */
    @Inject(method = "getCurrentDifficultyAt", at = @At("HEAD"), cancellable = true)
    private void cc_getCubicDifficulty(BlockPos pos, CallbackInfoReturnable<DifficultyInstance> cir) {
        if (!cc_isCubic) {
            return;
        }
        ServerLevel level = (ServerLevel) (Object) this;
        CubeAccess cube = cc_hasCubeAt(pos) ? cc_getCubeAt(pos) : null;
        long inhabited = cube == null ? 0L : cube.getInhabitedTime();
        float moonBrightness = cube == null ? 0.0F : level.getMoonBrightness(pos);
        cir.setReturnValue(new DifficultyInstance(level.getDifficulty(), level.getOverworldClockTime(), inhabited, moonBrightness));
    }

    @Override public ServerCubeCache cc_getCubeSource() {
        return ((ServerCubeCache) this.chunkSource);
    }

    @AddTransformToSets(ChunkToCloSet.ServerLevel_redirects.class)
    @TransformFromMethod("startTickingChunk(Lnet/minecraft/world/level/chunk/LevelChunk;)V")
    public native void cc_startTickingClo(LevelClo chunk);

    // Respawn data is owned by MinecraftServer in 26.3. Changing it no longer adds or removes persistent spawn tickets.

    @AddMethodToSets(containers = ChunkToCloSet.ServerLevel_redirects.class, method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V")
    public void cc_tickClo(LevelClo levelClo, int randomTickSpeed) {
        if (levelClo instanceof LevelCube levelCube) {
            cc_tickCube(levelCube, randomTickSpeed);
        } else {
            // TODO (P2) chunk ticking for anything that still needs to happen on chunks (probably just the forge event for mods?)
        }
    }

    @AddMethodToSets(containers = ChunkToCubeSet.ServerLevel_redirects.class, method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V")
    public void cc_tickCube(LevelCube levelCube, int randomTickSpeed) {
        // TODO (P2) cube ticking
    }

    // TODO (P2) waitForChunkAndEntities

    // TODO: comments below don't account for 1.20.4->1.21.5 changes; will need to check for other methods that need CC changes

    // TODO: phase 3 - isNaturalSpawningAllowed

    // TODO: phase 3 - invalidateCapabilites, neoforge api

    // TODO: phase 4 - setCubeForced - new function

    // TODO: saveDebugReport - mixins, debug only, low priority, if we really really really really need it

    @Inject(method = "isPositionEntityTicking", at = @At("HEAD"), cancellable = true)
    private void cc_entityTickingPosition(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        var visibility = ((CubicEntityManager) entityManager).cc_entityVisibility();
        if (visibility != null) {
            cir.setReturnValue(visibility.canTick(pos));
        }
    }

    /** Keep the native freeze/despawn/passenger loop, but test distance at the entity's cube, including Y. */
    @WrapOperation(method = "lambda$tick$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/DistanceManager;inEntityTickingRange(J)Z"))
    private boolean cc_cubeEntityTickRange(DistanceManager manager, long column, Operation<Boolean> original, @Local(argsOnly = true) Entity entity) {
        var visibility = ((CubicEntityManager) entityManager).cc_entityVisibility();
        return visibility == null ? original.call(manager, column)
                : visibility.canTick(entity.blockPosition()) && original.call(manager, CloPos.cube(CubePos.from(entity.blockPosition())).toLong());
    }

}
