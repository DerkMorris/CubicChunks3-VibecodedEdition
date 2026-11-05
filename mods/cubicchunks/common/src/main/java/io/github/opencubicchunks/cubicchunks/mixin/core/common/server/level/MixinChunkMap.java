package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.level;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

import javax.annotation.Nullable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.datafixers.DataFixer;
import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddFieldToSets;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddMethodToSets;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddTransformToSets;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.notstirred.dasm.api.annotations.transform.TransformFromMethod;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.MarkableAsCubic;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.TrackedEntityAccess;
import io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.chunk.storage.MixinChunkStorage;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ChunkToCloSet;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ChunkToCubeSet;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.GlobalSet;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.SectionPosToCubeSet;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeLightInvalidatePacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubeWithLightPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundSetCubeCacheCenterPacket;
import io.github.opencubicchunks.cubicchunks.server.level.CCServerPlayer;
import io.github.opencubicchunks.cubicchunks.server.level.CloGenerationTask;
import io.github.opencubicchunks.cubicchunks.server.level.CloHolder;
import io.github.opencubicchunks.cubicchunks.server.level.CloTrackingView;
import io.github.opencubicchunks.cubicchunks.server.level.CubeHolder;
import io.github.opencubicchunks.cubicchunks.server.level.CubicChunkMap;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.server.level.GeneratingCubeMap;
import io.github.opencubicchunks.cubicchunks.util.StaticCache3D;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.CloAccess;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.LevelClo;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ImposterProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.status.CubeStep;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeSaveQueue;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.SerializableCubeData;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CloStatusUpdateListener;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.ColumnSurfaceIndex;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.ColumnSurfaceResult;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.ColumnSkyLightIndex;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.ColumnSkyLightResult;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBounds;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightManager;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightPacketSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightQuery;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightStatus;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightUpdate;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import io.github.opencubicchunks.cubicchunks.world.level.poi.CubePoiLifecycle;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import io.github.opencubicchunks.cubicchunks.platform.Platform;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Util;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The vanilla {@link ChunkMap} class stores all loaded chunks for a world and handles loading and unloading them, including dependencies on
 * neighboring chunks. This mixin adds cubic chunks equivalents for methods where necessary, to allow ChunkMap to work with CLOs (i.e. both chunks and
 * cubes).
 */
@Dasm(value = ChunkToCloSet.class, target = @Ref(ChunkMap.class))
@Mixin(ChunkMap.class)
public abstract class MixinChunkMap extends MixinChunkStorage implements GeneratingCubeMap, CubicChunkMap, CubeHolder.PlayerProvider {
    @Shadow public abstract ReportedException debugFuturesAndCreateReportedException(IllegalStateException exception, String details);

    @Shadow protected abstract ChunkHolder getUpdatingChunkIfPresent(long aLong);

    @Shadow @Final ServerLevel level;
    @Shadow @Final private ChunkMap.DistanceManager distanceManager;
    @Shadow @Final private BlockableEventLoop<Runnable> mainThreadExecutor;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;
    @Shadow @Final private Long2LongMap nextChunkSaveTime;
    @Shadow @Final private Queue<Runnable> unloadQueue;
    @Shadow @Final private PlayerMap playerMap;
    @Shadow @Final private Int2ObjectMap<ChunkMap.TrackedEntity> entityMap;
    @Unique
    private volatile @Nullable RuntimeException cc_cubeSaveFailure;
    @Unique
    private CubeSaveQueue<CubePos> cc_cubeSaves;
    @Unique
    private static final int CC_SAVE_SNAPSHOTS_PER_PASS = 4;
    @Unique
    private int cc_saveSnapshotsRemaining = Integer.MAX_VALUE;
    @Unique
    private boolean cc_flushingSaves;
    @Unique
    private final Map<Long, ChunkHolder> cc_retryUnloads = new LinkedHashMap<>();
    @Shadow @Final private it.unimi.dsi.fastutil.longs.LongSet chunksToEagerlySave;

    @Unique
    private CubeSaveQueue<CubePos> cc_saves() {
        if (cc_cubeSaves == null) {
            cc_cubeSaves = new CubeSaveQueue<>();
        }
        return cc_cubeSaves;
    }

    @WrapMethod(method = "close")
    private void cc_drainSavesOnClose(Operation<Void> original) throws IOException {
        try {
            if (cc_cubeSaves != null) {
                cc_cubeSaves.awaitAll();
            }
        } catch (RuntimeException failure) {
            CompletionException closeFailure = new CompletionException("Cannot finish asynchronous cube saves during close", failure);
            try {
                original.call();
            } catch (Exception cleanup) {
                closeFailure.addSuppressed(cleanup);
            }
            throw closeFailure;
        }
        original.call();
    }

    @Inject(method = "hasWork", at = @At("HEAD"), cancellable = true)
    private void cc_pendingWritesAreWork(CallbackInfoReturnable<Boolean> cir) {
        if (cc_cubeSaves != null && cc_cubeSaves.hasWork()) {
            cir.setReturnValue(true);
        }
    }

    @Unique
    private @Nullable CubeLightManager cc_cubeLightManager;
    @Unique
    private @Nullable io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightManager cc_worldLights;
    @Unique
    private volatile boolean cc_lightingClosed;
    @Unique
    private static final int CC_LIGHT_REFRESH_INTERVAL = 20;
    @Unique
    private long cc_lightTick;
    @Unique
    private long cc_nextLightRefreshTick;

    @Override public CompletableFuture<CubeLightStatus> cc_getCubeLightStatus() {
        if (this.cc_lightingClosed) {
            return CompletableFuture.completedFuture(new CubeLightStatus(CubeLightStatus.Phase.CLOSED, null, 0, 0, null, false, null));
        }
        if (!this.mainThreadExecutor.isSameThread()) {
            return CompletableFuture.supplyAsync(this::cc_getCubeLightStatus, this.mainThreadExecutor).thenCompose(future -> future);
        }
        if (cc_worldLights != null) {
            return CompletableFuture.completedFuture(cc_worldLights.status());
        }
        return CompletableFuture
                .completedFuture(this.cc_cubeLightManager == null ? new CubeLightStatus(CubeLightStatus.Phase.IDLE, null, 0, 0, null, false, null)
                        : this.cc_cubeLightManager.status());
    }

    @Override public boolean cc_requestCubeLightRefresh(ServerPlayer player, CubePos pos, long observedEpoch) {
        if (!this.cc_mayRequestLightRefresh(player, pos, observedEpoch) || this.cc_lightTick < this.cc_nextLightRefreshTick) {
            return false;
        }
        if (!(cc_worldLights != null ? cc_worldLights.republish(pos) : this.cc_cubeLightManager.republishCurrent(pos))) {
            return false;
        }
        // A single world-wide budget also bounds each player's requests without retaining disconnected players.
        this.cc_nextLightRefreshTick = this.cc_lightTick + CC_LIGHT_REFRESH_INTERVAL;
        return true;
    }

    @Unique
    private boolean cc_mayRequestLightRefresh(ServerPlayer player, CubePos pos, long observedEpoch) {
        if (this.cc_lightingClosed || !this.mainThreadExecutor.isSameThread() || this.cc_cubeLightManager == null && cc_worldLights == null) {
            return false;
        }
        long epoch = cc_currentLightEpoch();
        if (player == null || pos == null || !cc_validObservedLightEpoch(observedEpoch, epoch)) {
            return false;
        }
        return this.cc_isCurrentLightRequester(player) && CubePos.from(pos.asLong()).equals(pos)
                && this.cc_isChunkTracked(player, pos.getX(), pos.getY(), pos.getZ());
    }

