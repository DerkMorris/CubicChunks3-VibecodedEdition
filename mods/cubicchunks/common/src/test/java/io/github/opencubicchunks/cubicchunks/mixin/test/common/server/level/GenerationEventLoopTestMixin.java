package io.github.opencubicchunks.cubicchunks.mixin.test.common.server.level;

import io.github.opencubicchunks.cubicchunks.testutils.GenerationTestScope;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockableEventLoop.class)
public abstract class GenerationEventLoopTestMixin {
    @Inject(method = "pollTask", at = @At("HEAD"))
    private void cc_testCheckProgress(CallbackInfoReturnable<Boolean> cir) {
        GenerationTestScope.checkProgress();
    }
}
