package io.github.opencubicchunks.cubicchunks.mixin.core.client.compat.sodium;

import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevel;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSection;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A missing neighbor remains an empty snapshot until its arrival invalidates the surrounding sections. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache", remap = false)
public abstract class MixinClonedChunkSectionCache {
    @Shadow @Final private Level level;

    @Inject(method = "clone", at = @At("HEAD"), cancellable = true)
    private void cc_cloneCubeSection(int x, int y, int z, CallbackInfoReturnable<ClonedChunkSection> cir) {
        if (((CanBeCubic) level).cc_isCubic()) {
            var cube = ((CubicLevel) level).cc_getCube(Coords.sectionToCube(x), Coords.sectionToCube(y), Coords.sectionToCube(z),
                    ChunkStatus.FULL, false);
            var section = cube == null ? null : cube.getSection(Coords.sectionToIndex(x, y, z));
            // Sodium's platform services still receive their normal column context. Blocks and entities come from the cube.
            cir.setReturnValue(new ClonedChunkSection(level, level.getChunk(x, z), section, SectionPos.of(x, y, z)));
        }
    }
}
