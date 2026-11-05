package io.github.opencubicchunks.cubicchunks.server.network;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubePlayerActionPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeUseItemOnPacket;
import io.github.opencubicchunks.cubicchunks.server.level.CCServerPlayer;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Fabric runs these on the server thread. Reuse vanilla authorization and acknowledgement logic after a no-load gate. */
public final class CubePlayerActions {
    private CubePlayerActions() {}

    public static void handle(ServerPlayer player, CCServerboundCubePlayerActionPacket packet) {
        if (!((CanBeCubic) player.level()).cc_isCubic() || !player.connection.hasClientLoaded()) {
            return;
        }
        if (isAvailable(player, packet.pos())) {
            player.connection.handlePlayerAction(packet.vanilla());
        } else {
            player.connection.ackBlockChangesUpTo(packet.sequence());
        }
    }

    public static void handle(ServerPlayer player, CCServerboundCubeUseItemOnPacket packet) {
        if (!((CanBeCubic) player.level()).cc_isCubic() || !player.connection.hasClientLoaded()) {
            return;
        }
        BlockPos adjacent = packet.pos().relative(packet.direction());
        if (isAvailable(player, packet.pos()) && (!CubicBlockBounds.contains(adjacent) || isAvailable(player, adjacent))) {
            player.connection.handleUseItemOn(packet.vanilla());
        } else {
            player.connection.ackBlockChangesUpTo(packet.sequence());
        }
    }

    private static boolean isAvailable(ServerPlayer player, BlockPos block) {
        CubePos pos = CubePos.from(block);
        if (!((CCServerPlayer) player).cc_getCloTrackingView().cc_contains(CloPos.cube(pos))) {
            return false;
        }
        return ((CubeSource) player.level().getChunkSource()).cc_getCube(pos.getX(), pos.getY(), pos.getZ(), ChunkStatus.FULL, false) != null;
    }
}
