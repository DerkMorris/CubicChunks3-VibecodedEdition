package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.level;

import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import javax.annotation.Nullable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddFieldToSets;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddMethodToSets;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddTransformToSets;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.notstirred.dasm.api.annotations.transform.TransformFromMethod;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.exception.DasmFailedToApply;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ChunkToCloSet;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ChunkToCubeSet;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockChangesPacket;
import io.github.opencubicchunks.cubicchunks.server.level.CloHolder;
import io.github.opencubicchunks.cubicchunks.server.level.CubeHolder;
import io.github.opencubicchunks.cubicchunks.server.level.CubicChunkMap;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.LevelClo;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import io.github.opencubicchunks.cubicchunks.platform.Platform;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
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
 * The vanilla {@link ChunkHolder} class extends {@link GenerationChunkHolder} to have methods for getting a fully loaded chunk, and handle saving
 * dependencies and broadcasting updates to clients. This mixin adds cubic chunks equivalents for methods where necessary, to allow
 * GenerationChunkHolder to dynamically wrap either a chunk or a cube (i.e. a CLO).
 */
@Dasm(value = ChunkToCubeSet.class, target = @Ref(ChunkHolder.class))
@Mixin(ChunkHolder.class)
public abstract class MixinChunkHolder extends MixinGenerationChunkHolder implements CloHolder, CubeHolder {
    @Shadow private boolean hasChangedSections;
    @Shadow @Final private ShortSet[] changedBlocksPerSection;
    @Shadow @Final private BitSet skyChangedLightSectionFilter;
    @Shadow @Final private BitSet blockChangedLightSectionFilter;

    @AddFieldToSets(containers = ChunkToCubeSet.ChunkHolder_redirects.class, field = "onLevelChange:Lnet/minecraft/server/level/ChunkHolder$LevelChangeListener;")
    private final LevelChangeListener cc_onLevelChange;

    @AddFieldToSets(containers = ChunkToCubeSet.ChunkHolder_redirects.class, field = "playerProvider:Lnet/minecraft/server/level/ChunkHolder$PlayerProvider;")
    private final PlayerProvider cc_playerProvider;

    @AddTransformToSets(ChunkToCubeSet.ChunkHolder_redirects.class)
    @TransformFromMethod(owner = @Ref(ChunkHolder.class), value = "<init>(Lnet/minecraft/world/level/ChunkPos;ILnet/minecraft/world/level/LevelHeightAccessor;"
            + "Lnet/minecraft/world/level/lighting/LevelLightEngine;Lnet/minecraft/server/level/ChunkHolder$LevelChangeListener;"
            + "Lnet/minecraft/server/level/ChunkHolder$PlayerProvider;)V")
    public MixinChunkHolder() {
        throw new DasmFailedToApply();
    }

