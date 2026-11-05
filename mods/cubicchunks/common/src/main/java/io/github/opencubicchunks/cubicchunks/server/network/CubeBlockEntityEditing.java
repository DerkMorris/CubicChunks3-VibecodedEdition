package io.github.opencubicchunks.cubicchunks.server.network;

import java.util.List;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeSetCommandBlockPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeSetStructureBlockPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeSignUpdatePacket;
import io.github.opencubicchunks.cubicchunks.server.level.CCServerPlayer;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Receivers run on Fabric's server thread. Looking up an editing target never creates a holder or loads a cube. */
public final class CubeBlockEntityEditing {
    private CubeBlockEntityEditing() {}

    public static @Nullable LevelCube loadedCube(ServerPlayer player, BlockPos block) {
        if (!((CanBeCubic) player.level()).cc_isCubic() || !player.connection.hasClientLoaded() || !CubicBlockBounds.contains(block)) {
            return null;
        }
        CubePos pos = CubePos.from(block);
        if (!((CCServerPlayer) player).cc_getCloTrackingView().cc_contains(CloPos.cube(pos))) {
            return null;
        }
        var cube = ((CubeSource) player.level().getChunkSource()).cc_getCube(pos.getX(), pos.getY(), pos.getZ(), ChunkStatus.FULL, false);
        return cube instanceof LevelCube full ? full : null;
    }

    public static void handle(ServerPlayer player, CCServerboundCubeSetCommandBlockPacket packet) {
        if (loadedCube(player, packet.pos()) != null) {
            player.connection.handleSetCommandBlock(packet.vanilla());
        }
    }

    public static void handle(ServerPlayer player, CCServerboundCubeSetStructureBlockPacket packet) {
        if (loadedCube(player, packet.pos()) != null) {
            player.connection.handleSetStructureBlock(packet.vanilla());
        }
    }

    public static void handle(ServerPlayer player, CCServerboundCubeSignUpdatePacket packet) {
        LevelCube cube = loadedCube(player, packet.pos());
        if (cube == null || !(cube.getBlockEntity(packet.pos()) instanceof SignBlockEntity sign)) {
            return;
        }
        var level = player.level();
        var connection = player.connection;
        List<String> lines = packet.lines().stream().map(ChatFormatting::stripFormatting).toList();
        // Match vanilla's filtering and sign authorization, but bind the asynchronous result to its original world and entity.
        player.getTextFilter().processMessageBundle(lines).thenAcceptAsync(filtered -> {
            if (player.level() != level || player.connection != connection || !connection.isAcceptingMessages()) {
                return;
            }
            if (loadedCube(player, packet.pos()) != cube || cube.getBlockEntities().get(packet.pos()) != sign) {
                return;
            }
            player.resetLastActionTime();
            sign.updateSignText(player, packet.slot(), filtered);
        }, level.getServer());
    }
}
