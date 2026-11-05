package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.level;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.DataFixer;
import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddFieldToSets;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddMethodToSets;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddTransformToSets;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.notstirred.dasm.api.annotations.transform.AddUnusedParam;
import io.github.notstirred.dasm.api.annotations.transform.TransformFromMethod;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.chunk.MixinChunkSource;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ChunkToCloSet;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ChunkToCubeSet;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.GlobalSet;
import io.github.opencubicchunks.cubicchunks.server.level.CubicChunkMap;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.server.level.ServerCubeCache;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeNaturalSpawner;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeSpecialSpawnSupport;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.ColumnSurfaceResult;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.ColumnSkyLightResult;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBounds;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightPacketSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightQuery;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightStatus;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightUpdate;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The vanilla {@link ServerChunkCache} class stores all loaded chunks on the server and has methods for getting chunks, ticking all chunks, handling
 * block+light updates, managing chunkloading tickets, etc. This mixin adds versions of these methods for cubes, meaning that this class now stores
 * and manages both cubes and chunks.
 */
@Dasm(value = ChunkToCubeSet.class, target = @Ref(ServerChunkCache.class))
@Mixin(ServerChunkCache.class)
public abstract class MixinServerChunkCache extends MixinChunkSource implements ServerCubeCache {
    @Override public @Nullable CubeLightView cc_getCubeLightView(CubePos pos) {
        return ((CubicChunkMap) this.chunkMap).cc_getCubeLightView(pos);
    }

    @Override public void cc_onCubeBlockDataChanged(CubeAccess cube) {
        ((CubicChunkMap) this.chunkMap).cc_onCubeBlockDataChanged(cube);
    }

    @Override public CubeLightPacketSnapshot cc_captureCubeLight(LevelCube cube) {
        return ((CubicChunkMap) this.chunkMap).cc_captureCubeLight(cube);
    }

    @Override public CompletableFuture<CubeLightUpdate> cc_rebuildCubeLighting(CubeLightBounds bounds) {
        return ((CubicChunkMap) this.chunkMap).cc_rebuildCubeLighting(bounds);
    }

    @Override public CompletableFuture<CubeLightStatus> cc_getCubeLightStatus() {
        return ((CubicChunkMap) this.chunkMap).cc_getCubeLightStatus();
    }

    @Override public boolean cc_requestCubeLightRefresh(ServerPlayer player, CubePos pos, long observedEpoch) {
        return ((CubicChunkMap) this.chunkMap).cc_requestCubeLightRefresh(player, pos, observedEpoch);
    }

    @Override public CompletableFuture<CubeLightQuery> cc_queryCubeLight(LightLayer layer, BlockPos pos) {
        return ((CubicChunkMap) this.chunkMap).cc_queryCubeLight(layer, pos);
    }

    @Override public CompletableFuture<ColumnSkyLightResult> cc_querySkyLightColumn(int blockX, int blockZ, int minCubeY, int maxCubeY) {
        return ((CubicChunkMap) this.chunkMap).cc_querySkyLightColumn(blockX, blockZ, minCubeY, maxCubeY);
    }

    @Override public CompletableFuture<ColumnSurfaceResult> cc_queryColumnSurface(Heightmap.Types type, int blockX, int blockZ, int minCubeY, int maxCubeY) {
        return ((CubicChunkMap) this.chunkMap).cc_queryColumnSurface(type, blockX, blockZ, minCubeY, maxCubeY);
    }

    // Cube equivalents for cached chunks
    @Shadow @Final private static int CACHE_SIZE;

    @AddFieldToSets(containers = ChunkToCubeSet.ServerChunkCache_redirects.class, field = "lastChunkPos:[J")
    private final long[] cc_lastCubePos = new long[CACHE_SIZE];

