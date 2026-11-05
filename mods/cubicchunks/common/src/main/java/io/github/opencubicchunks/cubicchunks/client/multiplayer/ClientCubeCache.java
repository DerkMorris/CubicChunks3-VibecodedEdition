package io.github.opencubicchunks.cubicchunks.client.multiplayer;

import static io.github.notstirred.dasm.api.annotations.transform.Visibility.PUBLIC;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.notstirred.dasm.api.annotations.transform.TransformFromMethod;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ClientChunkToCubeSet;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeLightRefreshPacket;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightProvider;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

public interface ClientCubeCache extends CubeSource, CubeLightProvider {
    long cc_lightGeneration();

    boolean cc_applyCubeLight(LevelCube cube, long revision, long epoch, @Nullable CubeLightView light, long generation);

    boolean cc_applyScopedCubeLight(LevelCube cube, long revision, long epoch, @Nullable CubeLightView light, long generation);

    void cc_invalidateScopedCubeLight(CubePos pos, long epoch);

    void cc_expectCubeLight(LevelCube cube, long epoch);

    void cc_expectScopedCubeLight(LevelCube cube, long epoch);

    void cc_invalidateCubeLight(long epoch);

    boolean cc_isCubeLightReady(CubePos pos);

    @Nullable CCServerboundCubeLightRefreshPacket cc_pollCubeLightRefresh();

    void cc_onCubeRemoved(LevelCube cube);

    // TODO (P2) we might want a version of the vanilla replaceWithPacketData with a different signature for handling chunks, since we only need
    // heightmap data with CC

    void cc_drop(CubePos chunkPos);

    void cc_replaceBiomes(int x, int y, int z, FriendlyByteBuf buffer);

    @Nullable LevelCube cc_replaceWithPacketData(
            int x, int y, int z, FriendlyByteBuf buffer, Map<Heightmap.Types, long[]> map,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer
    );

    void cc_updateViewCenter(int x, int y, int z);

    void cc_updateViewRadius(int viewDistance);

    // Fields and methods on this are public so they can be accessed from MixinClientChunkCache and tests; they should not be used anywhere else
    // (This has to be here since we can't add inner classes with mixin)
    @Dasm(ClientChunkToCubeSet.class)
    final class Storage {
        public final AtomicReferenceArray<LevelCube> chunks;
        final LongOpenHashSet loadedEmptySections = new LongOpenHashSet();
        public final int cubeRadius;
        private final int viewRange;
        public volatile int viewCenterX;
        public volatile int viewCenterY;
        public volatile int viewCenterZ;
        public int chunkCount;
        // Field added since we can't get it off ClientChunkCache since this is no longer an inner class
        final ClientLevel level;

        public Storage(int chunkRadius, ClientLevel clientLevel) {
            this.cubeRadius = chunkRadius;
            this.viewRange = chunkRadius * 2 + 1;
            this.chunks = new AtomicReferenceArray<>(this.viewRange * this.viewRange * this.viewRange);
            this.level = clientLevel;
        }

        public int getIndex(int x, int y, int z) {
            return Math.floorMod(z, this.viewRange) * this.viewRange * this.viewRange + Math.floorMod(y, this.viewRange) * this.viewRange
                    + Math.floorMod(x, this.viewRange);
        }

        public void replace(int chunkIndex, @Nullable LevelCube chunk) {
            LevelCube levelchunk = this.chunks.getAndSet(chunkIndex, chunk);
            if (levelchunk != null) {
                --this.chunkCount;
                this.dropEmptySections(levelchunk);
                this.notifyRemoved(levelchunk);
//                this.level.unload(levelchunk); // TODO P2
            }

            if (chunk != null) {
                ++this.chunkCount;
            }
        }

        public void drop(int chunkIndex, LevelCube chunk) {
            if (this.chunks.compareAndSet(chunkIndex, chunk, null)) {
                this.chunkCount--;
                this.dropEmptySections(chunk);
                this.notifyRemoved(chunk);
            }

//            this.level.unload(chunk); // TODO P2
        }

        private void notifyRemoved(LevelCube cube) {
            if (this.level.getChunkSource() instanceof ClientCubeCache cache) {
                cache.cc_onCubeRemoved(cube);
            }
        }

