package io.github.opencubicchunks.cubicchunks.server.network;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubeWithLightPacket;
import io.github.opencubicchunks.cubicchunks.server.level.CubicChunkMap;
import io.github.opencubicchunks.cubicchunks.platform.Platform;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Records actual cube payload sends, including the dimension, before entity spawn pairing is allowed. */
public interface CubeEntityTrackingSender {
    /** Both the initial sender and lighting publications deliver the complete cube body. */
    static void sendFullCube(
            ServerPlayer player, ServerLevel level, CubePos pos, CCClientboundLevelCubeWithLightPacket packet, boolean initialBatch
    ) {
        Platform.get().send(player, packet);
        var sender = (CubeEntityTrackingSender) player.connection.chunkSender;
        if (initialBatch || !sender.cc_hasSentEntityCube(level, pos)) {
            sender.cc_markEntityCubeSent(level, pos);
            ((CubicChunkMap) level.getChunkSource().chunkMap).cc_onCubeTrackingChange(player, pos, true);
        }
    }

    boolean cc_hasSentEntityCube(ServerLevel level, CubePos pos);

    void cc_markEntityCubeSent(ServerLevel level, CubePos pos);
}
