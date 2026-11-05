package io.github.opencubicchunks.cubicchunks.mixin.dasmsets;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import io.github.notstirred.dasm.api.annotations.redirect.redirects.ConstructorToFactoryRedirect;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.FieldRedirect;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.FieldToMethodRedirect;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.MethodRedirect;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.TypeRedirect;
import io.github.notstirred.dasm.api.annotations.redirect.sets.InterOwnerContainer;
import io.github.notstirred.dasm.api.annotations.redirect.sets.IntraOwnerContainer;
import io.github.notstirred.dasm.api.annotations.redirect.sets.RedirectSet;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.exception.DasmFailedToApply;
import io.github.opencubicchunks.cubicchunks.server.level.CubeHolder;
import io.github.opencubicchunks.cubicchunks.server.level.CubeLevel;
import io.github.opencubicchunks.cubicchunks.server.level.GeneratingCubeMap;
import io.github.opencubicchunks.cubicchunks.util.StaticCache3D;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.CloAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.EmptyLevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ImposterProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.status.CubePyramid;
import io.github.opencubicchunks.cubicchunks.world.level.cube.status.CubeStatusTask;
import io.github.opencubicchunks.cubicchunks.world.level.cube.status.CubeStatusTasks;
import io.github.opencubicchunks.cubicchunks.world.level.cube.status.CubeStep;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CloHeightmap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStatusTask;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Should be used for DASM transforms that work with only Cubes (as opposed to working with both Chunks and Cubes) <br/>
 * <br/>
 * Cube-related field and
 * type redirects, and method redirects containing Cube-related types in the signature or return type should be added to this set. <br/>
 * Other
 * redirects may also be added to this set if they should only be applied in contexts working with only Cubes. Redirects applicable in all contexts
 * should be added to {@link GlobalSet}.
 */
@RedirectSet
public interface ChunkToCubeSet extends GlobalSet, LoaderRedirectSet.Cube {
    @TypeRedirect(from = @Ref(ChunkPos.class), to = @Ref(CubePos.class))
    abstract class ChunkPos_to_CubePos_redirects {
        @FieldRedirect("INVALID_CHUNK_POS:J")
        static final long INVALID_CUBE_POS = Long.MAX_VALUE;

        @MethodRedirect("x()I")
        native int getX();

        @MethodRedirect("z()I")
        native int getZ();

        @MethodRedirect("pack()J")
        native long asLong();

        @MethodRedirect("pack(Lnet/minecraft/core/BlockPos;)J")
        static native long asLong(BlockPos pos);

        @MethodRedirect("unpack(J)Lnet/minecraft/world/level/ChunkPos;")
        static native CubePos from(long cubePos);

        // Dummy methods that throw errors; these should be manually redirected to the correct x,y,z methods using mixin.
        // (See: MixinCubePos)
        @ConstructorToFactoryRedirect("<init>(II)V")
        static native CubePos dummy_fromChunkCoords(int x, int z);

        @MethodRedirect("pack(II)J")
        static native long dummy_chunkAsLong(int x, int z);
    }

    @TypeRedirect(from = @Ref(ChunkAccess.class), to = @Ref(CubeAccess.class))
    abstract class ChunkAccess_to_CubeAccess_redirects {
        @FieldRedirect("chunkPos:Lnet/minecraft/world/level/ChunkPos;")
        protected CubePos cubePos;

        @MethodRedirect("getPos()Lnet/minecraft/world/level/ChunkPos;")
        public native CubePos cc_getCubePos();

        @MethodRedirect("getHeightmaps()Ljava/util/Collection;")
        public native Collection<Map.Entry<Heightmap.Types, CloHeightmap>> cc_getHeightmaps();

        @MethodRedirect("getOrCreateHeightmapUnprimed(Lnet/minecraft/world/level/levelgen/Heightmap$Types;)Lnet/minecraft/world/level/levelgen/Heightmap;")
        public native CloHeightmap cc_getOrCreateHeightmapUnprimed(Heightmap.Types type);
    }