    @AddFieldToSets(containers = ChunkToCubeSet.ServerChunkCache_redirects.class, field = "lastChunkStatus:[Lnet/minecraft/world/level/chunk/status/ChunkStatus;")
    private final ChunkStatus[] cc_lastCubeStatus = new ChunkStatus[CACHE_SIZE];

    @AddFieldToSets(containers = ChunkToCubeSet.ServerChunkCache_redirects.class, field = "lastChunk:[Lnet/minecraft/world/level/chunk/ChunkAccess;")
    private final CubeAccess[] cc_lastCube = new CubeAccess[CACHE_SIZE];

    @AddFieldToSets(containers = ChunkToCubeSet.ServerChunkCache_redirects.class, field = "spawningChunks:Ljava/util/List;")
    private final List<LevelCube> cc_spawningCubes = new ObjectArrayList<>();

    @Shadow @Final public ServerLevel level;

    @Shadow protected abstract @Nullable ChunkHolder getVisibleChunkIfPresent(long pChunkPos);

    @Shadow @Final Thread mainThread;

    @Shadow @Final private ServerChunkCache.MainThreadExecutor mainThreadProcessor;

    @Shadow @Final private DistanceManager distanceManager;

    @Shadow @Final public ChunkMap chunkMap;

    @Shadow @Final private ThreadedLevelLightEngine lightEngine;

    @Shadow @Final private SavedDataStorage savedDataStorage;

    @Shadow public abstract void save(boolean flush);

    /** Release storage even when a cube save fails; retain the save error and suppress any cleanup failures. */
    @WrapMethod(method = "close")
    private void cc_closeAfterFailedSave(Operation<Void> original) throws IOException {
        if (!this.cc_isCubic) {
            original.call();
            return;
        }
        // Reverse resource order matches vanilla's data, lighting, then chunk-storage shutdown.
        try (ChunkMap closeMap = this.chunkMap;
                ThreadedLevelLightEngine closeLight = this.lightEngine;
                SavedDataStorage closeData = this.savedDataStorage) {
            this.save(true);
        }
    }

    @Shadow private @Nullable NaturalSpawner.SpawnState lastSpawnState;

    @Shadow protected abstract void getFullChunk(long chunkPos, Consumer<LevelChunk> fullChunkGetter);

    @Shadow @Final private Set<ChunkHolder> chunkHoldersToBroadcast;

    @Inject(method = "<init>", at = @At(value = "CTOR_HEAD", unsafe = true))
    private void cc_onInit(
            ServerLevel level, LevelStorageSource.LevelStorageAccess levelStorageAccess, DataFixer fixerUpper,
            StructureTemplateManager structureManager, Executor dispatcher, ChunkGenerator generator, int viewDistance, int simulationDistance,
            boolean sync, ChunkStatusUpdateListener chunkStatusListener, CallbackInfo ci
    ) {
        if (((CanBeCubic) level).cc_isCubic()) {
            this.cc_setCubic();
        }
    }

    @AddTransformToSets(ChunkToCubeSet.ServerChunkCache_redirects.class)
    @TransformFromMethod("storeInCache(JLnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/chunk/status/ChunkStatus;)V")
    private native void cc_storeInCache(long pChunkPos, CubeAccess pChunk, ChunkStatus pChunkStatus);

