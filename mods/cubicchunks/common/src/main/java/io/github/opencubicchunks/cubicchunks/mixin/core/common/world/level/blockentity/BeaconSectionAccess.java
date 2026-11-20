package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.blockentity;

import net.minecraft.world.level.block.entity.BeaconBeamOwner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BeaconBeamOwner.Section.class)
public interface BeaconSectionAccess {
    @Accessor("height") void cc_setHeight(int height);
}
