package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.level;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.DistanceManagerAccess;
import io.github.opencubicchunks.cubicchunks.server.level.CloTaskDispatcher;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ThrottlingChunkTaskDispatcher;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DistanceManager.PlayerTicketTracker.class)
public abstract class MixinPlayerTicketTracker extends MixinFixedPlayerDistanceChunkTracker {
    @SuppressWarnings("target")
    @Shadow @Final DistanceManager this$0;

    @Shadow private int viewDistance;

    @Shadow public abstract void runAllUpdates();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cc_initializeRadius(CallbackInfo ci) {
        cc_propagationLimit = viewDistance;
    }

    /** The outer shells never issue player tickets. Resume propagation at the old boundary when the view expands. */
    @Inject(method = "updateViewDistance", at = @At("HEAD"))
    private void cc_resizeTicketGraph(int distance, CallbackInfo ci) {
        int oldRadius = cc_propagationLimit;
        if (!cc_isCubic || oldRadius == distance) {
            cc_propagationLimit = distance;
            return;
        }
        // Settle pending movement with the previous radius before rechecking its boundary.
        runAllUpdates();
        cc_propagationLimit = distance;
        for (var entry : chunks.long2ByteEntrySet()) {
            int depth = entry.getByteValue();
            if (distance > oldRadius && depth == oldRadius) {
                checkNeighborsAfterUpdate(entry.getLongKey(), depth, true);
            } else if (depth > distance) {
                checkNode(entry.getLongKey());
            }
        }
    }

    /**
     * This modifies the lambda inside Distance.this.ticketDispatcher.onLevelChange to use a CloPos instead of a ChunkPos.
     */
    @WrapWithCondition(method = "runAllUpdates", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ThrottlingChunkTaskDispatcher;onLevelChange(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/IntSupplier;ILjava/util/function/IntConsumer;)V"))
    private boolean cc_onRunAllUpdates(
            ThrottlingChunkTaskDispatcher instance, ChunkPos chunkPos, IntSupplier intSupplier, int i, IntConsumer intConsumer
    ) {
        if (!cc_isCubic) {
            return true;
        }
        ((CloTaskDispatcher) ((DistanceManagerAccess) this$0).cc_ticketDispatcher()).cc_onLevelChange(CloPos.fromLong(chunkPos.pack()), intSupplier,
                i, intConsumer);
        return false;
    }
}
