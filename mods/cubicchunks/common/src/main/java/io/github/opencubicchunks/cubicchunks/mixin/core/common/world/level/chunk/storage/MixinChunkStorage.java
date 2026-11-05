package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.chunk.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.DataFixer;
import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddMethodToSets;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ChunkToCloSet;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStorage;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.SerializableCubeData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps vanilla regions and cube files separate while sharing their flush and close lifecycle. */
@Dasm(value = ChunkToCloSet.class, target = @Ref(SimpleRegionStorage.class))
@Mixin(SimpleRegionStorage.class)
public abstract class MixinChunkStorage {
    @Unique
    private @Nullable Path cc_cubeDirectory;
    @Unique
    private @Nullable CubeStorage cc_cubeStorage;
    @Unique
    private boolean cc_storageClosed;
    @Unique
    private volatile @Nullable Throwable cc_failedCubeSave;

    /** Snapshot and preflight failures must also make flush/close fail, even before an I/O write was queued. */
    @Unique
    protected synchronized void cc_recordCubeSaveFailure(Throwable failure) {
        if (this.cc_failedCubeSave == null) {
            this.cc_failedCubeSave = failure;
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cc_rememberCubeDirectory(RegionStorageInfo info, Path folder, DataFixer fixer, boolean sync, DataFixTypes type, CallbackInfo ci) {
        this.cc_cubeDirectory = "chunk".equals(info.type()) ? folder.resolveSibling("cubes") : null;
    }

    @Unique
    private synchronized CubeStorage cc_cubeStorage() throws IOException {
        if (this.cc_storageClosed) {
            throw new IOException("Cube storage is closed: " + this.cc_cubeDirectory);
        }
        if (this.cc_cubeDirectory == null) {
            throw new IOException("Cube persistence is only available for chunk storage");
        }
        if (this.cc_cubeStorage == null) {
            this.cc_cubeStorage = new CubeStorage(this.cc_cubeDirectory);
        }
        return this.cc_cubeStorage;
    }

    @ModifyReturnValue(method = "synchronize", at = @At("RETURN"))
    private synchronized CompletableFuture<Void> cc_flushCubeStorage(CompletableFuture<Void> vanilla, boolean flush) {
        CompletableFuture<Void> cubes = this.cc_cubeStorage == null ? CompletableFuture.completedFuture(null) : this.cc_cubeStorage.flush();
        CompletableFuture<Void> saves = this.cc_failedCubeSave == null ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(this.cc_failedCubeSave);
        return CompletableFuture.allOf(vanilla, cubes, saves);
    }

    @WrapMethod(method = "close")
    private void cc_closeCubeStorage(Operation<Void> original) throws IOException {
        CubeStorage cubes;
        synchronized (this) {
            if (this.cc_storageClosed) {
                return;
            }
            this.cc_storageClosed = true;
            cubes = this.cc_cubeStorage;
        }
        // Both stores are closed even if one fails; try-with-resources preserves suppressed failures.
        try (CubeStorage closeCubes = cubes) {
            original.call();
            if (this.cc_failedCubeSave != null) {
                throw new IOException("A cube save failed before storage closed", this.cc_failedCubeSave);
            }
        }
    }

    @Shadow public abstract boolean isOldChunkAround(ChunkPos pos, int radius);

    @Shadow public abstract CompletableFuture<Optional<CompoundTag>> read(ChunkPos pos);

    @Shadow public abstract CompletableFuture<Void> write(ChunkPos pos, Supplier<CompoundTag> data);

    @Shadow public abstract CompletableFuture<Void> write(ChunkPos pos, CompoundTag data);

    @Unique
    protected CompletableFuture<NavigableMap<Integer, CompoundTag>> cc_readCubeColumn(int cubeX, int cubeZ, int minCubeY, int maxCubeY) {
        try {
            return this.cc_cubeStorage().readColumn(cubeX, cubeZ, minCubeY, maxCubeY);
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Unique
    protected CompletableFuture<Void> cc_writeCubeSnapshot(CubePos pos, CompoundTag snapshot, ChunkType type) {
        try {
            return this.cc_cubeStorage().writeChecked(pos, snapshot, type == ChunkType.LEVELCHUNK ? null
                    : saved -> SerializableCubeData.parse(saved, pos).chunkStatus().getChunkType() != ChunkType.LEVELCHUNK);
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @AddMethodToSets(containers = ChunkToCloSet.ChunkStorage_redirects.class, method = "isOldChunkAround(Lnet/minecraft/world/level/ChunkPos;I)Z")
    public boolean cc_isOldChunkAround(CloPos pos, int radius) {
        // Cubes cannot originate from vanilla legacy regions, so vanilla blending only applies to columns.
        return pos.isChunk() && isOldChunkAround(pos.chunkPos(), radius);
    }

    @AddMethodToSets(containers = ChunkToCloSet.ChunkStorage_redirects.class, method = "read(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;")
    public CompletableFuture<Optional<CompoundTag>> cc_read(CloPos pos) {
        if (pos.isChunk()) {
            return read(pos.chunkPos());
        }
        try {
            return this.cc_cubeStorage().read(pos.cubePos());
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @AddMethodToSets(containers = ChunkToCloSet.ChunkStorage_redirects.class, method = "write(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;")
    public CompletableFuture<Void> cc_write(CloPos pos, Supplier<CompoundTag> data) {
        if (pos.isChunk()) {
            return write(pos.chunkPos(), data);
        }
        try {
            return this.cc_cubeStorage().write(pos.cubePos(), data);
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @AddMethodToSets(containers = ChunkToCloSet.ChunkStorage_redirects.class, method = "write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;")
    public CompletableFuture<Void> cc_write(CloPos pos, CompoundTag data) {
        if (pos.isChunk()) {
            return write(pos.chunkPos(), data);
        }
        try {
            return this.cc_cubeStorage().write(pos.cubePos(), data);
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }
}