    /** Mirrors 26.3's getChunk, retaining Y in both the cache key and the off-thread dispatch closure. */
    @Override public @Nullable CubeAccess cc_getCube(int cubeX, int cubeY, int cubeZ, ChunkStatus requiredStatus, boolean load) {
        if (Thread.currentThread() != this.mainThread) {
            return CompletableFuture.supplyAsync(() -> this.cc_getCube(cubeX, cubeY, cubeZ, requiredStatus, load), this.mainThreadProcessor).join();
        }
        ProfilerFiller profiler = Profiler.get();
        profiler.incrementCounter("getCube");
        long pos = CloPos.cubeAsLong(cubeX, cubeY, cubeZ);
        for (int i = 0; i < CACHE_SIZE; i++) {
            if (pos == this.cc_lastCubePos[i] && requiredStatus == this.cc_lastCubeStatus[i]) {
                CubeAccess cached = this.cc_lastCube[i];
                if (cached != null || !load) {
                    return cached;
                }
            }
        }
        profiler.incrementCounter("getCubeCacheMiss");
        CompletableFuture<ChunkResult<CubeAccess>> future = this.cc_getCubeFutureMainThread(cubeX, cubeY, cubeZ, requiredStatus, load);
        this.mainThreadProcessor.managedBlock(future::isDone);
        ChunkResult<CubeAccess> result = future.join();
        CubeAccess cube = result.orElse(null);
        if (cube == null && load) {
            throw Util.pauseInIde(new IllegalStateException("Cube not available when requested: " + result.getError()));
        }
        this.cc_storeInCache(pos, cube, requiredStatus);
        return cube;
    }

    @TransformFromMethod("getChunkNow(II)Lnet/minecraft/world/level/chunk/LevelChunk;")
    @Override public native @Nullable LevelCube cc_getCubeNow(int pChunkX, @AddUnusedParam int chunkY, int pChunkZ);

    // The first two params are the x and z coordinates inside the call being redirected; the next three params are the x/y/z coordinates in the
    // params of getCubeNow
    @Dynamic @Redirect(method = "cc_getCubeNow", at = @At(value = "INVOKE", target = "Lio/github/opencubicchunks/cc_core/api/CubePos;dummy_chunkAsLong(II)J"))
    private long cc_getCubeNow_posAsLong(int pX, int pZ, int pXRepeated, int pY, int pZRepeated) {
        return CloPos.cubeAsLong(pX, pY, pZ);
    }

    // Note that we don't add this to any redirect sets; we just need it for the below mixin
    // (Whenever clearing caches, we want to clear all caches, not just cubes or chunks specifically)
    @TransformFromMethod("clearCache()V")
    private native void cc_clearCache();

    /**
     * When clearing caches, clear the cube cache as well as the chunk cache
     */
    @Inject(method = "clearCache", at = @At("HEAD"))
    private void cc_onClearCache(CallbackInfo ci) {
        cc_clearCache();
    }

    // This method requires enough manual redirects that we just replace it entirely
    @Override public CompletableFuture<ChunkResult<CubeAccess>> cc_getCubeFuture(int pX, int chunkY, int pZ, ChunkStatus pChunkStatus, boolean pLoad) {
        boolean flag = Thread.currentThread() == this.mainThread;
        CompletableFuture<ChunkResult<CubeAccess>> completablefuture;
        if (flag) {
            completablefuture = this.cc_getCubeFutureMainThread(pX, chunkY, pZ, pChunkStatus, pLoad);
            this.mainThreadProcessor.managedBlock(completablefuture::isDone);
        } else {
            completablefuture = CompletableFuture
                    .supplyAsync(() -> this.cc_getCubeFutureMainThread(pX, chunkY, pZ, pChunkStatus, pLoad), this.mainThreadProcessor)
                    .thenCompose(future -> future);
        }

        return completablefuture;
    }

    @TransformFromMethod("getChunkFutureMainThread(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;")
    private native CompletableFuture<ChunkResult<CubeAccess>> cc_getCubeFutureMainThread(
            int pX, @AddUnusedParam int chunkY, int pZ, ChunkStatus pChunkStatus, boolean pLoad
    );

    // The first two params are the x and z coordinates inside the call being redirected; the next three params are the x/y/z coordinates in the
    // params of cc_getCubeFutureMainThread
    @Dynamic @Redirect(method = "cc_getCubeFutureMainThread", at = @At(value = "INVOKE", target = "Lio/github/opencubicchunks/cc_core/api/CubePos;dummy_fromChunkCoords(II)Lio/github/opencubicchunks/cc_core/api/CubePos;"))
    private CubePos cc_getCubeFutureMainThread_chunkPosConstruct(int pX, int pZ, int pXRepeated, int pY, int pZRepeated) {
        return CubePos.of(pX, pY, pZ);
    }