        public void onSectionEmptinessChanged(int x, int y, int z, boolean isEmpty) {
            if (this.inRange(x, y, z)) {
                long i = SectionPos.asLong(x, y, z);
                if (isEmpty) {
                    this.loadedEmptySections.add(i);
                } else if (this.loadedEmptySections.remove(i)) {
//                    ClientChunkCache.this.level.onSectionBecomingNonEmpty(i); // TODO P2
                }
            }
        }

        public void dropEmptySections(LevelCube chunk) {
            var cubePos = chunk.cc_getCubePos();

            for (int dx = 0; dx < CubicConstants.DIAMETER_IN_SECTIONS; dx++) {
                for (int dy = 0; dy < CubicConstants.DIAMETER_IN_SECTIONS; dy++) {
                    for (int dz = 0; dz < CubicConstants.DIAMETER_IN_SECTIONS; dz++) {
                        long sectionPosLong = SectionPos.asLong(Coords.cubeToSection(cubePos.getX(), dx), Coords.cubeToSection(cubePos.getY(), dy),
                                Coords.cubeToSection(cubePos.getZ(), dz));
                        this.loadedEmptySections.remove(sectionPosLong);
                    }
                }
            }
        }

        public void addEmptySections(LevelCube chunk) {
            var cubePos = chunk.cc_getCubePos();
            LevelChunkSection[] chunkSections = chunk.getSections();

            for (int dx = 0; dx < CubicConstants.DIAMETER_IN_SECTIONS; dx++) {
                for (int dy = 0; dy < CubicConstants.DIAMETER_IN_SECTIONS; dy++) {
                    for (int dz = 0; dz < CubicConstants.DIAMETER_IN_SECTIONS; dz++) {
                        var chunkSection = chunkSections[Coords.sectionToIndex(dx, dy, dz)];
                        long sectionPosLong = SectionPos.asLong(Coords.cubeToSection(cubePos.getX(), dx), Coords.cubeToSection(cubePos.getY(), dy),
                                Coords.cubeToSection(cubePos.getZ(), dz));
                        if (chunkSection.hasOnlyAir()) {
                            this.loadedEmptySections.add(sectionPosLong);
                        }
                    }
                }
            }
        }

        public void refreshEmptySections(LevelCube chunk) {
            var cubePos = chunk.cc_getCubePos();
            LevelChunkSection[] chunkSections = chunk.getSections();

            for (int dx = 0; dx < CubicConstants.DIAMETER_IN_SECTIONS; dx++) {
                for (int dy = 0; dy < CubicConstants.DIAMETER_IN_SECTIONS; dy++) {
                    for (int dz = 0; dz < CubicConstants.DIAMETER_IN_SECTIONS; dz++) {
                        var chunkSection = chunkSections[Coords.sectionToIndex(dx, dy, dz)];
                        long sectionPosLong = SectionPos.asLong(Coords.cubeToSection(cubePos.getX(), dx), Coords.cubeToSection(cubePos.getY(), dy),
                                Coords.cubeToSection(cubePos.getZ(), dz));
                        if (chunkSection.hasOnlyAir()) {
                            this.loadedEmptySections.add(sectionPosLong);
                        } else if (this.loadedEmptySections.remove(sectionPosLong)) {
//                            ClientChunkCache.this.level.onSectionBecomingNonEmpty(sectionPosLong); // TODO P2
                        }
                    }
                }
            }
        }

        public boolean inRange(int x, int y, int z) {
            return Math.abs(x - this.viewCenterX) <= this.cubeRadius && Math.abs(y - this.viewCenterY) <= this.cubeRadius
                    && Math.abs(z - this.viewCenterZ) <= this.cubeRadius;
        }

        @TransformFromMethod(owner = @Ref(ClientChunkCache.Storage.class), value = "getChunk(I)Lnet/minecraft/world/level/chunk/LevelChunk;", visibility = PUBLIC)
        public native @Nullable LevelCube getChunk(int chunkIndex);

        public void dumpChunks(String filePath) {
            // TODO reimplement debug code
        }
    }
}
