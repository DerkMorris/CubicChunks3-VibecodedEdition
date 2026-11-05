package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level;

import javax.annotation.Nullable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.MarkableAsCubic;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ChunkToCloSet;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevel;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Dasm(value = ChunkToCloSet.class, target = @Ref(Level.class))
@Mixin(Level.class)
public abstract class MixinLevel implements CubicLevel, MarkableAsCubic, LevelAccessor {
    /** The collision iterator asks for X/Z columns; this reader resolves each full block position to a loaded cube. */
    @Inject(method = "getChunkForCollisions", at = @At("HEAD"), cancellable = true)
    private void cc_collisionBlocks(int x, int z, CallbackInfoReturnable<BlockGetter> cir) {
        if (cc_isCubic) {
            cir.setReturnValue(new CubeMobBlockView((Level) (Object) this));
        }
    }

    @Shadow public abstract @Nullable ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus requestedStatus, boolean forceLoad);

    protected boolean cc_isCubic;

    @Override public void cc_setCubic() {
        cc_isCubic = true;
    }

    @Override public boolean cc_isCubic() {
        return cc_isCubic;
    }

    /** Keep dimension height/section counts intact; block access and placement use the cube coordinate range. */
    @Override public boolean isInsideBuildHeight(int y) {
        return cc_isCubic ? CubicBlockBounds.contains(y) : y >= getMinY() && y <= getMaxY();
    }

    @Override public boolean isOutsideBuildHeight(int y) {
        return !isInsideBuildHeight(y);
    }

    @Inject(method = "isInValidBounds", at = @At("HEAD"), cancellable = true)
    private void cc_rejectUnrepresentableBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cc_isCubic && !CubicBlockBounds.contains(pos)) {
            cir.setReturnValue(false);
        }
    }

    public LevelCube cc_getCubeAt(BlockPos blockPos) {
        return this.cc_getCube(Coords.blockToCube(blockPos.getX()), Coords.blockToCube(blockPos.getY()), Coords.blockToCube(blockPos.getZ()));
    }

    public LevelCube cc_getCube(int cubeX, int cubeY, int cubeZ) {
        return (LevelCube) this.cc_getCube(cubeX, cubeY, cubeZ, ChunkStatus.FULL);
    }

    @Override public @Nullable CubeAccess cc_getCube(int cubeX, int cubeY, int cubeZ, ChunkStatus status, boolean forceLoad) {
        CubeAccess cubeaccess = ((CubeSource) this.getChunkSource()).cc_getCube(cubeX, cubeY, cubeZ, status, forceLoad);
        if (cubeaccess == null && forceLoad) {
            throw new IllegalStateException("Should always be able to create a cube!");
        } else {
            return cubeaccess;
        }
    }

    @Inject(method = "<init>", at = @At(value = "CTOR_HEAD"))
    private void cc_init(
            net.minecraft.world.level.storage.WritableLevelData data, net.minecraft.resources.ResourceKey<Level> dimension,
            net.minecraft.core.RegistryAccess registries, net.minecraft.core.Holder<net.minecraft.world.level.dimension.DimensionType> type,
            boolean client, boolean debug, long seed, int maxNeighbors, CallbackInfo ci
    ) {
        if (io.github.opencubicchunks.cubicchunks.world.level.CubicWorldMode.isCubic(data, dimension, client)) {
            this.cc_setCubic();
        }
    }

    @Override public @Nullable BlockGetter cc_getCubeForCollisions(int cubeX, int cubeY, int cubeZ) {
        return cc_isCubic ? new CubeMobBlockView((Level) (Object) this) : this.cc_getCube(cubeX, cubeY, cubeZ, ChunkStatus.FULL, false);
    }

    // setBlock
    // Vanilla performs notification in setBlock itself. Replace only the chunk operations and retain its notification logic.
    @WrapOperation(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level"
            + "/Level;getChunkAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk cc_replaceLevelChunkInGetChunkAt(
            Level level, BlockPos blockPos, Operation<LevelChunk> original, @Share("levelCube") LocalRef<LevelCube> levelCubeLocalRef
    ) {
        if (cc_isCubic) {
            levelCubeLocalRef.set(this.cc_getCubeAt(blockPos));
            return null;
        }
        return original.call(level, blockPos);
    }

    @WrapOperation(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState cc_replaceLevelChunkInSetBlockState(
            LevelChunk levelChunk, BlockPos blockPos, BlockState blockState, int flags, Operation<BlockState> original,
            @Share("levelCube") LocalRef<LevelCube> levelCubeLocalRef
    ) {
        if (cc_isCubic) {
            return levelCubeLocalRef.get().setBlockState(blockPos, blockState, flags);
        }
        return original.call(levelChunk, blockPos, blockState, flags);
    }

    @WrapOperation(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getFullStatus()Lnet/minecraft/server/level/FullChunkStatus;"))
    private FullChunkStatus cc_replaceLevelChunkStatusInSetBlock(
            LevelChunk levelChunk, Operation<FullChunkStatus> original, @Share("levelCube") LocalRef<LevelCube> levelCubeLocalRef
    ) {
        return cc_isCubic ? levelCubeLocalRef.get().getFullStatus() : original.call(levelChunk);
    }

    // getBlockState
    // Preserve vanilla's bounds check, then return before its column lookup can load a chunk.
    @Inject(method = "getBlockState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"), cancellable = true)
    private void cc_replaceLevelChunkInGetBlockState(BlockPos blockPos, CallbackInfoReturnable<BlockState> cir) {
        if (cc_isCubic) {
            cir.setReturnValue(this.cc_getCubeAt(blockPos).getBlockState(blockPos));
        }
    }

    // getBlockEntity
    // Replaces LevelChunk with a LevelCube to call getBlockEntity
    @Inject(method = "getBlockEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getChunkAt(Lnet/minecraft/core/BlockPos;)"
            + "Lnet/minecraft/world/level/chunk/LevelChunk;"), cancellable = true)
    private void cc_replaceGetChunkAtInSetBlockEntity(BlockPos blockPos, CallbackInfoReturnable<BlockEntity> cir) {
        if (cc_isCubic) {
            cir.setReturnValue(this.cc_getCubeAt(blockPos).getBlockEntity(blockPos, LevelChunk.EntityCreationType.IMMEDIATE));
        }
    }

    // getFluidState
    // Preserve vanilla's bounds check, then bypass the column lookup in cubic levels.
    @Inject(method = "getFluidState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getChunkAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/chunk/LevelChunk;"), cancellable = true)
    private void cc_replaceGetChunkAtInGetFluidState(BlockPos blockPos, CallbackInfoReturnable<FluidState> cir) {
        if (cc_isCubic) {
            cir.setReturnValue(this.cc_getCubeAt(blockPos).getFluidState(blockPos));
        }
    }

    // setBlockEntity
    // Replaces LevelChunk with a LevelCube to call addAndRegisterBlockEntity
    @Inject(method = "setBlockEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getChunkAt(Lnet/minecraft/core/BlockPos;)"
            + "Lnet/minecraft/world/level/chunk/LevelChunk;"), cancellable = true)
    private void cc_replaceLevelChunkInSetBlockEntity(BlockEntity blockEntity, CallbackInfo ci, @Local(ordinal = 0) BlockPos blockPos) {
        if (cc_isCubic) {
            this.cc_getCubeAt(blockPos).addAndRegisterBlockEntity(blockEntity);
            ci.cancel();
        }
    }

    // removeBlockEntity
    // Replaces LevelChunk with a LevelCube to call removeBlockEntity, needs a local ref to do so
    @WrapOperation(method = "removeBlockEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getChunkAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk cc_replaceGetChunkAtInRemoveBlockEntity(
            Level level, BlockPos pos, Operation<LevelChunk> original, @Share("levelCube") LocalRef<LevelCube> levelCubeLocalRef
    ) {
        if (cc_isCubic) {
            levelCubeLocalRef.set(cc_getCubeAt(pos));
            return null;
        }
        return original.call(level, pos);
    }

    @WrapOperation(method = "removeBlockEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;removeBlockEntity(Lnet/minecraft/core/BlockPos;)V"))
    private void cc_replaceLevelChunkInRemoveBlockEntity(
            LevelChunk levelChunk, BlockPos pos, Operation<Void> original, @Share("levelCube") LocalRef<LevelCube> levelCubeLocalRef
    ) {
        if (cc_isCubic) {
            levelCubeLocalRef.get().removeBlockEntity(pos);
        } else {
            original.call(levelChunk, pos);
        }
    }

    // isLoaded
    // Replaces ChunkSource with a CubeSource to call hasCube
    @WrapOperation(method = "isLoaded", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkSource;hasChunk(II)Z"))
    private boolean cc_replaceHasChunkInIsLoaded(ChunkSource chunkSource, int x, int z, Operation<Boolean> original, BlockPos blockPos) {
        if (cc_isCubic) {
            return ((CubeSource) chunkSource).cc_hasCube(Coords.blockToCube(blockPos.getX()), Coords.blockToCube(blockPos.getY()),
                    Coords.blockToCube(blockPos.getZ()));
        }
        return original.call(chunkSource, x, z);
    }

    // loadedAndEntityCanStandOnFace
    // Uses an inject here since the entire second half of the method needs to be replaced anyways
    @Inject(method = "loadedAndEntityCanStandOnFace", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;"), cancellable = true)
    private void cc_replaceGetChunkAtInLoadedAndEntityCanStandOnFace(
            BlockPos blockPos, Entity entity, Direction direction, CallbackInfoReturnable<Boolean> cir
    ) {
        if (cc_isCubic) {
            CubeAccess cubeAccess = this.cc_getCube(Coords.blockToCube(blockPos.getX()), Coords.blockToCube(blockPos.getY()),
                    Coords.blockToCube(blockPos.getZ()), ChunkStatus.FULL, false);
            cir.setReturnValue(
                    cubeAccess == null ? false : cubeAccess.getBlockState(blockPos).entityCanStandOnFace(this, blockPos, entity, direction));
        }
    }

    // blockEntityChanged
    // This function is small enough that we can just replace it entirely
    @Inject(method = "blockEntityChanged", at = @At(value = "HEAD"), cancellable = true)
    private void cc_replaceBlockEntityChanged(BlockPos blockPos, CallbackInfo ci) {
        if (cc_isCubic) {
            if (this.cc_hasCubeAt(blockPos)) {
                this.cc_getCubeAt(blockPos).markUnsaved();
            }
            ci.cancel();
        }
    }

    // TODO: Phase 3 low priority: Add a method to modify isOutsideSpawnableHeight to respect the limits of the packing for CloPos
}