    @TransformFromMethod("hasChunk(II)Z")
    public native boolean cc_hasCube(int pX, @AddUnusedParam int y, int pZ);

    // The first two params are the x and z coordinates inside the call being redirected; the next three params are the x/y/z coordinates in the
    // params of cc_hasCube
    @Dynamic @Redirect(method = "cc_hasCube", at = @At(value = "INVOKE", target = "Lio/github/opencubicchunks/cc_core/api/CubePos;dummy_fromChunkCoords(II)Lio/github/opencubicchunks/cc_core/api/CubePos;"))
    private CubePos cc_hasCube_posAsLong(int pX, int pZ, int pXRepeated, int pY, int pZRepeated) {
        return CubePos.of(pX, pY, pZ);
    }

    // TODO (P2) - lighting; currently unused. can probably be done with dasm and @AddUnusedParam
    public @Nullable LightChunk cc_getCubeForLighting(int pChunkX, int chunkY, int pChunkZ) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cc_onTick(BooleanSupplier hasTimeLeft, boolean tickChunks, CallbackInfo ci) {
        if (this.cc_isCubic) {
            ci.cancel();
            cc_tick(hasTimeLeft, tickChunks);
        }
    }

    @AddTransformToSets(GlobalSet.ServerChunkCache_redirects.class)
    @TransformFromMethod("tick(Ljava/util/function/BooleanSupplier;Z)V")
    public native void cc_tick(BooleanSupplier hasTimeLeft, boolean tickChunks);

    @AddTransformToSets(GlobalSet.ServerChunkCache_redirects.class)
    @TransformFromMethod(useRedirectSets = ChunkToCloSet.class, value = "broadcastChangedChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V")
    private native void cc_broadcastChangedClos(ProfilerFiller profiler);

    @Inject(method = "broadcastChangedChunks", at = @At("HEAD"), cancellable = true)
    private void cc_onVanillaBroadcastChangedChunks(ProfilerFiller profiler, CallbackInfo ci) {
        if (cc_isCubic) {
            ci.cancel();
            cc_broadcastChangedClos(profiler);
        }
    }

    @Shadow private boolean spawnEnemies;

    @AddMethodToSets(containers = GlobalSet.ServerChunkCache_redirects.class, method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V")
    private void cc_tickClos(ProfilerFiller profiler) {
        profiler.push("naturalCubeSpawn");
        try {
            // Vanilla's debug SpawnState describes 2D columns and cannot represent these caps.
            lastSpawnState = null;
            CubeNaturalSpawner.tick(level, spawnEnemies);
            int randomTickSpeed = level.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
            ((CubicChunkMap) chunkMap).cc_forEachBlockTickingClo(cube -> ((CubicServerLevel) level).cc_tickClo(cube, randomTickSpeed));
            if (CubeSpecialSpawnSupport.enabled(level)) {
                level.tickCustomSpawners(spawnEnemies);
            }
        } finally {
            profiler.pop();
        }
    }

    @Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("HEAD"), cancellable = true)
    private void cc_onVanillaTickChunks(ProfilerFiller profiler, CallbackInfo ci) {
        if (cc_isCubic) {
            ci.cancel();
            cc_tickClos(profiler);
        }
    }

    @Inject(method = "blockChanged", at = @At("HEAD"), cancellable = true)
    public void cc_onBlockChanged(BlockPos pos, CallbackInfo ci) {
        if (!this.cc_isCubic) {
            return;
        }
        ci.cancel();
        int x = Coords.blockToCube(pos.getX());
        int y = Coords.blockToCube(pos.getY());
        int z = Coords.blockToCube(pos.getZ());
        ChunkHolder chunkholder = this.getVisibleChunkIfPresent(CloPos.cubeAsLong(x, y, z));
        if (chunkholder != null && chunkholder.blockChanged(pos)) {
            this.chunkHoldersToBroadcast.add(chunkholder);
        }
    }