    @Unique
    private long cc_currentLightEpoch() {
        return cc_worldLights != null ? cc_worldLights.epoch() : this.cc_cubeLightManager.epoch();
    }

    @Unique
    private static boolean cc_validObservedLightEpoch(long observed, long current) {
        return observed >= 0 && observed <= current;
    }

    @Unique
    private boolean cc_isCurrentLightRequester(ServerPlayer player) {
        return ((CanBeCubic) this.level).cc_isCubic() && player.level() == this.level && this.level.players().contains(player);
    }

    @Override public CompletableFuture<CubeLightUpdate> cc_rebuildCubeLighting(CubeLightBounds bounds) {
        if (this.cc_lightingClosed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Cube lighting is closed"));
        }
        if (!this.mainThreadExecutor.isSameThread()) {
            return CompletableFuture.supplyAsync(() -> this.cc_rebuildCubeLighting(bounds), this.mainThreadExecutor).thenCompose(future -> future);
        }
        if (cc_worldLights != null) {
            return CompletableFuture
                    .failedFuture(new IllegalStateException("Automatic world lighting is active; bounded rebuilds cannot replace it"));
        }
        synchronized (this) {
            if (this.cc_lightingClosed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Cube lighting is closed"));
            }
            if (this.cc_cubeLightManager == null) {
                this.cc_cubeLightManager = new CubeLightManager(this::cc_findLightingSource, this.mainThreadExecutor::isSameThread,
                        Util.backgroundExecutor(), this.mainThreadExecutor, this::cc_onCubeLightPublicationChanged);
            }
            return this.cc_cubeLightManager.rebuild(bounds);
        }
    }

    @Override public @Nullable CubeLightView cc_getCubeLightView(CubePos pos) {
        if (!cc_lightingClosed && mainThreadExecutor.isSameThread() && cc_worldLights != null) {
            return cc_worldLights.view(pos);
        }
        if (this.cc_lightingClosed || !this.mainThreadExecutor.isSameThread() || this.cc_cubeLightManager == null) {
            return null;
        }
        return this.cc_cubeLightManager.view(pos);
    }

    @Override public CubeLightPacketSnapshot cc_captureCubeLight(LevelCube cube) {
        if (!cc_lightingClosed && mainThreadExecutor.isSameThread() && cc_worldLights != null) {
            return cc_worldLights.capture(cube);
        }
        if (this.cc_lightingClosed || !this.mainThreadExecutor.isSameThread() || this.cc_cubeLightManager == null) {
            return new CubeLightPacketSnapshot(0, null);
        }
        return this.cc_cubeLightManager.capture(cube);
    }

    @Override public void cc_onCubeBlockDataChanged(CubeAccess cube) {
        this.cc_validateCubeLighting();
        if (!cc_lightingClosed && mainThreadExecutor.isSameThread() && cc_worldLights != null) {
            cc_worldLights.changed(cube);
        }
    }

    @Unique
    private void cc_validateCubeLighting() {
        if (!this.cc_lightingClosed && this.mainThreadExecutor.isSameThread() && this.cc_cubeLightManager != null) {
            this.cc_cubeLightManager.validate();
        }
    }

