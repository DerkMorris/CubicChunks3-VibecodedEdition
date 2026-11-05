package io.github.opencubicchunks.cubicchunks.mixin.test.common.server.level;

import io.github.opencubicchunks.cubicchunks.testutils.GenerationTestScope;
import net.minecraft.CrashReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CrashReport.class)
public abstract class GenerationCrashReportTestMixin {
    @Inject(method = "forThrowable", at = @At("HEAD"))
    private static void cc_testCaptureCause(Throwable cause, String description, CallbackInfoReturnable<CrashReport> cir) {
        GenerationTestScope.failOnCrashReport(cause, description);
    }
}
