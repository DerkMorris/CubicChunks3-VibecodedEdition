package io.github.opencubicchunks.cubicchunks.mixin.access.client;

import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockStatePredictionHandler.class)
public interface BlockStatePredictionHandlerAccess {
    @Accessor("lastTeleportSequence") int cc_lastTeleportSequence();
}
