package io.github.opencubicchunks.cubicchunks.mixin.test.common.server.level;

import io.github.opencubicchunks.cubicchunks.world.level.chunklike.LevelClo;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ChunkMap.class, priority = 900)
public interface ChunkMapSendingTestAccess {
    @Dynamic("Added by MixinChunkMap") @Invoker("cc_onChunkReadyToSend") void cc_testReadyToSend(ChunkHolder holder, LevelClo clo);
}
