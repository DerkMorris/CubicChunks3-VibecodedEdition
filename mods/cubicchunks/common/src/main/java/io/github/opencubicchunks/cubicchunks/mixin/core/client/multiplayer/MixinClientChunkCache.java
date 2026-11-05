package io.github.opencubicchunks.cubicchunks.mixin.core.client.multiplayer;

import static io.github.opencubicchunks.cc_core.CubicChunksBase.LOGGER;
import static io.github.opencubicchunks.cc_core.utils.Coords.cubeToSection;

import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddFieldToSets;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubicClientLevel;
import io.github.opencubicchunks.cubicchunks.client.renderer.CubicLevelRenderer;
import io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.chunk.MixinChunkSource;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ClientChunkToCubeSet;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeLightRefreshPacket;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.EmptyLevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.ClientCubeLightState;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The vanilla {@link ClientChunkCache} class stores all loaded chunks on the client and has methods to update and unload them, as well as change the
 * center and range of the chunk storage. This mixin adds versions of these methods for cubes, meaning that this class now stores both cubes and
 * chunks.
 */
@Dasm(value = ClientChunkToCubeSet.class, target = @Ref(ClientChunkCache.class))
@Mixin(ClientChunkCache.class)
public abstract class MixinClientChunkCache extends MixinChunkSource implements ClientCubeCache {
    @AddFieldToSets(containers = ClientChunkToCubeSet.ClientChunkCache_redirects.class, field = "storage:Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;")
    volatile ClientCubeCache.Storage cc_cubeStorage;

    private LevelCube cc_emptyCube;

    @Shadow @Final ClientLevel level;

    @Unique
    private final ClientCubeLightState cc_lightState = new ClientCubeLightState(pos -> this.cc_getCube(pos.getX(), pos.getY(), pos.getZ(), false),
            this::cc_lightChanged);
    @Unique
    private boolean cc_applyingFullCubePacket;

    @Override public @Nullable CubeLightView cc_getCubeLightView(CubePos pos) {
        return this.cc_lightState.view(pos);
    }

    @Override public @Nullable CubeLightView cc_getVisibleCubeLightView(CubePos pos) {
        return this.cc_lightState.displayView(pos);
    }

    @Override public long cc_lightGeneration() {
        return this.cc_lightState.generation();
    }

    @Override public boolean cc_applyCubeLight(LevelCube cube, long revision, long epoch, @Nullable CubeLightView light, long generation) {
        return this.cc_lightState.apply(cube, revision, epoch, light, generation);
    }

    @Override public boolean cc_applyScopedCubeLight(LevelCube cube, long revision, long epoch, @Nullable CubeLightView light, long generation) {
        return this.cc_lightState.applyScoped(cube, revision, epoch, light, generation);
    }

    @Override public void cc_invalidateScopedCubeLight(CubePos pos, long epoch) {
        this.cc_lightState.invalidateScoped(pos, epoch);
    }

    @Override public void cc_expectCubeLight(LevelCube cube, long epoch) {
        this.cc_lightState.expect(cube, epoch);
    }

    @Override public void cc_expectScopedCubeLight(LevelCube cube, long epoch) {
        this.cc_lightState.expectScoped(cube, epoch);
    }

    @Override public void cc_invalidateCubeLight(long epoch) {
        this.cc_lightState.invalidate(epoch);
    }

    @Override public boolean cc_isCubeLightReady(CubePos pos) {
        CubeLightView view = this.cc_getCubeLightView(pos);
        return view != null && view.isReady();
    }

    @Override public @Nullable CCServerboundCubeLightRefreshPacket cc_pollCubeLightRefresh() {
        ClientCubeLightState.RefreshRequest request = this.cc_lightState.pollRefresh();
        return request == null ? null : new CCServerboundCubeLightRefreshPacket(request.pos(), request.observedEpoch());
    }

    @Override public void cc_onCubeBlockDataChanged(CubeAccess cube) {
        if (!this.cc_applyingFullCubePacket) {
            this.cc_lightState.invalidateLocalAt(cube.cc_getCubePos());
        }
    }

    @Override public void cc_onCubeLightCellChanged(CubeAccess cube, BlockPos pos, net.minecraft.world.level.block.state.BlockState previous) {
        if (!this.cc_applyingFullCubePacket) {
            this.cc_lightState.changeCell(pos, previous);
        }
    }

