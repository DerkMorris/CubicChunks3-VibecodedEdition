package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeCatSpawning;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.CatSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CatSpawner.class)
public class MixinCatSpawner {
    @Shadow private int nextTick;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cc_spawn(ServerLevel level, boolean enemies, CallbackInfo ci) {
        if (((CanBeCubic) level).cc_isCubic()) {
            nextTick = CubeCatSpawning.tick(level, nextTick);
            ci.cancel();
        }
    }
}