    @TypeRedirect(from = @Ref(Heightmap.class), to = @Ref(CloHeightmap.class))
    interface Heightmap_to_CloHeightmap_redirects {
        @MethodRedirect("primeHeightmaps(Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/util/Set;)V")
        static void primeHeightmaps(CloAccess clo, Set<Heightmap.Types> types) {
            throw new DasmFailedToApply();
        }
    }

    @TypeRedirect(from = @Ref(string = "net.minecraft.world.level.chunk.ChunkAccess$ChunkPathElement"), to = @Ref(string = "io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess$CubePathElement"))
    abstract class ChunkAccess$ChunkPathElement_to_CubeAccess$CubePathElement_redirects {

    }

    @TypeRedirect(from = @Ref(LevelChunk.class), to = @Ref(LevelCube.class))
    abstract class LevelChunk_to_LevelCube_redirects extends ChunkAccess_to_CubeAccess_redirects {}

    @TypeRedirect(from = @Ref(LevelChunk.PostLoadProcessor.class), to = @Ref(LevelCube.PostLoadProcessor.class))
    interface LevelChunk$PostLoadProcessor_to_LevelCube$PostLoadProcessor_redirects {}

    @TypeRedirect(from = @Ref(string = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity"), to = @Ref(string = "io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube$BoundTickingBlockEntity"))
    abstract class LevelChunk$BoundTickingBlockEntity_to_LevelCube$BoundTickingBlockEntity_redirects {}

    @TypeRedirect(from = @Ref(string = "net.minecraft.world.level.chunk.LevelChunk$RebindableTickingBlockEntityWrapper"), to = @Ref(string = "io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube$RebindableTickingBlockEntityWrapper"))
    abstract class LevelChunk$RebindableTickingBlockEntityWrapper_to_LevelCube$RebindableTickingBlockEntityWrapper_redirects {}

    @TypeRedirect(from = @Ref(ProtoChunk.class), to = @Ref(ProtoCube.class))
    abstract class ProtoChunk_to_ProtoCube_redirects {}

    @TypeRedirect(from = @Ref(ImposterProtoChunk.class), to = @Ref(ImposterProtoCube.class))
    abstract class ImposterProtoChunk_to_ImposterProtoCube_redirects {
        // Whole-class method declarations need the redirect on this exact owner, not only on ChunkAccess.
        @MethodRedirect("getOrCreateHeightmapUnprimed(Lnet/minecraft/world/level/levelgen/Heightmap$Types;)Lnet/minecraft/world/level/levelgen/Heightmap;")
        public native CloHeightmap cc_getOrCreateHeightmapUnprimed(Heightmap.Types type);
    }

    @TypeRedirect(from = @Ref(EmptyLevelChunk.class), to = @Ref(EmptyLevelCube.class))
    abstract class EmptyLevelChunk_to_EmptyLevelCube_redirects {}

    // FIXME probably need to move to a client-only set
    @TypeRedirect(from = @Ref(ChunkStatusTask.class), to = @Ref(CubeStatusTask.class))
    interface ChunkStatusTask_to_CubeStatusTask_redirects {}

    @TypeRedirect(from = @Ref(StaticCache2D.class), to = @Ref(StaticCache3D.class))
    abstract class StaticCache2D_to_StaticCache3D_redirects {}

    @TypeRedirect(from = @Ref(ChunkStep.class), to = @Ref(CubeStep.class))
    abstract class ChunkStep_to_CubeStep_redirects {}

    @TypeRedirect(from = @Ref(ChunkStep.Builder.class), to = @Ref(CubeStep.Builder.class))
    abstract class ChunkStep$Builder_to_CubeStep$Builder_redirects {}

    @TypeRedirect(from = @Ref(ChunkPyramid.class), to = @Ref(CubePyramid.class))
    abstract class ChunkPyramid_to_CubePyramid_redirects {}

    @TypeRedirect(from = @Ref(ChunkPyramid.Builder.class), to = @Ref(CubePyramid.Builder.class))
    abstract class ChunkPyramid$Builder_to_CubePyramid$Builder_redirects {}

    @TypeRedirect(from = @Ref(ChunkHolder.LevelChangeListener.class), to = @Ref(CubeHolder.LevelChangeListener.class))
    interface ChunkHolder$LevelChangeListener_to_CubeHolder$LevelChangeListener_redirects {
        @MethodRedirect("onLevelChange(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/IntSupplier;ILjava/util/function/IntConsumer;)V")
        void cc_onLevelChange(CubePos cubePos, IntSupplier queueLevelGetter, int ticketLevel, IntConsumer queueLevelSetter);
    }

    @TypeRedirect(from = @Ref(ChunkHolder.PlayerProvider.class), to = @Ref(CubeHolder.PlayerProvider.class))
    interface ChunkHolder$PlayerProvider_to_CubeHolder$PlayerProvider_redirects {
        @MethodRedirect("getPlayers(Lnet/minecraft/world/level/ChunkPos;Z)Ljava/util/List;")
        List<ServerPlayer> cc_getPlayers(CubePos pos, boolean boundaryOnly);
    }

    @TypeRedirect(from = @Ref(GeneratingChunkMap.class), to = @Ref(GeneratingCubeMap.class))
    interface GeneratingChunkMap_to_GeneratingCubeMap_redirects {
        @MethodRedirect("applyStep(Lnet/minecraft/server/level/GenerationChunkHolder;Lnet/minecraft/world/level/chunk/status/ChunkStep;"
                + "Lnet/minecraft/util/StaticCache2D;)Ljava/util/concurrent/CompletableFuture;")
        CompletableFuture<CubeAccess> cc_applyCubeStep(GenerationChunkHolder chunk, CubeStep step, StaticCache3D<GenerationChunkHolder> cache);

        @MethodRedirect("scheduleGenerationTask(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Lnet/minecraft/world/level/ChunkPos;)"
                + "Lnet/minecraft/server/level/ChunkGenerationTask;")
        ChunkGenerationTask cc_scheduleGenerationTask(ChunkStatus targetStatus, CubePos pos);
    }

    @IntraOwnerContainer(@Ref(ChunkGenerationTask.class))
    abstract class ChunkGenerationTask_redirects {
        @FieldToMethodRedirect("chunkMap:Lnet/minecraft/server/level/GeneratingChunkMap;")
        private native GeneratingCubeMap cc_getGeneratingCubeMap();
    }

    @TypeRedirect(from = @Ref(LevelChunk.UnsavedListener.class), to = @Ref(LevelCube.UnsavedListener.class))
    interface LevelChunk$UnsavedListener_to_LevelCube$UnsavedListener_redirects {}

    @IntraOwnerContainer(@Ref(ChunkHolder.class))
    abstract class ChunkHolder_redirects extends GenerationChunkHolder_redirects {}

    // Loader-added cube hooks are supplied by LoaderRedirectSet.Cube.

    @InterOwnerContainer(from = @Ref(ChunkLevel.class), to = @Ref(CubeLevel.class))
    class ChunkLevel_to_CubeLevel_redirects {}

    @InterOwnerContainer(from = @Ref(ChunkStatusTasks.class), to = @Ref(CubeStatusTasks.class))
    class ChunkStatusTasks_to_CubeStatusTasks_redirects {}

    @IntraOwnerContainer(@Ref(GenerationChunkHolder.class))
    class GenerationChunkHolder_redirects {
        @FieldRedirect("pos:Lnet/minecraft/world/level/ChunkPos;")
        protected CubePos cc_cubePos;
    }

    @IntraOwnerContainer(@Ref(ChunkMap.class))
    class ChunkMap_redirects {}

    @IntraOwnerContainer(@Ref(ServerChunkCache.class))
    class ServerChunkCache_redirects {}

    @IntraOwnerContainer(@Ref(Entity.class))
    class Entity_redirects {}

    @IntraOwnerContainer(@Ref(ServerPlayer.class))
    class ServerPlayer_redirects extends Entity_redirects {}

    @IntraOwnerContainer(@Ref(Level.class))
    class Level_redirects {}

    @IntraOwnerContainer(@Ref(ServerLevel.class))
    class ServerLevel_redirects extends Level_redirects {}

}
