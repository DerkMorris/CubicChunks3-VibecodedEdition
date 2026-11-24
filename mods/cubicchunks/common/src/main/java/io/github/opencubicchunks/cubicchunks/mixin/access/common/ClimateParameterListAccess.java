package io.github.opencubicchunks.cubicchunks.mixin.access.common;

import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Climate.ParameterList.class)
public interface ClimateParameterListAccess {
    @Accessor("index") Climate.RTree<?> cc_index();
}
