package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.levelgen;

import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CloHeightmap;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;

/** Vanilla heightmaps retain their implementation while satisfying the shared chunk/cube contract. */
@Mixin(Heightmap.class)
public abstract class MixinHeightmap implements CloHeightmap {}
