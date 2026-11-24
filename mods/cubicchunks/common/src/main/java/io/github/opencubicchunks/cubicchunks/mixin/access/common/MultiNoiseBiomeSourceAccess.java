package io.github.opencubicchunks.cubicchunks.mixin.access.common;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MultiNoiseBiomeSource.class)
public interface MultiNoiseBiomeSourceAccess {
    @Invoker("parameters") Climate.ParameterList<Holder<Biome>> cc_parameters();
}
