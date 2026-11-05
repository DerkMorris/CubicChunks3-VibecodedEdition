package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level;

import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddFieldToSets;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ChunkToCubeSet;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.GlobalSet;
import io.github.opencubicchunks.cubicchunks.platform.neoforge.CubeAuxiliaryLightManager;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import org.spongepowered.asm.mixin.Mixin;

@Dasm(value = GlobalSet.class, target = @Ref(LevelCube.class))
@Mixin(LevelCube.class)
public class MixinLevelCubeAuxiliaryLight {
    @AddFieldToSets(containers = ChunkToCubeSet.LevelChunk_to_LevelCube_redirects.class, field = "auxLightManager:Lnet/neoforged/neoforge/common/world/LevelChunkAuxiliaryLightManager;")
    private final CubeAuxiliaryLightManager cc_auxLightManager = new CubeAuxiliaryLightManager((LevelCube) (Object) this);
}
