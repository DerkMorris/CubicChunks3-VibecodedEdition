package io.github.opencubicchunks.cubicchunks.mixin.core.client;

import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.MarkableAsCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevel;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Keeps extraction state aligned with the moving ViewArea and extracts a section as soon as its own cube is resident. */
@Mixin(SectionUpdateTracker.class)
public abstract class MixinSectionUpdateTracker {
    @Shadow @Final private RotatingSectionStorage<SectionUpdateTracker.SectionDirtyState> storage;

    @Shadow public abstract boolean hasAllNeighbors(ClientLevel level, long sectionNode);

    @Unique
    private ClientLevel cc_level;

    @ModifyArgs(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/RotatingSectionStorage;<init>(IIILnet/minecraft/client/RotatingSectionStorage$ValueCreator;)V"))
    private void cc_boundVerticalStorage(Args args, LevelHeightAccessor level, int viewDistance) {
        if (level instanceof CanBeCubic cubic && cubic.cc_isCubic()) {
            int radius = args.get(0);
            args.set(1, -radius);
            args.set(2, radius);
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cc_initializeStorage(LevelHeightAccessor level, int viewDistance, CallbackInfo ci) {
        if (level instanceof CanBeCubic cubic && cubic.cc_isCubic()) {
            ((MarkableAsCubic) storage).cc_setCubic();
            if (level instanceof ClientLevel clientLevel) {
                cc_level = clientLevel;
            }
        }
    }

    @Inject(method = "getDirtyState", at = @At("RETURN"), cancellable = true)
    private void cc_deferUnreadySection(long sectionNode, CallbackInfoReturnable<SectionUpdateTracker.SectionDirtyState> cir) {
        var dirtyState = cir.getReturnValue();
        if (cc_level != null && dirtyState != null && dirtyState.isDirty() && !hasAllNeighbors(cc_level, sectionNode)) {
            // Vanilla only checks neighbors for an uncompiled mesh. Rebuilds must retain their dirty state as well.
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "hasAllNeighbors", at = @At("HEAD"), cancellable = true)
    private void cc_requireResidentCube(ClientLevel level, long sectionNode, CallbackInfoReturnable<Boolean> cir) {
        if (!((CanBeCubic) level).cc_isCubic()) {
            return;
        }
        // The render region uses an empty section copy for missing neighbors. Their arrival dirties the boundary again.
        cir.setReturnValue(((CubicLevel) level).cc_getCube(Coords.sectionToCube(SectionPos.x(sectionNode)),
                Coords.sectionToCube(SectionPos.y(sectionNode)), Coords.sectionToCube(SectionPos.z(sectionNode)), ChunkStatus.FULL, false) != null);
    }
}
