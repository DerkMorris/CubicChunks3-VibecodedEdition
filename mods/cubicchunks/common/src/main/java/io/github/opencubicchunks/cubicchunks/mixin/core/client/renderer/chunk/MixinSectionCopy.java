package io.github.opencubicchunks.cubicchunks.mixin.core.client.renderer.chunk;

import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddTransformToSets;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.notstirred.dasm.api.annotations.transform.TransformFromMethod;
import io.github.opencubicchunks.cubicchunks.exception.DasmFailedToApply;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ClientChunkToCubeSet;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The vanilla {@link SectionCopy} stores block data of a {@link LevelChunkSection} and is used for rendering that section. We simply add a
 * constructor that creates the {@code SectionCopy} from a cube instead of a chunk.
 */
@Dasm(value = ClientChunkToCubeSet.class, target = @Ref(SectionCopy.class))
@Mixin(SectionCopy.class)
public class MixinSectionCopy {
    // Vanilla SectionCopy copies the section data and does not retain a wrapped LevelChunk.
    // Its constructor can therefore be redirected without NeoForge's extra wrapped field.

    @AddTransformToSets(ClientChunkToCubeSet.SectionCopy_redirects.class)
    @TransformFromMethod("<init>(Lnet/minecraft/world/level/chunk/LevelChunk;I)V")
    public MixinSectionCopy(LevelCube wrapped, int sectionIndex) {
        throw new DasmFailedToApply();
    }
}