    @Inject(method = "onLightUpdate", at = @At("HEAD"), cancellable = true)
    private void cc_onVanillaOnLightUpdate(LightLayer type, SectionPos pos, CallbackInfo ci) {
        if (this.cc_isCubic) {
            ci.cancel();
            cc_onLightUpdate(type, pos);
        }
    }

    @Override @AddMethodToSets(containers = GlobalSet.ServerChunkCache_redirects.class, method = "onLightUpdate(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/SectionPos;)V")
    public void cc_onLightUpdate(LightLayer pType, SectionPos pPos) {
        // TODO (P2) lighting
    }

    @AddTransformToSets(ChunkToCloSet.ServerChunkCache_redirects.class)
    @TransformFromMethod(useRedirectSets = ChunkToCloSet.class, value = "addTicket(Lnet/minecraft/server/level/Ticket;Lnet/minecraft/world/level/ChunkPos;)V")
    public native void cc_addTicket(Ticket ticket, CloPos cloPos);

    @AddTransformToSets(ChunkToCloSet.ServerChunkCache_redirects.class)
    @TransformFromMethod(useRedirectSets = ChunkToCloSet.class, value = "addTicketWithRadius(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V")
    public native void cc_addTicketWithRadius(TicketType ticket, CloPos cloPos, int radius);

    @AddTransformToSets(ChunkToCloSet.ServerChunkCache_redirects.class)
    @TransformFromMethod(useRedirectSets = ChunkToCloSet.class, value = "removeTicketWithRadius(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V")
    public native void cc_removeTicketWithRadius(TicketType ticket, CloPos cloPos, int radius);

    @AddTransformToSets(ChunkToCloSet.ServerChunkCache_redirects.class)
    @TransformFromMethod(useRedirectSets = ChunkToCloSet.class, value = "updateChunkForced(Lnet/minecraft/world/level/ChunkPos;Z)Z")
    public native boolean cc_updateCloForced(CloPos pPos, boolean pAdd);

    // Cube-specific methods that delegate to the corresponding Clo methods
    @AddMethodToSets(containers = ChunkToCubeSet.ServerChunkCache_redirects.class, method = "addTicket(Lnet/minecraft/server/level/Ticket;Lnet/minecraft/world/level/ChunkPos;)V")
    public void cc_addTicket(Ticket ticket, CubePos cubePos) {
        cc_addTicket(ticket, CloPos.cube(cubePos));
    }

    @AddMethodToSets(containers = ChunkToCubeSet.ServerChunkCache_redirects.class, method = "addTicketWithRadius(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V")
    public void cc_addTicketWithRadius(TicketType ticket, CubePos cubePos, int radius) {
        cc_addTicketWithRadius(ticket, CloPos.cube(cubePos), radius);
    }

    @AddMethodToSets(containers = ChunkToCubeSet.ServerChunkCache_redirects.class, method = "removeTicketWithRadius(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V")
    public void cc_removeTicketWithRadius(TicketType ticket, CubePos cubePos, int radius) {
        cc_removeTicketWithRadius(ticket, CloPos.cube(cubePos), radius);
    }

    @AddMethodToSets(containers = ChunkToCubeSet.ServerChunkCache_redirects.class, method = "updateChunkForced(Lnet/minecraft/world/level/ChunkPos;Z)Z")
    @Override public boolean cc_updateCubeForced(CubePos cubePos, boolean forced) {
        return cc_updateCloForced(CloPos.cube(cubePos), forced);
    }

    @AddTransformToSets(ChunkToCloSet.ServerChunkCache_redirects.class)
    @TransformFromMethod(value = "getChunkDebugData(Lnet/minecraft/world/level/ChunkPos;)Ljava/lang/String;", useRedirectSets = ChunkToCloSet.class)
    public native String cc_getChunkDebugData(CloPos pChunkPos);
}
