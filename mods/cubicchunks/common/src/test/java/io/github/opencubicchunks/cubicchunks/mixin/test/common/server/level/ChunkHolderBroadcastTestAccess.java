package io.github.opencubicchunks.cubicchunks.mixin.test.common.server.level;

import java.util.BitSet;

import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.server.level.ChunkHolder;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ChunkHolder.class, priority = 900)
public interface ChunkHolderBroadcastTestAccess {
    @Dynamic("Added by MixinChunkHolder") @Invoker("cc_broadcastCubeChanges") void cc_testBroadcast(LevelCube cube);

    @Accessor("changedBlocksPerSection") ShortSet[] cc_testChangedSections();

    @Accessor("skyChangedLightSectionFilter") BitSet cc_testSkyMask();

    @Accessor("blockChangedLightSectionFilter") BitSet cc_testBlockMask();
}