    @Override public void cc_onCubeRemoved(LevelCube cube) {
        ((CubicClientLevel) this.level).cc_onCubeUnloaded(cube.cc_getCubePos());
        cube.cc_clearClientBlockEntities();
        ((CubicClientLevel) this.level).cc_forgetCubePredictions(cube.cc_getCubePos());
        this.cc_lightState.remove(cube.cc_getCubePos());
        this.cc_lightChanged(cube.cc_getCubePos());
    }

    @Unique
    private void cc_lightChanged(CubePos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level != this.level) {
            return;
        }
        if (minecraft.levelExtractor != null) {
            int x = cubeToSection(pos.getX(), 0);
            int y = cubeToSection(pos.getY(), 0);
            int z = cubeToSection(pos.getZ(), 0);
            int size = CubicConstants.DIAMETER_IN_SECTIONS;
            minecraft.levelExtractor.setSectionRangeDirty(x - 1, y - 1, z - 1, x + size, y + size, z + size);
        }
        if (minecraft.levelRenderer != null) {
            CubicLevelRenderer renderer = (CubicLevelRenderer) minecraft.levelRenderer;
            if (this.cc_getCube(pos.getX(), pos.getY(), pos.getZ(), false) != null) {
                renderer.cc_onCubeReadyToRender(pos);
            } else {
                renderer.cc_onCubeUnloaded(pos);
            }
        }
    }

    /**
     * Initialize cube storage and the empty cube if the level is cubic
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void cc_onConstruct(ClientLevel level, int viewDistance, CallbackInfo ci) {
        if (((CanBeCubic) level).cc_isCubic()) {
            cc_emptyCube = new EmptyLevelCube(level, CubePos.of(0, 0, 0),
                    level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
            cc_cubeStorage = new ClientCubeCache.Storage(calculateStorageRange(viewDistance), level);
            // TODO we could redirect the initial construction instead of immediately resizing. doesn't really matter
            updateViewRadius(cc_calculateChunkViewDistance(viewDistance));
        }
    }

    private static boolean cc_isValidCube(@Nullable LevelCube chunk, int x, int y, int z) {
        if (chunk == null) {
            return false;
        } else {
            CubePos cubePos = chunk.cc_getCloPos().cubePos();
            return cubePos.getX() == x && cubePos.getY() == y && cubePos.getZ() == z;
        }
    }

    @Override public void cc_drop(CubePos chunkPos) {
        if (this.cc_cubeStorage.inRange(chunkPos.getX(), chunkPos.getY(), chunkPos.getZ())) {
            int i = this.cc_cubeStorage.getIndex(chunkPos.getX(), chunkPos.getY(), chunkPos.getZ());
            LevelCube levelCube = this.cc_cubeStorage.getChunk(i);
            if (cc_isValidCube(levelCube, chunkPos.getX(), chunkPos.getY(), chunkPos.getZ())) {
                // TODO event hook
//                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.level.ChunkEvent.Unload(levelCube));
                this.cc_cubeStorage.drop(i, levelCube);
            }
        }
    }

    @Override public @Nullable LevelCube cc_getCube(int chunkX, int chunkY, int chunkZ, ChunkStatus requiredStatus, boolean load) {
        if (this.cc_cubeStorage.inRange(chunkX, chunkY, chunkZ)) {
            LevelCube levelCube = this.cc_cubeStorage.getChunk(this.cc_cubeStorage.getIndex(chunkX, chunkY, chunkZ));
            if (cc_isValidCube(levelCube, chunkX, chunkY, chunkZ)) {
                return levelCube;
            }
        }

        return load ? this.cc_emptyCube : null;
    }

    @Override public void cc_replaceBiomes(int x, int y, int z, FriendlyByteBuf buffer) {
        if (true) {
            // TODO (P2)
            throw new UnsupportedOperationException("don't remove this exception until packet integration tests are added for this method");
        }
        if (!this.cc_cubeStorage.inRange(x, y, z)) {
            LOGGER.warn("Ignoring cube since it's not in the view range: {}, {}, {}", x, y, z);
        } else {
            int i = this.cc_cubeStorage.getIndex(x, y, z);
            LevelCube levelCube = this.cc_cubeStorage.chunks.get(i);
            if (!cc_isValidCube(levelCube, x, y, z)) {
                LOGGER.warn("Ignoring cube since it's not present: {}, {}, {}", x, y, z);
            } else {
                levelCube.replaceBiomes(buffer);
            }
        }
    }

    @Override public @Nullable LevelCube cc_replaceWithPacketData(
            int x, int y, int z, FriendlyByteBuf buffer, Map<Heightmap.Types, long[]> map,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer
    ) {
        this.cc_lightState.forget(CubePos.of(x, y, z));
        this.cc_applyingFullCubePacket = true;
        try {
            LevelCube installed = this.cc_installFullCubeData(x, y, z, buffer, map, consumer);
            if (installed != null) {
                ((CubicClientLevel) this.level).cc_forgetCubePredictions(installed.cc_getCubePos());
                this.cc_lightChanged(installed.cc_getCubePos());
            }
            return installed;
        } catch (RuntimeException failure) {
            this.cc_lightState.invalidateLocal();
            throw failure;
        } finally {
            this.cc_applyingFullCubePacket = false;
        }
    }

    @Unique
    private @Nullable LevelCube cc_installFullCubeData(
            int x, int y, int z, FriendlyByteBuf buffer, Map<Heightmap.Types, long[]> map,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer
    ) {
        if (!this.cc_cubeStorage.inRange(x, y, z)) {
            LOGGER.warn("Ignoring cube since it's not in the view range: {}, {}, {}", x, y, z);
            return null;
        } else {
            int i = this.cc_cubeStorage.getIndex(x, y, z);
            LevelCube levelCube = this.cc_cubeStorage.chunks.get(i);
            CubePos cubePos = CubePos.of(x, y, z);
            if (!cc_isValidCube(levelCube, x, y, z)) {
                levelCube = new LevelCube(this.level, cubePos);
                levelCube.replaceWithPacketData(buffer, map, consumer);
                this.cc_cubeStorage.replace(i, levelCube);
            } else {
                levelCube.replaceWithPacketData(buffer, map, consumer);
                this.cc_cubeStorage.refreshEmptySections(levelCube);
            }

            ((CubicClientLevel) this.level).cc_onCubeLoaded(cubePos);
            // TODO event hook
//            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.level.ChunkEvent.Load(levelCube, false));
            return levelCube;
        }
    }

    @Shadow public abstract void updateViewCenter(int x, int z);

    @Override public void cc_updateViewCenter(int x, int y, int z) {
        this.cc_cubeStorage.viewCenterX = x;
        this.cc_cubeStorage.viewCenterY = y;
        this.cc_cubeStorage.viewCenterZ = z;
        this.updateViewCenter(cubeToSection(x, 0), cubeToSection(z, 0));
        ((CubicClientLevel) this.level).cc_refreshEntityTicks();
    }

    @Shadow public abstract void updateViewRadius(int viewDistance);

    @Override public void cc_updateViewRadius(int viewDistance) {
        int i = this.cc_cubeStorage.cubeRadius;
        int j = calculateStorageRange(viewDistance);
        if (i != j) {
            ClientCubeCache.Storage storage = new ClientCubeCache.Storage(j, this.level);
            storage.viewCenterX = this.cc_cubeStorage.viewCenterX;
            storage.viewCenterY = this.cc_cubeStorage.viewCenterY;
            storage.viewCenterZ = this.cc_cubeStorage.viewCenterZ;

            for (int k = 0; k < this.cc_cubeStorage.chunks.length(); ++k) {
                LevelCube levelCube = this.cc_cubeStorage.chunks.get(k);
                if (levelCube != null) {
                    CubePos cubePos = levelCube.cc_getCloPos().cubePos();
                    if (storage.inRange(cubePos.getX(), cubePos.getY(), cubePos.getZ())) {
                        storage.replace(storage.getIndex(cubePos.getX(), cubePos.getY(), cubePos.getZ()), levelCube);
                    } else {
                        this.cc_onCubeRemoved(levelCube);
                    }
                }
            }
            this.cc_cubeStorage = storage;
        }
        updateViewRadius(cc_calculateChunkViewDistance(viewDistance));
        ((CubicClientLevel) this.level).cc_refreshEntityTicks();
    }

    @Shadow private static int calculateStorageRange(int viewDistance) {
        throw new IllegalStateException("mixin failed to apply");
    }

    @SuppressWarnings("checkstyle:MagicNumber") // <-- TODO it's unclear what the 3 represents in vanilla?
    private static int cc_calculateChunkViewDistance(int cubeViewDistance) {
        int cubeStorageRange = calculateStorageRange(cubeViewDistance);
        // TODO this radius might be larger than it needs to be? coordinate maths is difficult
        int chunkStorageRange = CubicConstants.DIAMETER_IN_SECTIONS * (cubeStorageRange + 1);
        return chunkStorageRange - 3; // This gives the view distance, which gets passed back into calculateStorageRange which will readd the 3
    }

    // TODO gatherStats (only used for debug)

    @Override public int cc_getLoadedCubeCount() {
        return this.cc_cubeStorage.chunkCount;
    }
}
