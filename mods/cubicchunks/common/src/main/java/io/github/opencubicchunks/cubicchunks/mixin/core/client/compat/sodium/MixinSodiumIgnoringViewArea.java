package io.github.opencubicchunks.cubicchunks.mixin.core.client.compat.sodium;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Sodium's placeholder has no vanilla sections and must not start the cubic vanilla graph. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.util.IgnoringViewArea", remap = false)
public abstract class MixinSodiumIgnoringViewArea implements CanBeCubic {
    @Override public boolean cc_isCubic() {
        return false;
    }
}
