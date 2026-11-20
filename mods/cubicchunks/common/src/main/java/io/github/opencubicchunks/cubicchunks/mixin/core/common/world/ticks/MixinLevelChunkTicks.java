package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.ticks;

import java.util.Iterator;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.world.ticks.CubicTickContainer;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Queue changes must participate in the cube's normal asynchronous-save dirty state. */
@Mixin(LevelChunkTicks.class)
public abstract class MixinLevelChunkTicks<T> implements CubicTickContainer {
    @Unique
    private Runnable cc_unsavedListener;

    @Override public void cc_setUnsavedListener(Runnable listener) {
        this.cc_unsavedListener = listener;
    }

    @Override public void cc_markUnsaved() {
        if (cc_unsavedListener != null) {
            cc_unsavedListener.run();
        }
    }

    @Inject(method = "scheduleUnchecked", at = @At("RETURN"))
    private void cc_dirtyAfterInsertion(ScheduledTick<T> tick, CallbackInfo ci) {
        cc_markUnsaved();
    }

    @Inject(method = "poll", at = @At("RETURN"))
    private void cc_dirtyAfterRemoval(CallbackInfoReturnable<ScheduledTick<T>> cir) {
        if (cir.getReturnValue() != null) {
            cc_markUnsaved();
        }
    }

    @WrapOperation(method = "removeIf", at = @At(value = "INVOKE", target = "Ljava/util/Iterator;remove()V"))
    private void cc_dirtyAfterAreaRemoval(Iterator<?> iterator, Operation<Void> original) {
        original.call(iterator);
        cc_markUnsaved();
    }
}
