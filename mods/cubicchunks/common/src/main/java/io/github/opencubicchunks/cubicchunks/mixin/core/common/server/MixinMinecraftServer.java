package io.github.opencubicchunks.cubicchunks.mixin.core.common.server;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cc_core.world.SpawnPlaceFinder;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.server.level.progress.CubeLoadStatusView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer {
    @Inject(method = "createChunkLoadStatusView", at = @At("RETURN"), cancellable = true)
    private void cc_createCubeLoadStatusView(int radius, CallbackInfoReturnable<ChunkLoadStatusView> cir) {
        cir.setReturnValue(new CubeLoadStatusView((MinecraftServer) (Object) this, cir.getReturnValue()));
    }

    @Shadow public abstract ServerLevel overworld();

    @Shadow public abstract Iterable<ServerLevel> getAllLevels();

    @Shadow public abstract ResourceManager getResourceManager();

    @Shadow @Final protected LevelStorageSource.LevelStorageAccess storageSource;

    @Shadow @Final private SavedDataStorage savedDataStorage;

    @Shadow private volatile boolean isSaving;

    /** Vanilla drains and saves before closing levels; a cube write error must not bypass their cleanup. */
    @WrapMethod(method = "stopServer")
    private void cc_closeAfterShutdownFailure(Operation<Void> original) {
        try {
            original.call();
        } catch (RuntimeException | Error failure) {
            boolean cubic = false;
            for (ServerLevel level : this.getAllLevels()) {
                cubic |= level != null && ((CanBeCubic) level).cc_isCubic();
            }
            if (cubic) {
                for (ServerLevel level : this.getAllLevels()) {
                    cc_closeAfterFailure(level, failure);
                }
                this.isSaving = false;
                cc_closeAfterFailure(this.savedDataStorage, failure);
                // ReloadableResources.close delegates to this same resource manager in 26.3.
                cc_closeAfterFailure(() -> {
                    if (this.getResourceManager() instanceof AutoCloseable resources) {
                        resources.close();
                    }
                }, failure);
                cc_closeAfterFailure(this.storageSource, failure);
            }
            throw failure;
        }
    }

    @Unique
    private static void cc_closeAfterFailure(AutoCloseable resource, Throwable failure) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Throwable cleanup) {
            if (cleanup != failure) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    // setInitialSpawn
    // 26.3 obtains the horizontal spawn origin from the generator. Preserve its choice and retain the cube's vertical origin for height lookup.
    @WrapOperation(method = "setInitialSpawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;getOrigin(Lnet/minecraft/world/level/levelgen/RandomState;)Lnet/minecraft/world/level/ChunkPos;"))
    private static ChunkPos cc_captureSpawnOrigin(
            ChunkGenerator generator, RandomState randomState, Operation<ChunkPos> original, ServerLevel serverLevel,
            @Share("cubePos") LocalRef<CubePos> cubePosLocalRef
    ) {
        ChunkPos origin = original.call(generator, randomState);
        if (((CanBeCubic) serverLevel).cc_isCubic()) {
            cubePosLocalRef.set(CubePos.from(origin, Coords.blockToCube(generator.getSpawnHeight(serverLevel))));
        }
        return origin;
    }

    @WrapOperation(method = "setInitialSpawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ChunkPos;getWorldPosition()Lnet/minecraft/core/BlockPos;"))
    private static BlockPos cc_replaceGetWorldPositionInSetInitialSpawn(
            ChunkPos chunkPos, Operation<BlockPos> original, ServerLevel serverLevel, @Share("cubePos") LocalRef<CubePos> cubePosLocalRef
    ) {
        if (((CanBeCubic) serverLevel).cc_isCubic()) {
            return cubePosLocalRef.get().asChunkPos().getWorldPosition();
        }
        return original.call(chunkPos);
    }

    /**
     * This mixin uses SpawnPlaceFinder (core CC2 code) in a similar fashion to the CC2 implementation.
     */
    @WrapOperation(method = "setInitialSpawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
    private static int cc_replaceGetHeightWithSpawnPlaceFinder(
            ServerLevel serverLevel, Heightmap.Types heightmapType, int x, int z, Operation<Integer> original,
            @Share("cubePos") LocalRef<CubePos> cubePosLocalRef
    ) {
        if (((CanBeCubic) serverLevel).cc_isCubic()) {
            BlockPos topBlockBisect = SpawnPlaceFinder.getTopBlockBisect(serverLevel, cubePosLocalRef.get().asBlockPos(), false,
                    pos -> serverLevel.getBlockState(pos).is(BlockTags.VALID_SPAWN),
                    pos -> serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty());
            if (topBlockBisect != null) {
                return topBlockBisect.getY();
            } else {
                return serverLevel.getSeaLevel() + 1; // This is the default value in vanilla
            }
        }
        return original.call(serverLevel, heightmapType, x, z);
    }

    // 26.3 prepareLevels counts tracked loads with ChunkLoadCounter; it no longer squares a configured spawn radius.

    // TODO P2 :: Forced cubes will need to be implemented here as well; but this includes saving logic so P2
}