    /** Revoke a publication before sending any replacement block/light snapshots for the same epoch. */
    @Unique
    private void cc_onCubeLightPublicationChanged(long epoch, Set<CubePos> affected) {
        if (this.cc_lightingClosed || !this.mainThreadExecutor.isSameThread() || affected.isEmpty() || this.level.players().isEmpty()) {
            return;
        }
        Map<CubePos, List<ServerPlayer>> tracking = new HashMap<>();
        Set<ServerPlayer> recipients = new HashSet<>();
        for (CubePos pos : affected) {
            List<ServerPlayer> players = this.cc_getPlayers(pos, false);
            tracking.put(pos, players);
            recipients.addAll(players);
        }
        for (ServerPlayer player : recipients) {
            Platform.get().send(player, new CCClientboundCubeLightInvalidatePacket(epoch));
        }
        for (Map.Entry<CubePos, List<ServerPlayer>> entry : tracking.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                this.cc_sendPublishedCubeLight(epoch, entry.getKey(), entry.getValue());
            }
        }
    }

    @Unique
    private void cc_sendPublishedCubeLight(long epoch, CubePos pos, List<ServerPlayer> players) {
        CubeLightManager.Source source = this.cc_findLightingSource(pos);
        if (source == null || !(source.cube() instanceof LevelCube cube)) {
            return;
        }
        CubeLightPacketSnapshot snapshot = this.cc_captureCubeLight(cube);
        if (snapshot.epoch() == epoch && snapshot.light() != null) {
            CCClientboundLevelCubeWithLightPacket packet = new CCClientboundLevelCubeWithLightPacket(cube, snapshot);
            for (ServerPlayer player : players) {
                io.github.opencubicchunks.cubicchunks.server.network.CubeEntityTrackingSender.sendFullCube(player, level, pos, packet, false);
            }
        }
    }

    @Unique
    private void cc_sendWorldLight(CubePos pos, long epoch) {
        List<ServerPlayer> players = cc_getPlayers(pos, false);
        for (ServerPlayer player : players) {
            Platform.get().send(player, new io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeLightRevokePacket(pos, epoch));
        }
        if (!players.isEmpty()) {
            cc_sendPublishedCubeLight(epoch, pos, players);
        }
    }

    @Override public CompletableFuture<CubeLightQuery> cc_queryCubeLight(LightLayer layer, BlockPos pos) {
        try {
            Objects.requireNonNull(layer, "layer");
            BlockPos immutable = Objects.requireNonNull(pos, "pos").immutable();
            if (this.cc_lightingClosed) {
                return CompletableFuture.completedFuture(CubeLightQuery.unavailable());
            }
            if (!this.mainThreadExecutor.isSameThread()) {
                return CompletableFuture.supplyAsync(() -> this.cc_queryCubeLight(layer, immutable), this.mainThreadExecutor)
                        .thenCompose(future -> future);
            }
            if (cc_worldLights != null) {
                CubeLightView view = cc_worldLights.view(CubePos.from(immutable));
                return CompletableFuture.completedFuture(view == null ? CubeLightQuery.unavailable()
                        : new CubeLightQuery(CubeLightQuery.State.CURRENT, view.sample(layer, immutable)));
            }
            return CompletableFuture.completedFuture(
                    this.cc_cubeLightManager == null ? CubeLightQuery.unavailable() : this.cc_cubeLightManager.query(layer, immutable));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** Current lighting uses active loaded cubes; pending unloads and saved files cannot establish live dependencies. */
    @Unique
    private @Nullable CubeLightManager.Source cc_findLightingSource(CubePos pos) {
        ChunkHolder holder = this.updatingChunkMap.get(CloPos.cube(pos).asLong());
        if (holder == null) {
            return null;
        }
        CloAccess latest = ((CloHolder) holder).cc_getLatestClo();
        if (latest instanceof ImposterProtoCube imposter) {
            latest = imposter.cc_getWrappedClo();
        }
        if (!(latest instanceof CubeAccess cube) || cube.getPersistedStatus() != ChunkStatus.FULL) {
            return null;
        }
        if (!cube.cc_getCubePos().equals(pos) || !((CloHolder) holder).cc_getCloPos().equals(CloPos.cube(pos))) {
            throw new IllegalStateException("Cube holder contains a different lighting source: " + pos);
        }
        return new CubeLightManager.Source(holder, cube);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void cc_closeBoundedLighting(CallbackInfo ci) {
        this.cc_closeCubeLighting();
    }

    @Unique
    private void cc_closeCubeLighting() {
        synchronized (this) {
            this.cc_lightingClosed = true;
            if (this.cc_cubeLightManager != null) {
                this.cc_cubeLightManager.close();
            }
            if (cc_worldLights != null) {
                cc_worldLights.close();
            }
        }
    }

    @Shadow private boolean save(ChunkAccess chunk) {
        throw new AssertionError();
    }

    @Shadow private void scheduleUnload(long pos, ChunkHolder holder) {
        throw new AssertionError();
    }

    @Shadow private CompletableFuture<ChunkAccess> scheduleChunkLoad(ChunkPos pos) {
        throw new AssertionError();
    }

    @Shadow private CompletableFuture<Optional<CompoundTag>> readChunk(ChunkPos pos) {
        throw new AssertionError();
    }

    @Shadow private boolean isExistingChunkFull(ChunkPos pos) {
        throw new AssertionError();
    }

    @Unique
    private void cc_awaitCubeSaves() {
        try {
            if (cc_cubeSaves != null) {
                cc_cubeSaves.awaitAll();
            }
        } finally {
            cc_checkCubeSaveFailure();
        }
    }

    @Unique
    private void cc_checkCubeSaveFailure() {
        try {
            if (cc_cubeSaves != null) {
                cc_cubeSaves.poll();
            }
        } catch (RuntimeException failure) {
            cc_cubeSaveFailure = failure;
            cc_recordCubeSaveFailure(failure);
        }
        if (this.cc_cubeSaveFailure != null) {
            throw this.cc_cubeSaveFailure;
        }
    }

    /**
     * Captures live FULL cubes on their owning thread, then reads committed files in storage order.
     * The result describes that request snapshot; a later save/unload cannot erase its live overlay.
     */
    @Override public CompletableFuture<ColumnSurfaceResult> cc_queryColumnSurface(Heightmap.Types type, int blockX, int blockZ, int minCubeY, int maxCubeY) {
        if (!this.mainThreadExecutor.isSameThread()) {
            return CompletableFuture.supplyAsync(() -> this.cc_queryColumnSurface(type, blockX, blockZ, minCubeY, maxCubeY), this.mainThreadExecutor)
                    .thenCompose(future -> future);
        }
        try {
            int cubeX = Coords.blockToCube(blockX);
            int cubeZ = Coords.blockToCube(blockZ);
            ColumnSurfaceIndex live = new ColumnSurfaceIndex(cubeX, cubeZ);
            live.query(type, blockX, blockZ, minCubeY, maxCubeY); // Validate before scheduling I/O or capturing block data.
            this.cc_captureSurfaceSources(live, this.pendingUnloads.values(), cubeX, cubeZ, minCubeY, maxCubeY);
            this.cc_captureSurfaceSources(live, this.updatingChunkMap.values(), cubeX, cubeZ, minCubeY, maxCubeY);
            PalettedContainerFactory palettes = this.level.palettedContainerFactory();
            return this.cc_readCubeColumn(cubeX, cubeZ, minCubeY, maxCubeY).thenApplyAsync(tags -> {
                ColumnSurfaceIndex combined = new ColumnSurfaceIndex(cubeX, cubeZ);
                tags.forEach((cubeY, tag) -> {
                    CubePos pos = CubePos.of(cubeX, cubeY, cubeZ);
                    SerializableCubeData data = SerializableCubeData.parse(tag, pos);
                    combined.put(pos, data.chunkStatus(), data.chunkStatus() == ChunkStatus.FULL ? data.surfaceHeightmaps(palettes) : Map.of());
                });
                combined.overlay(live);
                return combined.query(type, blockX, blockZ, minCubeY, maxCubeY);
            }, this.mainThreadExecutor);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Unique
    private void cc_captureSurfaceSources(ColumnSurfaceIndex target, Iterable<ChunkHolder> holders, int cubeX, int cubeZ, int minY, int maxY) {
        this.cc_forEachColumnSource(holders, cubeX, cubeZ, minY, maxY, cube -> {
            ChunkStatus status = cube.getPersistedStatus();
            target.put(cube.cc_getCubePos(), status, status == ChunkStatus.FULL ? cube.cc_snapshotHeightmaps() : Map.of());
        });
    }

    /** Captures real block-face occlusion; ordinary heightmap predicates cannot describe skylight boundaries. */
    @Override public CompletableFuture<ColumnSkyLightResult> cc_querySkyLightColumn(int blockX, int blockZ, int minCubeY, int maxCubeY) {
        if (!this.mainThreadExecutor.isSameThread()) {
            return CompletableFuture.supplyAsync(() -> this.cc_querySkyLightColumn(blockX, blockZ, minCubeY, maxCubeY), this.mainThreadExecutor)
                    .thenCompose(future -> future);
        }
        try {
            int cubeX = Coords.blockToCube(blockX);
            int cubeZ = Coords.blockToCube(blockZ);
            ColumnSkyLightIndex live = new ColumnSkyLightIndex(cubeX, cubeZ);
            live.query(blockX, blockZ, minCubeY, maxCubeY);
            this.cc_captureSkyLightSources(live, this.pendingUnloads.values(), cubeX, cubeZ, minCubeY, maxCubeY);
            this.cc_captureSkyLightSources(live, this.updatingChunkMap.values(), cubeX, cubeZ, minCubeY, maxCubeY);
            PalettedContainerFactory palettes = this.level.palettedContainerFactory();
            return this.cc_readCubeColumn(cubeX, cubeZ, minCubeY, maxCubeY).thenApplyAsync(tags -> {
                ColumnSkyLightIndex combined = new ColumnSkyLightIndex(cubeX, cubeZ);
                tags.forEach((cubeY, tag) -> {
                    CubePos pos = CubePos.of(cubeX, cubeY, cubeZ);
                    SerializableCubeData data = SerializableCubeData.parse(tag, pos);
                    combined.put(pos, data.chunkStatus(), data.chunkStatus() == ChunkStatus.FULL ? data.skyOcclusion(palettes) : null);
                });
                combined.overlay(live);
                return combined.query(blockX, blockZ, minCubeY, maxCubeY);
            }, this.mainThreadExecutor);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Unique
    private void cc_captureSkyLightSources(ColumnSkyLightIndex target, Iterable<ChunkHolder> holders, int cubeX, int cubeZ, int minY, int maxY) {
        this.cc_forEachColumnSource(holders, cubeX, cubeZ, minY, maxY, cube -> {
            ChunkStatus status = cube.getPersistedStatus();
            target.put(cube.cc_getCubePos(), status, status == ChunkStatus.FULL ? cube.cc_snapshotSkyOcclusion() : null);
        });
    }

    @Unique
    private void cc_forEachColumnSource(Iterable<ChunkHolder> holders, int cubeX, int cubeZ, int minY, int maxY, Consumer<CubeAccess> consume) {
        for (ChunkHolder holder : holders) {
            CloHolder cloHolder = (CloHolder) holder;
            CloPos pos = cloHolder.cc_getCloPos();
            if (!pos.isCube() || pos.getX() != cubeX || pos.getZ() != cubeZ || pos.getY() < minY || pos.getY() > maxY) {
                continue;
            }
            CloAccess latest = cloHolder.cc_getLatestClo();
            if (latest instanceof CubeAccess cube) {
                if (!cube.cc_getCloPos().equals(pos)) {
                    throw new IllegalStateException("Cube holder contains a different column source: " + pos);
                }
                consume.accept(cube);
            }
        }
    }

    @Shadow @Final private static CompletableFuture<ChunkResult<List<CloAccess>>> UNLOADED_CHUNK_LIST_FUTURE;
    @Shadow @Final private static ChunkResult<List<CloAccess>> UNLOADED_CHUNK_LIST_RESULT;

    @Shadow private static double euclideanDistanceSquared(ChunkPos chunkPos, Vec3 pos) {
        throw new IllegalStateException();
    }

    @AddFieldToSets(containers = ChunkToCloSet.ChunkMap_redirects.class, field = "chunkStatusListener:Lnet/minecraft/world/level/entity/ChunkStatusUpdateListener;")
    private CloStatusUpdateListener cc_cloStatusListener;

    // TODO once we can target non-return locations in constructors, do this when the vanilla field is set
    @Inject(method = "<init>", at = @At("RETURN"))
    private void cc_onInit(
            ServerLevel level, LevelStorageSource.LevelStorageAccess levelStorageAccess, DataFixer fixerUpper,
            StructureTemplateManager structureManager, Executor dispatcher, BlockableEventLoop mainThreadExecutor, LightChunkGetter lightChunk,
            ChunkGenerator generator, ChunkStatusUpdateListener chunkStatusListener, TicketStorage ticketStorage, int serverViewDistance,
            boolean sync, CallbackInfo ci
    ) {
        if (((CanBeCubic) level).cc_isCubic()) {
            // Loading progress is queried through CubeLoadStatusView in 26.3, rather than pushed to a constructor listener.
            cc_cloStatusListener = (cloPos, fullChunkStatus) -> {
                if (cloPos.isCube()) {
                    ((CubicServerLevel) level).cc_onCubeEntityStatus(cloPos.cubePos(), fullChunkStatus);
                }
            };
            ((MarkableAsCubic) distanceManager).cc_setCubic();
        }
    }

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("setChunkUnsaved(Lnet/minecraft/world/level/ChunkPos;)V")
    private native void cc_setCloUnsaved(CloPos cloPos);

    /**
     * Returns the squared distance to the center of the cube.
     */
    @AddMethodToSets(containers = ChunkToCloSet.ChunkMap_redirects.class, method = "euclideanDistanceSquared(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/phys/Vec3;)D")
    private static double cc_euclideanDistanceSquared(CloPos cloPos, Vec3 vec3) {
        if (cloPos.isChunk()) {
            // FIXME we shouldn't be getting euclidean distance for chunks, as this doesn't make sense in context
            return euclideanDistanceSquared(cloPos.chunkPos(), vec3);
//            throw new UnsupportedOperationException("Should not call euclideanDistanceSquared with a chunk position");
        }
        double cubeCenterX = Coords.cubeToCenterBlock(cloPos.getX());
        double cubeCenterY = Coords.cubeToCenterBlock(cloPos.getY());
        double cubeCenterZ = Coords.cubeToCenterBlock(cloPos.getZ());
        double dx = cubeCenterX - vec3.x();
        double dy = cubeCenterY - vec3.y();
        double dz = cubeCenterZ - vec3.z();
        return dx * dx + dy * dy + dz * dz;
    }

    // TODO make vanilla isChunkTracked/isChunkOnTrackedBorder fail in cubic world

    // These methods are not copied due to taking 3 ints instead of 2
    @Override public boolean cc_isChunkTracked(ServerPlayer player, int x, int y, int z) {
        return ((CCServerPlayer) player).cc_getCloTrackingView().cc_contains(x, y, z)
                // TODO this requires PlayerChunkSender to accept Clo longs
                && !player.connection.chunkSender.isPending(CloPos.cubeAsLong(x, y, z));
    }

    /** Pair stationary entities only after their cube packet, and unpair them when that cube leaves this player's view. */
    @Override public void cc_onCubeTrackingChange(ServerPlayer player, CubePos pos, boolean sent) {
        for (ChunkMap.TrackedEntity tracked : List.copyOf(entityMap.values())) {
            if (CubePos.from(((TrackedEntityAccess) tracked).cc_entity().blockPosition()).equals(pos)) {
                if (sent) {
                    tracked.updatePlayer(player);
                } else {
                    tracked.removePlayer(player);
                }
            }
        }
    }

    private boolean cc_isChunkOnTrackedBorder(ServerPlayer player, int x, int y, int z) {
        if (this.cc_isChunkTracked(player, x, y, z)) {
            for (int dx = -1; dx <= 1; ++dx) {
                for (int dz = -1; dz <= 1; ++dz) {
                    for (int dy = -1; dy <= 1; ++dy) {
                        if ((dx != 0 || dz != 0 || dy != 0) && !this.cc_isChunkTracked(player, x + dx, y + dy, z + dz)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("getChunkDebugData(Lnet/minecraft/world/level/ChunkPos;)Ljava/lang/String;")
    public native String cc_getChunkDebugData(CloPos pos);

    // region [cc_getChunkRangeFuture dasm + mixin]
    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("getChunkRangeFuture(Lnet/minecraft/server/level/ChunkHolder;ILjava/util/function/IntFunction;)Ljava/util/concurrent/CompletableFuture;")
    private native CompletableFuture<ChunkResult<List<CloAccess>>> cc_getChunkRangeFuture(
            ChunkHolder cloHolder, int radius, IntFunction<ChunkStatus> statusByRadius
    );

    // TODO this could be substantially improved probably hopefully

    /**
     * Cubes require different adjacency logic compared to Chunks
     */
    @SuppressWarnings("checkstyle:CyclomaticComplexity") // <-- TODO this method is just a bit of a disaster
    @Dynamic @Inject(method = "cc_getChunkRangeFuture", at = @At("HEAD"), cancellable = true)
    private void cc_onGetChunkRangeFuture(
            ChunkHolder cloHolder, int radius, IntFunction<ChunkStatus> statusByRadius,
            CallbackInfoReturnable<CompletableFuture<ChunkResult<List<CloAccess>>>> cir
    ) {
        // Note that statusByRadius sometimes isn't actually correct for cubes beyond the first few steps, but getChunkRangeFuture is only called with
        // parameters for which it's correct within the radius
        CloPos pos = ((CloHolder) cloHolder).cc_getCloPos();
        if (!pos.isCube()) {
            return;
        }
        // The vanilla method has an early exit for radius=0 here; this is not valid for cubes because even if radius=0 we still depend on chunks that
        // neighbor the cube
        int cubeDiameter = radius * 2 + 1;
        int chunkDiameter = cubeDiameter * CubicConstants.DIAMETER_IN_SECTIONS;
        int futureCount = cubeDiameter * cubeDiameter * cubeDiameter + chunkDiameter * chunkDiameter;
        List<CompletableFuture<ChunkResult<CloAccess>>> futures = new ArrayList<>(futureCount);
        int middleCubeIndex = -1;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                // We want the chunks intersecting this column of cubes to be loaded at the maximum level of any of those cubes;
                // this occurs when dy=0, so we only consider x/z distance
                int chunkDistance = Math.max(Math.abs(dz), Math.abs(dx));
                for (int sectionZ = 0; sectionZ < CubicConstants.DIAMETER_IN_SECTIONS; sectionZ++) {
                    for (int sectionX = 0; sectionX < CubicConstants.DIAMETER_IN_SECTIONS; sectionX++) {
                        ChunkHolder holder = this.getUpdatingChunkIfPresent(
                                CloPos.chunkAsLong(Coords.cubeToSection(pos.getX() + dx, sectionX), Coords.cubeToSection(pos.getZ() + dz, sectionZ)));
                        if (holder == null) {
                            cir.setReturnValue(UNLOADED_CHUNK_LIST_FUTURE);
                            return;
                        }
                        ChunkStatus expectedStatus = statusByRadius.apply(chunkDistance);
                        futures.add((CompletableFuture<ChunkResult<CloAccess>>) (Object) holder.scheduleChunkGenerationTask(expectedStatus,
                                (ChunkMap) (Object) this));
                    }
                }
                for (int dy = -radius; dy <= radius; dy++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        middleCubeIndex = futures.size();
                    }
                    ChunkHolder holder = this.getUpdatingChunkIfPresent(CloPos.cubeAsLong(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz));
                    if (holder == null) {
                        cir.setReturnValue(UNLOADED_CHUNK_LIST_FUTURE);
                        return;
                    }
                    ChunkStatus expectedStatus = statusByRadius.apply(Math.max(chunkDistance, Math.abs(dy)));
                    futures.add((CompletableFuture<ChunkResult<CloAccess>>) (Object) holder.scheduleChunkGenerationTask(expectedStatus,
                            (ChunkMap) (Object) this));
                }
            }
        }

        // Vanilla expects that the center chunk is in the middle of the list; this is not the case for cubes, so we manually swap the center cube to
        // the middle
        // - this is a """temporary""" approach, that we may or may not actually fix later.
        Collections.swap(futures, middleCubeIndex, futures.size() / 2);

        cir.setReturnValue(Util.sequence(futures).thenApply(resultList -> {
            List<CloAccess> outputList = new ArrayList<>(resultList.size());

            for (final ChunkResult<CloAccess> chunkResult : resultList) {
                if (chunkResult == null) {
                    throw this.debugFuturesAndCreateReportedException(new IllegalStateException("At least one of the chunk futures were null"),
                            "n/a");
                }

                CloAccess cloAccess = chunkResult.orElse(null);
                if (cloAccess == null) {
                    return UNLOADED_CHUNK_LIST_RESULT;
                }

                outputList.add(cloAccess);
            }

            return ChunkResult.of(outputList);
        }));
    }
    // endregion

    // region [cc_updateCubeScheduling dasm + mixin]
    @AddTransformToSets(ChunkToCubeSet.ChunkMap_redirects.class)
    @TransformFromMethod(useRedirectSets = ChunkToCubeSet.class, value = "updateChunkScheduling(JILnet/minecraft/server/level/ChunkHolder;I)Lnet/minecraft/server/level/ChunkHolder;")
    public native @Nullable ChunkHolder cc_updateCubeScheduling(long cubePos, int newLevel, @Nullable ChunkHolder holder, int oldLevel);

    @Inject(method = "updateChunkScheduling", at = @At("HEAD"), cancellable = true)
    private void cc_onUpdateChunkScheduling(long cloPos, int newLevel, ChunkHolder holder, int oldLevel, CallbackInfoReturnable<ChunkHolder> cir) {
        if (((CanBeCubic) level).cc_isCubic() && CloPos.isCube(cloPos)) {
            cir.setReturnValue(cc_updateCubeScheduling(cloPos, newLevel, holder, oldLevel));
        }
    }
    // endregion

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("onLevelChange(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/IntSupplier;ILjava/util/function/IntConsumer;)V")
    private native void cc_onLevelChange(CloPos cloPos, IntSupplier intsupplier, int i, IntConsumer intconsumer);

    @AddMethodToSets(containers = ChunkToCubeSet.ChunkMap_redirects.class, method = "onLevelChange(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/IntSupplier;ILjava/util/function/IntConsumer;)V")
    public void cc_onCubeLevelChange(CubePos cubePos, IntSupplier queueLevelGetter, int ticketLevel, IntConsumer queueLevelSetter) {
        cc_onLevelChange(CloPos.cube(cubePos), queueLevelGetter, ticketLevel, queueLevelSetter);
    }

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("saveAllChunks(Z)V")
    public native void cc_saveAllChunks(boolean flush);

    @Inject(method = "saveAllChunks", at = @At("HEAD"), cancellable = true)
    private void cc_onSaveAllChunks(boolean flush, CallbackInfo ci) {
        this.cc_checkCubeSaveFailure();
        if (((CanBeCubic) level).cc_isCubic()) {
            cc_flushingSaves = flush;
            cc_saveSnapshotsRemaining = flush ? Integer.MAX_VALUE : CC_SAVE_SNAPSHOTS_PER_PASS;
            try {
                cc_saveAllChunks(flush);
                if (flush) {
                    cc_awaitCubeSaves();
                }
            } finally {
                cc_flushingSaves = false;
                cc_saveSnapshotsRemaining = Integer.MAX_VALUE;
                cc_checkCubeSaveFailure();
            }
            ci.cancel();
        }
    }

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("saveChunksEagerly(Ljava/util/function/BooleanSupplier;)V")
    private native void cc_saveClosEagerly(BooleanSupplier hasMoreTime);

    @AddMethodToSets(containers = ChunkToCloSet.ChunkMap_redirects.class, method = "scheduleUnload(JLnet/minecraft/server/level/ChunkHolder;)V")
    private void cc_scheduleUnload(long chunkPos, ChunkHolder chunkHolder) {
        if (!((CloHolder) chunkHolder).cc_getCloPos().isCube()) {
            this.scheduleUnload(chunkPos, chunkHolder);
            return;
        }
        CompletableFuture<?> ready = chunkHolder.getSaveSyncFuture();
        ready.thenRunAsync(() -> {
            if (chunkHolder.getSaveSyncFuture() != ready) {
                this.cc_scheduleUnload(chunkPos, chunkHolder);
                return;
            }
            if (this.pendingUnloads.get(chunkPos) != chunkHolder) {
                return;
            }
            if (!cc_finishCubeUnload(chunkHolder)) {
                cc_retryUnloads.put(chunkPos, chunkHolder);
                return;
            }
            this.pendingUnloads.remove(chunkPos, chunkHolder);
            this.nextChunkSaveTime.remove(chunkPos);
            // Cubic lighting and entity tracking have their own unfinished lifecycle; never clear a vanilla column here.
        }, this.unloadQueue::add).whenComplete((ignored, failure) -> {
            if (failure != null) {
                this.cc_cubeSaveFailure = new CompletionException("Failed to unload cube " + ((CloHolder) chunkHolder).cc_getCloPos(), failure);
                this.cc_recordCubeSaveFailure(this.cc_cubeSaveFailure);
            }
        });
    }

    @Unique
    private boolean cc_finishCubeUnload(ChunkHolder holder) {
        if (cc_saveSnapshotsRemaining == 0) {
            return false;
        }
        CloAccess latest = ((CloHolder) holder).cc_getLatestClo();
        if (latest == null) {
            return true;
        }
        cc_save(latest);
        CubeAccess cube = latest instanceof ImposterProtoCube imposter ? (CubeAccess) imposter.cc_getWrappedClo() : (CubeAccess) latest;
        CubePos pos = cube.cc_getCubePos();
        if (cube.isUnsaved() || cc_saves().contains(pos)) {
            return false;
        }
        var poi = CubePoiLifecycle.storage(level);
        if (poi != null && !poi.isSaved(pos)) {
            return false;
        }
        if (!cc_releaseFullCube(cube)) {
            return false;
        }
        if (poi != null) {
            poi.tryUnload(pos);
        }
        return true;
    }

    @Unique
    private boolean cc_releaseFullCube(CubeAccess cube) {
        if (cube instanceof LevelCube full) {
            if (!((CubicServerLevel) level).cc_tryUnloadCubeEntities(cube.cc_getCubePos())) {
                return false;
            }
            full.clearAllBlockEntities();
            full.unregisterTickContainerFromLevel(level);
            full.setLoaded(false);
        }
        return true;
    }

    // region [cc_scheduleChunkLoad dasm + mixin]
    @AddMethodToSets(containers = ChunkToCloSet.ChunkMap_redirects.class, method = "scheduleChunkLoad(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;")
    private CompletableFuture<CloAccess> cc_scheduleChunkLoad(CloPos cloPos) {
        if (cloPos.isChunk()) {
            return this.scheduleChunkLoad(cloPos.chunkPos()).thenApply(chunk -> (CloAccess) chunk);
        }
        return this.cc_readChunk(cloPos)
                .thenApplyAsync(tag -> tag.map(value -> SerializableCubeData.parse(value, cloPos.cubePos())), Util.backgroundExecutor())
                .<CloAccess>thenApplyAsync(data -> {
                    if (data.isEmpty()) {
                        return this.cc_createEmptyChunk(cloPos);
                    }
                    CubeAccess cube = data.get().read(this.level);
                    var poi = CubePoiLifecycle.storage(this.level);
                    if (poi != null) {
                        poi.reconcile(cube);
                    }
                    this.cc_markPosition(cloPos, cube.getPersistedStatus().getChunkType());
                    return cube;
                }, this.mainThreadExecutor);
        // Corrupt, incompatible, or inaccessible files remain failed futures, never fresh replacement cubes.
    }

    @AddMethodToSets(containers = ChunkToCubeSet.ChunkMap_redirects.class, method = "scheduleChunkLoad(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;")
    private CompletableFuture<CloAccess> cc_scheduleChunkLoad(CubePos cubePos) {
        return cc_scheduleChunkLoad(CloPos.cube(cubePos));
    }

    // endregion

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod(value = "createEmptyChunk(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/ChunkAccess;")
    private native CloAccess cc_createEmptyChunk(CloPos cloPos);

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("markPositionReplaceable(Lnet/minecraft/world/level/ChunkPos;)V")
    private native void cc_markPositionReplaceable(CloPos cloPos);

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("markPosition(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/status/ChunkType;)B")
    private native byte cc_markPosition(CloPos cloPos, ChunkType chunkType);

    // region [cc_applyCubeStep dasm + mixin]
    @AddTransformToSets(ChunkToCubeSet.ChunkMap_redirects.class)
    @TransformFromMethod(useRedirectSets = ChunkToCubeSet.class, value = "applyStep(Lnet/minecraft/server/level/GenerationChunkHolder;Lnet/minecraft/world/level/chunk/status/ChunkStep;"
            + "Lnet/minecraft/util/StaticCache2D;)Ljava/util/concurrent/CompletableFuture;")
    public native CompletableFuture<CubeAccess> cc_applyCubeStep(
            GenerationChunkHolder generationchunkholder, CubeStep chunkstep, StaticCache3D<GenerationChunkHolder> cache
    );

    @Dynamic @Redirect(method = "cc_applyCubeStep", at = @At(value = "INVOKE", target = "Lio/github/opencubicchunks/cubicchunks/util/StaticCache3D;get(II)Ljava/lang/Object;"))
    private Object cc_onApplyCubeStep_staticCacheGet(StaticCache3D instance, int x, int z, @Local(ordinal = 0) CubePos cubePos) {
        return instance.get(cubePos.getX(), cubePos.getY(), cubePos.getZ());
    }
    // endregion

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("scheduleGenerationTask(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Lnet/minecraft/world/level/ChunkPos;)"
            + "Lnet/minecraft/server/level/ChunkGenerationTask;")
    public native ChunkGenerationTask cc_scheduleGenerationTask(ChunkStatus chunkstatus, CloPos cloPos);

    @AddMethodToSets(containers = ChunkToCubeSet.ChunkMap_redirects.class, method = "scheduleGenerationTask(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Lnet/minecraft/world/level/ChunkPos;)"
            + "Lnet/minecraft/server/level/ChunkGenerationTask;")
    public ChunkGenerationTask cc_scheduleGenerationTask(ChunkStatus chunkstatus, CubePos cubePos) {
        return cc_scheduleGenerationTask(chunkstatus, CloPos.cube(cubePos));
    }

    // region [cc_runGenerationTask dasm + mixin]
    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("runGenerationTask(Lnet/minecraft/server/level/ChunkGenerationTask;)V")
    private native void cc_runGenerationTask(ChunkGenerationTask chunkgenerationtask);

    // Delegate to the cube method for cubes
    @Inject(method = "runGenerationTask", at = @At("HEAD"), cancellable = true)
    private void cc_onVanillaRunGenerationTask(ChunkGenerationTask task, CallbackInfo ci) {
        if (((CloGenerationTask) task).cc_getCloPos().isCube()) {
            ci.cancel();
            cc_runGenerationTask(task);
        }
    }
    // endregion

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("prepareEntityTickingChunk(Lnet/minecraft/server/level/ChunkHolder;)Ljava/util/concurrent/CompletableFuture;")
    public native CompletableFuture<ChunkResult<LevelClo>> cc_prepareEntityTickingChunk(ChunkHolder holder);

    @Inject(method = "prepareEntityTickingChunk", at = @At("HEAD"), cancellable = true)
    private void cc_onVanillaPrepareEntityTickingChunk(ChunkHolder chunk, CallbackInfoReturnable<CompletableFuture<ChunkResult<LevelClo>>> cir) {
        if (((CloHolder) chunk).cc_getCloPos().isCube()) {
            cir.setReturnValue(cc_prepareEntityTickingChunk(chunk));
        }
    }

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("prepareTickingChunk(Lnet/minecraft/server/level/ChunkHolder;)Ljava/util/concurrent/CompletableFuture;")
    public native CompletableFuture<ChunkResult<LevelClo>> cc_prepareTickingChunk(ChunkHolder holder);

    @Inject(method = "prepareTickingChunk", at = @At("HEAD"), cancellable = true)
    private void cc_onVanillaPrepareTickingChunk(ChunkHolder chunk, CallbackInfoReturnable<CompletableFuture<ChunkResult<LevelClo>>> cir) {
        if (((CloHolder) chunk).cc_getCloPos().isCube()) {
            cir.setReturnValue(cc_prepareTickingChunk(chunk));
        }
    }

    @AddMethodToSets(containers = ChunkToCloSet.ChunkMap_redirects.class, method = "onChunkReadyToSend(Lnet/minecraft/server/level/ChunkHolder;Lnet/minecraft/world/level/chunk/LevelChunk;)V")
    private void cc_onChunkReadyToSend(ChunkHolder holder, LevelClo clo) {
        for (ServerPlayer player : this.playerMap.getAllPlayers()) {
            if (((CCServerPlayer) player).cc_getCloTrackingView().cc_contains(clo.cc_getCloPos())) {
                cc_markChunkPendingToSend(player, clo);
            }
        }
        this.level.getChunkSource().onChunkReadyToSend(holder);
        // Vanilla debug subscriptions use column keys. Cube debug overlays need a separate 3D protocol.
        if (clo instanceof LevelChunk chunk) {
            this.level.debugSynchronizers().registerChunk(chunk);
        }
    }

    @AddMethodToSets(containers = ChunkToCloSet.ChunkMap_redirects.class, method = "prepareAccessibleChunk(Lnet/minecraft/server/level/ChunkHolder;)Ljava/util/concurrent/CompletableFuture;")
    public CompletableFuture<ChunkResult<LevelClo>> cc_prepareAccessibleChunk(ChunkHolder holder) {
        return cc_getChunkRangeFuture(holder, 1,
                ((CloHolder) holder).cc_getCloPos().isCube() ? io.github.opencubicchunks.cubicchunks.server.level.CubeLevel::getStatusAroundFullCube
                        : ChunkLevel::getStatusAroundFullChunk)
                .thenApply(result -> result.map(clos -> (LevelClo) clos.get(clos.size() / 2)));
    }

    @Inject(method = "prepareAccessibleChunk", at = @At("HEAD"), cancellable = true)
    private void cc_onVanillaPrepareAccessibleChunk(ChunkHolder chunk, CallbackInfoReturnable<CompletableFuture<ChunkResult<LevelClo>>> cir) {
        if (((CloHolder) chunk).cc_getCloPos().isCube()) {
            cir.setReturnValue(cc_prepareAccessibleChunk(chunk));
        }
    }

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("saveChunkIfNeeded(Lnet/minecraft/server/level/ChunkHolder;J)Z")
    private native boolean cc_saveChunkIfNeeded(ChunkHolder holder, long gameTime);

    @AddMethodToSets(containers = ChunkToCloSet.ChunkMap_redirects.class, method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z")
    private boolean cc_save(CloAccess cloAccess) {
        if (cloAccess instanceof ChunkAccess chunk) {
            return this.save(chunk);
        }
        this.cc_checkCubeSaveFailure();
        CubeAccess cube = cloAccess instanceof ImposterProtoCube imposter ? (CubeAccess) imposter.cc_getWrappedClo() : (CubeAccess) cloAccess;
        if (cc_saveSnapshotsRemaining == 0) {
            chunksToEagerlySave.add(cube.cc_getCloPos().asLong());
            return false;
        }
        cc_saveSnapshotsRemaining--;
        var poi = CubePoiLifecycle.storage(this.level);
        if (poi != null) {
            poi.reconcile(cube);
            if (cc_flushingSaves) {
                poi.flush(cube.cc_getCubePos());
            } else {
                poi.save(cube.cc_getCubePos());
            }
        }
        if (!cube.isUnsaved()) {
            return false;
        }
        return cc_submitCubeSave(cube);
    }

    @Unique
    private boolean cc_submitCubeSave(CubeAccess cube) {
        CloPos pos = cube.cc_getCloPos();
        try {
            if (cc_flushingSaves) {
                cc_saves().awaitAll();
            }
            if (!cc_saves().canSubmit(cube.cc_getCubePos())) {
                chunksToEagerlySave.add(pos.asLong());
                return false;
            }
            if (!cube.tryMarkSaved()) {
                return false;
            }
            CompoundTag snapshot = SerializableCubeData.copyOf(this.level, cube).write();
            ChunkType type = cube.getPersistedStatus().getChunkType();
            boolean accepted = cc_saves().submit(cube.cc_getCubePos(), snapshot, tag -> cc_writeCubeSnapshot(cube.cc_getCubePos(), tag, type),
                    () -> cc_markPosition(pos, type), cube::markUnsaved);
            if (!accepted) {
                cube.markUnsaved();
                chunksToEagerlySave.add(pos.asLong());
            }
            return accepted;
        } catch (RuntimeException failure) {
            cube.markUnsaved();
            this.cc_cubeSaveFailure = new CompletionException("Failed to save cube " + pos, failure);
            this.cc_recordCubeSaveFailure(this.cc_cubeSaveFailure);
            throw this.cc_cubeSaveFailure;
        }
    }

    @AddMethodToSets(containers = ChunkToCloSet.ChunkMap_redirects.class, method = "isExistingChunkFull(Lnet/minecraft/world/level/ChunkPos;)Z")
    private boolean cc_isExistingChunkFull(CloPos cloPos) {
        if (cloPos.isChunk()) {
            return this.isExistingChunkFull(cloPos.chunkPos());
        }
        return this.cc_readChunk(cloPos).join()
                .map(tag -> SerializableCubeData.parse(tag, cloPos.cubePos()).chunkStatus().getChunkType() == ChunkType.LEVELCHUNK).orElse(false);
    }

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("markChunkPendingToSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;)V")
    private native void cc_markChunkPendingToSend(ServerPlayer player, CloPos cloPos);

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("markChunkPendingToSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/chunk/LevelChunk;)V")
    private static native void cc_markChunkPendingToSend(ServerPlayer player, LevelClo clo);

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("dropChunk(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;)V")
    private static native void cc_dropChunk(ServerPlayer player, CloPos cloPos);

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("getChunkToSend(J)Lnet/minecraft/world/level/chunk/LevelChunk;")
    public native LevelClo cc_getChunkToSend(long cloPos);

    /** Publish initial geometry together with its exact light, so newly loaded cubes never first render black. */
    @Dynamic @Inject(method = "cc_getChunkToSend", at = @At("RETURN"), cancellable = true)
    private void cc_waitForInitialCubeLight(long cloPos, CallbackInfoReturnable<LevelClo> cir) {
        if (cir.getReturnValue() instanceof LevelCube cube && cc_cubeLightManager == null
                && (cc_worldLights == null || cc_worldLights.capture(cube).light() == null)) {
            cir.setReturnValue(null);
        }
    }

    // dumpChunks (low prio)

    // printFuture - only ever called in dumpChunks

    @AddMethodToSets(containers = ChunkToCloSet.ChunkMap_redirects.class, method = "readChunk(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;")
    private CompletableFuture<Optional<CompoundTag>> cc_readChunk(CloPos cloPos) {
        return cloPos.isCube() ? this.cc_read(cloPos) : this.readChunk(cloPos.chunkPos());
    }

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("collectSpawningChunks(Ljava/util/List;)V")
    native void cc_collectSpawningClos(List<LevelClo> list);

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("forEachBlockTickingChunk(Ljava/util/function/Consumer;)V")
    public native void cc_forEachBlockTickingClo(Consumer<LevelClo> consumer);

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("anyPlayerCloseEnoughForSpawning(Lnet/minecraft/world/level/ChunkPos;)Z")
    public native boolean cc_anyPlayerCloseEnoughForSpawning(CloPos cloPos);

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("anyPlayerCloseEnoughForSpawningInternal(Lnet/minecraft/world/level/ChunkPos;)Z")
    private native boolean cc_anyPlayerCloseEnoughForSpawningInternal(CloPos cloPos);

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("getPlayersCloseForSpawning(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/List;")
    public native List<ServerPlayer> cc_getPlayersCloseForSpawning(CloPos cloPos);

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("playerIsCloseEnoughForSpawning(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;)Z")
    private native boolean cc_playerIsCloseEnoughForSpawning(ServerPlayer player, CloPos cloPos);

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("updatePlayerStatus(Lnet/minecraft/server/level/ServerPlayer;Z)V")
    public native void cc_updatePlayerStatus(ServerPlayer player, boolean track);

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("move(Lnet/minecraft/server/level/ServerPlayer;)V")
    public native void cc_move(ServerPlayer player);

    @Dynamic @Redirect(method = "cc_move", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;asLong()J"))
    private long cc_compareFullCubePosition(SectionPos section) {
        return CubePos.of(Coords.sectionToCube(section.x()), Coords.sectionToCube(section.y()), Coords.sectionToCube(section.z())).asLong();
    }

    // region [cc_updateChunkTracking dasm + mixin]
    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("updateChunkTracking(Lnet/minecraft/server/level/ServerPlayer;)V")
    private native void cc_updateChunkTracking(ServerPlayer player);

    @Dynamic @WrapOperation(method = "cc_updateChunkTracking", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;getPlayerViewDistance(Lnet/minecraft/server/level/ServerPlayer;)I"))
    private int cc_onUpdateChunkTracking_getViewDistance(ChunkMap instance, ServerPlayer player, Operation<Integer> original) {
        return Coords.sectionToCubeRenderDistance(original.call(instance, player));
    }
    // endregion

    // region [cc_applyChunkTrackingView dasm + mixin]
    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("applyChunkTrackingView(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/level/ChunkTrackingView;)V")
    private native void cc_applyChunkTrackingView(ServerPlayer player, CloTrackingView chunkTrackingView);

    @Dynamic @Redirect(method = "cc_applyChunkTrackingView", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void cc_onApplyChunkTrackingView_setChunkCacheCenterPacket(
            ServerGamePacketListenerImpl instance, Packet packet, ServerPlayer player, CloTrackingView cloTrackingView
    ) {
        Platform.get().send(player,
                new CCClientboundSetCubeCacheCenterPacket(((CloTrackingView.Positioned) cloTrackingView).center().cubePos()));
    }
    // endregion

    // region [cc_getPlayers dasm + mixin]
    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("getPlayers(Lnet/minecraft/world/level/ChunkPos;Z)Ljava/util/List;")
    public native List<ServerPlayer> cc_getPlayers(CloPos pos, boolean boundaryOnly);

    @Dynamic @Redirect(method = "cc_getPlayers", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;isChunkOnTrackedBorder(Lnet/minecraft/server/level/ServerPlayer;II)Z"))
    private boolean cc_getPlayers_isChunkOnTrackedBorder(ChunkMap instance, ServerPlayer player, int x, int z, @Local CloPos pos) {
        return this.cc_isChunkOnTrackedBorder(player, pos.getX(), pos.getY(), pos.getZ());
    }

    @Dynamic @Redirect(method = "cc_getPlayers", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;isChunkTracked(Lnet/minecraft/server/level/ServerPlayer;II)Z"))
    private boolean cc_getPlayers_isChunkTracked(ChunkMap instance, ServerPlayer player, int x, int z, @Local CloPos pos) {
        return this.cc_isChunkTracked(player, pos.getX(), pos.getY(), pos.getZ());
    }
    // endregion

    @AddMethodToSets(containers = ChunkToCubeSet.ChunkMap_redirects.class, method = "getPlayers(Lnet/minecraft/world/level/ChunkPos;Z)Ljava/util/List;")
    @Override public List<ServerPlayer> cc_getPlayers(CubePos pos, boolean boundaryOnly) {
        return cc_getPlayers(CloPos.cube(pos), boundaryOnly);
    }

    // Replace `SectionPos.chunk()` with `SectionPos.cc_cube()` unconditionally here
    @AddTransformToSets(GlobalSet.ChunkMap_redirects.class)
    @TransformFromMethod(value = "tick(Ljava/util/function/BooleanSupplier;)V", useRedirectSets = { ChunkToCloSet.class, SectionPosToCubeSet.class })
    protected native void cc_tick(BooleanSupplier hasMoreTime);

    @AddTransformToSets(GlobalSet.ChunkMap_redirects.class)
    @TransformFromMethod(value = "tick()V", useRedirectSets = { ChunkToCloSet.class, SectionPosToCubeSet.class })
    public native void cc_tick();

    @Dynamic @Inject(method = "cc_tick()V", at = @At("RETURN"))
    private void cc_validateCubeLightingAfterTick(CallbackInfo ci) {
        this.cc_checkCubeSaveFailure();
        this.cc_tickCubeLighting();
    }

    @Unique
    private void cc_tickCubeLighting() {
        if (!this.cc_lightingClosed && this.mainThreadExecutor.isSameThread()) {
            this.cc_lightTick++;
            if (this.cc_cubeLightManager != null) {
                this.cc_cubeLightManager.tick();
            } else if (!level.players().isEmpty()) {
                cc_tickWorldLighting();
            }
        }
    }

    @Unique
    private void cc_tickWorldLighting() {
        if (cc_worldLights == null) {
            var storage = new io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightStorage(level.getSeed(),
                    level.palettedContainerFactory(), pos -> cc_read(CloPos.cube(pos)),
                    pos -> cc_readCubeColumn(pos.getX(), pos.getZ(), -CubePos.MAX_COORDINATE_VALUE - 1, CubePos.MAX_COORDINATE_VALUE),
                    Util.backgroundExecutor(), ((CubicServerLevel) level).cc_nativeBiomeTerrain());
            cc_worldLights = new io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightManager(level.getSeed(),
                    level.dimensionType().hasSkyLight(), Util.backgroundExecutor(), storage, this::cc_sendWorldLight);
        }
        Map<CubePos, CubeAccess> sources = new LinkedHashMap<>();
        cc_collectWorldLightSources(pendingUnloads.values(), sources);
        cc_collectWorldLightSources(updatingChunkMap.values(), sources);
        List<CubePos> targets = new ArrayList<>();
        var distances = new it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap<CubePos>();
        sources.forEach((pos, cube) -> {
            if (cube instanceof LevelCube && cc_findLightingSource(pos) != null) {
                double distance = cc_lightDistance(pos);
                if (Double.isFinite(distance)) {
                    distances.put(pos, distance);
                    targets.add(pos);
                }
            }
        });
        targets.sort(java.util.Comparator.comparingDouble(distances::getDouble));
        cc_worldLights.tick(sources, targets);
    }

    @Unique
    private double cc_lightDistance(CubePos pos) {
        double nearest = Double.POSITIVE_INFINITY;
        for (ServerPlayer player : level.players()) {
            if (((CCServerPlayer) player).cc_getCloTrackingView().cc_contains(pos.getX(), pos.getY(), pos.getZ())) {
                nearest = Math.min(nearest, player.distanceToSqr(Coords.cubeToCenterBlock(pos.getX()), Coords.cubeToCenterBlock(pos.getY()),
                        Coords.cubeToCenterBlock(pos.getZ())));
            }
        }
        return nearest;
    }

    @Unique
    private void cc_collectWorldLightSources(Iterable<ChunkHolder> holders, Map<CubePos, CubeAccess> target) {
        for (ChunkHolder holder : holders) {
            CloAccess latest = ((CloHolder) holder).cc_getLatestClo();
            if (latest instanceof ImposterProtoCube imposter) {
                latest = imposter.cc_getWrappedClo();
            }
            if (latest instanceof CubeAccess cube && cube.getPersistedStatus().isOrAfter(ChunkStatus.TERRAIN)) {
                target.put(cube.cc_getCubePos(), cube);
            }
        }
    }

    @AddTransformToSets(GlobalSet.ChunkMap_redirects.class)
    @TransformFromMethod(value = "processUnloads(Ljava/util/function/BooleanSupplier;)V")
    private native void cc_processUnloads(BooleanSupplier hasMoreTime);

    @Dynamic @Inject(method = "cc_processUnloads", at = @At("HEAD"))
    private void cc_checkUnloadFailure(BooleanSupplier hasMoreTime, CallbackInfo ci) {
        this.cc_checkCubeSaveFailure();
        cc_saveSnapshotsRemaining = cc_flushingSaves ? Integer.MAX_VALUE : CC_SAVE_SNAPSHOTS_PER_PASS;
        var retry = new LinkedHashMap<>(cc_retryUnloads);
        cc_retryUnloads.clear();
        retry.forEach(this::cc_scheduleUnload);
    }

    @Dynamic @Inject(method = "cc_processUnloads", at = @At("RETURN"))
    private void cc_endSavePass(BooleanSupplier hasMoreTime, CallbackInfo ci) {
        cc_saveSnapshotsRemaining = Integer.MAX_VALUE;
    }

    // TODO resendBiomesForChunks - only used for FillBiomeCommand

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("onFullChunkStatusChange(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/server/level/FullChunkStatus;)V")
    public native void cc_onFullChunkStatusChange(CloPos cloPos, FullChunkStatus fullChunkStatus);

    @AddMethodToSets(containers = ChunkToCubeSet.ChunkMap_redirects.class, method = "onFullChunkStatusChange(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/server/level/FullChunkStatus;)V")
    public void cc_onFullChunkStatusChange(CubePos cubePos, FullChunkStatus fullChunkStatus) {
        cc_onFullChunkStatusChange(CloPos.cube(cubePos), fullChunkStatus);
    }

    @AddTransformToSets(ChunkToCloSet.ChunkMap_redirects.class)
    @TransformFromMethod("waitForLightBeforeSending(Lnet/minecraft/world/level/ChunkPos;I)V")
    public native void cc_waitForLightBeforeSending(CloPos cloPos, int radius);
}
