package io.github.opencubicchunks.cubicchunks.mixin.core.client.renderer;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.renderer.CubicLevelRenderer;
import io.github.opencubicchunks.cubicchunks.mixin.access.client.ViewAreaAccess;
import io.github.opencubicchunks.cubicchunks.server.level.CloTrackingView;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionOcclusionGraph.class)
public abstract class MixinSectionOcclusionGraph implements CubicLevelRenderer {
    private boolean cc_isCubic = false;
    @Shadow private @Nullable ViewArea viewArea;

    @Shadow protected abstract boolean isInViewDistance(long centerSectionPos, long sectionPos);

    @Inject(method = "waitAndReset", at = @At("RETURN"))
    private void cc_onWaitAndReset(@Nullable ViewArea viewArea, CallbackInfo ci) {
        cc_isCubic = viewArea != null && ((CanBeCubic) viewArea).cc_isCubic();
        if (!cc_isCubic) {
            cc_cubeChanges.clear();
        }
    }

    @Shadow @Final private LongOpenHashSet loadedChunks;

    @Shadow public abstract void invalidate();

    @Unique
    private final ConcurrentLinkedQueue<Map.Entry<Long, Boolean>> cc_cubeChanges = new ConcurrentLinkedQueue<>();

    /** Rebuilding a view area does not resend cubes; world teardown still clears their availability. */
    @WrapOperation(method = "waitAndReset", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;clear()V"))
    private void cc_keepLoadedCubesOnViewRebuild(LongOpenHashSet set, Operation<Void> original, @Nullable ViewArea newViewArea) {
        if (set == loadedChunks && cc_isCubic && newViewArea != null && ((CanBeCubic) newViewArea).cc_isCubic()) {
            return;
        }
        original.call(set);
    }

    @Override public void cc_onCubeReadyToRender(CubePos cubePos) {
        cc_cubeChanges.add(Map.entry(cubePos.asLong(), true));
    }

    @Override public void cc_onCubeUnloaded(CubePos cubePos) {
        cc_cubeChanges.add(Map.entry(cubePos.asLong(), false));
    }

    /** Packet handlers enqueue changes; the render graph owns mutation of its loaded set. */
    @Inject(method = "updateLoadedChunks", at = @At("HEAD"), cancellable = true)
    private void cc_updateLoadedCubes(LongOpenHashSet added, LongOpenHashSet removed, CallbackInfo ci) {
        if (!cc_isCubic) {
            cc_cubeChanges.clear();
            return;
        }
        boolean removedCube = false;
        Map.Entry<Long, Boolean> change;
        while ((change = cc_cubeChanges.poll()) != null) {
            if (change.getValue()) {
                // runPartialUpdate wakes sections waiting on this cube, including in a newly published graph.
                loadedChunks.add(change.getKey());
            } else {
                removedCube |= loadedChunks.remove(change.getKey());
            }
        }
        if (removedCube) {
            invalidate();
        }
        ci.cancel();
    }

    @ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;runPartialUpdate(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lit/unimi/dsi/fastutil/longs/LongSet;)V"), index = 1)
    private LongSet cc_useLoadedCubesForPartialUpdate(LongSet vanillaExpectedChunks) {
        return cc_isCubic ? loadedChunks : vanillaExpectedChunks;
    }

    @WrapOperation(method = "initializeQueueForFullUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ViewArea;getRenderSection(J)"
            + "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;"))
    private @Nullable SectionRenderDispatcher.RenderSection cc_onInitializeQueueForFullUpdate_getRenderSectionAt(
            ViewArea instance, long sectionPos, Operation<SectionRenderDispatcher.RenderSection> original
    ) {
        var result = original.call(instance, sectionPos);
        if (result == null && cc_isCubic) {
            throw new IllegalStateException("getRenderSectionAt should never return null in a cubic world");
        }
        return result;
    }

    @WrapOperation(method = "runUpdates", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ChunkPos;fromSectionNode(J)J"))
    private long cc_onRunUpdates_sectionToChunk(long sectionPosLong, Operation<Long> original) {
        if (!cc_isCubic) {
            return original.call(sectionPosLong);
        }
        return CubePos.asLong(Coords.sectionToCube(SectionPos.x(sectionPosLong)), Coords.sectionToCube(SectionPos.y(sectionPosLong)),
                Coords.sectionToCube(SectionPos.z(sectionPosLong)));
    }

    @Inject(method = "isInViewDistance", at = @At("HEAD"), cancellable = true)
    private void cc_onIsInViewDistance(long originSectionPosLong, long sectionPosLong, CallbackInfoReturnable<Boolean> cir) {
        if (!cc_isCubic) {
            return;
        }
        int originCubeX = Coords.sectionToCube(SectionPos.x(originSectionPosLong));
        int originCubeY = Coords.sectionToCube(SectionPos.y(originSectionPosLong));
        int originCubeZ = Coords.sectionToCube(SectionPos.z(originSectionPosLong));
        int posCubeX = Coords.sectionToCube(SectionPos.x(sectionPosLong));
        int posCubeY = Coords.sectionToCube(SectionPos.y(sectionPosLong));
        int posCubeZ = Coords.sectionToCube(SectionPos.z(sectionPosLong));
        cir.setReturnValue(CloTrackingView.cc_isInViewDistance(originCubeX, originCubeY, originCubeZ,
                Coords.sectionToCubeRenderDistance(this.viewArea.getViewDistance()), posCubeX, posCubeY, posCubeZ));
    }

    @Inject(method = "getRelativeFrom", at = @At("HEAD"), cancellable = true)
    private void cc_onGetRelativeFrom(
            long sectionPosLong, SectionRenderDispatcher.RenderSection section, Direction direction,
            CallbackInfoReturnable<SectionRenderDispatcher.RenderSection> cir
    ) {
        if (!cc_isCubic) {
            return;
        }
        // Same as vanilla logic but we don't manually check Y coordinates since that's handled by isInViewDistance now
        long sectionPosLong2 = section.getNeighborSectionNode(direction);
        if (!this.isInViewDistance(sectionPosLong, sectionPosLong2)) {
            cir.setReturnValue(null);
        } else {
            cir.setReturnValue(((ViewAreaAccess) this.viewArea).cc_invokeGetRenderSection(sectionPosLong2));
        }
    }
}