    @Dynamic @Redirect(method = "<init>(Lio/github/opencubicchunks/cc_core/api/CubePos;ILnet/minecraft/world/level/LevelHeightAccessor;"
            + "Lnet/minecraft/world/level/lighting/LevelLightEngine;Lio/github/opencubicchunks/cubicchunks/server/level/CubeHolder$LevelChangeListener;"
            + "Lio/github/opencubicchunks/cubicchunks/server/level/CubeHolder$PlayerProvider;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelHeightAccessor;getSectionsCount()I"))
    private int cc_changedSectionCount(LevelHeightAccessor height) {
        return CubicConstants.SECTION_COUNT;
    }

    @Shadow public abstract @Nullable LevelChunk getTickingChunk();

    @AddTransformToSets(ChunkToCubeSet.ChunkHolder_redirects.class)
    @TransformFromMethod(owner = @Ref(ChunkHolder.class), value = "getTickingChunk()Lnet/minecraft/world/level/chunk/LevelChunk;")
    public native @Nullable LevelCube cc_getTickingCube();

    @AddMethodToSets(containers = ChunkToCloSet.ChunkHolder_redirects.class, method = "getTickingChunk()Lnet/minecraft/world/level/chunk/LevelChunk;")
    public @Nullable LevelClo cc_getTickingClo() {
        if (cc_cubePos != null) {
            return cc_getTickingCube();
        }
        return (LevelClo) getTickingChunk();
    }

    @Shadow public abstract @Nullable LevelChunk getChunkToSend();

    @AddTransformToSets(ChunkToCubeSet.ChunkHolder_redirects.class)
    @TransformFromMethod(owner = @Ref(ChunkHolder.class), value = "getChunkToSend()Lnet/minecraft/world/level/chunk/LevelChunk;")
    public native @Nullable LevelCube cc_getCubeToSend();

    @AddTransformToSets(ChunkToCloSet.ChunkHolder_redirects.class)
    @TransformFromMethod(owner = @Ref(ChunkHolder.class), value = "getChunkToSend()Lnet/minecraft/world/level/chunk/LevelChunk;")
    public @Nullable LevelClo cc_getCloToSend() {
        if (cc_cubePos != null) {
            return cc_getCubeToSend();
        }
        return (LevelClo) getChunkToSend();
    }

    @Inject(method = "blockChanged", at = @At("HEAD"), cancellable = true)
    public void cc_onBlockChanged(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cc_cubePos != null) {
            cir.setReturnValue(cc_blockChanged(pos));
        }
    }

    // region [cc_blockChanged dasm + mixin]
    @TransformFromMethod("blockChanged(Lnet/minecraft/core/BlockPos;)Z")
    private native boolean cc_blockChanged(BlockPos pos);

    @Dynamic @Redirect(method = "cc_blockChanged", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelHeightAccessor;getSectionIndex(I)I"))
    private int cc_onBlockChanged_sectionIndex(LevelHeightAccessor instance, int y, BlockPos pos) {
        return Coords.sectionToIndex(Coords.blockToSection(pos.getX()), Coords.blockToSection(pos.getY()), Coords.blockToSection(pos.getZ()));
    }
    // endregion

    @Inject(method = "sectionLightChanged", at = @At("HEAD"), cancellable = true)
    public void cc_onSectionLightChanged(LightLayer lightLayer, int sectionY, CallbackInfoReturnable<Boolean> cir) {
        if (cc_cubePos != null) {
            // CubeLightManager publishes cube light; a vanilla column's section-Y mask cannot describe it.
            cir.setReturnValue(false);
        }
    }

    @Shadow public abstract void broadcastChanges(LevelChunk chunk);

    @AddMethodToSets(containers = ChunkToCubeSet.ChunkHolder_redirects.class, method = "broadcastChanges(Lnet/minecraft/world/level/chunk/LevelChunk;)V")
    public void cc_broadcastCubeChanges(LevelCube cube) {
        // Column light masks are never sent for cubes. Light changes use validated full-cube publications instead.
        this.skyChangedLightSectionFilter.clear();
        this.blockChangedLightSectionFilter.clear();
        if (!this.hasChangedSections) {
            return;
        }
        List<ServerPlayer> players = this.cc_playerProvider.cc_getPlayers(this.cc_cubePos, false);
        for (int sectionIndex = 0; sectionIndex < CubicConstants.SECTION_COUNT; sectionIndex++) {
            ShortSet changes = this.changedBlocksPerSection[sectionIndex];
            if (changes == null) {
                continue;
            }
            if (!players.isEmpty()) {
                this.cc_broadcastSectionChanges(cube, sectionIndex, changes, players);
            }
            this.changedBlocksPerSection[sectionIndex] = null;
        }
        this.hasChangedSections = false;
    }

    @Unique
    private void cc_broadcastSectionChanges(LevelCube cube, int index, ShortSet changes, List<ServerPlayer> players) {
        CCClientboundCubeBlockChangesPacket.forEachPacket(cube, index, changes, packet -> {
            for (ServerPlayer player : players) {
                Platform.get().send(player, packet);
            }
        });
    }

    @AddMethodToSets(containers = ChunkToCloSet.ChunkHolder_redirects.class, method = "broadcastChanges(Lnet/minecraft/world/level/chunk/LevelChunk;)V")
    public void cc_broadcastCloChanges(LevelClo clo) {
        if (cc_cubePos != null) {
            cc_broadcastCubeChanges((LevelCube) clo);
        } else {
            broadcastChanges(((LevelChunk) clo));
        }
    }

    /** Vanilla's success consumer casts to LevelChunk before completing the confirmation, silently stranding cube promotions. */
    @Inject(method = "lambda$scheduleFullChunkPromotion$1", at = @At("HEAD"), cancellable = true)
    private static void cc_confirmCubePromotion(CompletableFuture<?> confirmation, ChunkResult<?> result, CallbackInfo ci) {
        if (result.orElse(null) instanceof LevelCube) {
            confirmation.complete(null);
            ci.cancel();
        }
    }

    @WrapOperation(method = { "lambda$scheduleFullChunkPromotion$0",
        "demoteFullChunk" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;onFullChunkStatusChange(Lnet/minecraft/world/level/ChunkPos;"
                + "Lnet/minecraft/server/level/FullChunkStatus;)V"))
    private void cc_onCallChunkMapOnFullChunkStatusChange(
            ChunkMap instance, ChunkPos chunkPos, FullChunkStatus fullChunkStatus, Operation<Void> original
    ) {
        if (cc_cubePos != null) {
            ((CubicChunkMap) instance).cc_onFullChunkStatusChange(cc_cubePos, fullChunkStatus);
        } else {
            original.call(instance, chunkPos, fullChunkStatus);
        }
    }

    @WrapOperation(method = "updateFutures", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder$LevelChangeListener;onLevelChange(Lnet/minecraft/world/level/ChunkPos;"
            + "Ljava/util/function/IntSupplier;ILjava/util/function/IntConsumer;)V"))
    protected void cc_onUpdateFutures_onCallOnLevelChange(
            ChunkHolder.LevelChangeListener instance, ChunkPos chunkPos, IntSupplier intSupplier, int i, IntConsumer intConsumer,
            Operation<Void> original
    ) {
        if (cc_cubePos != null) {
            cc_onLevelChange.cc_onLevelChange(cc_cubePos, intSupplier, i, intConsumer);
        } else {
            original.call(instance, chunkPos, intSupplier, i, intConsumer);
        }
    }
}
