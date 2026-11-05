package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubePatrolSpawning;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PatrolSpawner.class)
public class MixinPatrolSpawner {
    @Shadow private int nextTick;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cc_spawn(ServerLevel level, boolean enemies, CallbackInfo ci) {
        if (((CanBeCubic) level).cc_isCubic()) {
            nextTick = CubePatrolSpawning.tick(level, enemies, nextTick);
            ci.cancel();
        }
    }
}
