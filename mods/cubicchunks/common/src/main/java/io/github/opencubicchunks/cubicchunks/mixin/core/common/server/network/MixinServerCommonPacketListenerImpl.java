package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.network;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockChangesPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockEntityDataPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockEventPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeLevelEventPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeOpenSignEditorPacket;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.netty.channel.ChannelFutureListener;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;

/** Corrections sent directly by vanilla game mode must use the same coordinates as cubic broadcasts. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class MixinServerCommonPacketListenerImpl {
    @WrapMethod(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V")
    private void cc_encodeCubeCorrection(Packet<?> packet, ChannelFutureListener listener, Operation<Void> original) {
        if (!((Object) this instanceof ServerGamePacketListenerImpl game) || !((CanBeCubic) game.player.level()).cc_isCubic()) {
            original.call(packet, listener);
            return;
        }
        if (packet instanceof ClientboundBlockUpdatePacket update) {
            BlockPos pos = update.getPos();
            // A placement at the last supported block may also correct its out-of-range neighbor; no cube can contain that neighbor.
            if (!CubicBlockBounds.contains(pos)) {
                return;
            }
            original.call(new ClientboundCustomPayloadPacket(cc_correction(game, update)), listener);
            return;
        }
        if (packet instanceof ClientboundOpenSignEditorPacket sign) {
            original.call(new ClientboundCustomPayloadPacket(new CCClientboundCubeOpenSignEditorPacket(sign.pos(), sign.slot())), listener);
            return;
        }
        if (packet instanceof ClientboundBlockEntityDataPacket entity) {
            original.call(new ClientboundCustomPayloadPacket(CCClientboundCubeBlockEntityDataPacket.from(entity)), listener);
            return;
        }
        cc_sendEvent(packet, listener, original);
    }

    private static void cc_sendEvent(Packet<?> packet, ChannelFutureListener listener, Operation<Void> original) {
        if (packet instanceof ClientboundBlockEventPacket event) {
            if (CubicBlockBounds.contains(event.getPos())) {
                original.call(new ClientboundCustomPayloadPacket(CCClientboundCubeBlockEventPacket.from(event)), listener);
            }
        } else if (packet instanceof ClientboundLevelEventPacket event) {
            if (CubicBlockBounds.contains(event.getPos())) {
                original.call(new ClientboundCustomPayloadPacket(CCClientboundCubeLevelEventPacket.from(event)), listener);
            }
        } else {
            original.call(packet, listener);
        }
    }

    private static CCClientboundCubeBlockChangesPacket cc_correction(ServerGamePacketListenerImpl game, ClientboundBlockUpdatePacket update) {
        BlockPos pos = update.getPos();
        CubePos cubePos = CubePos.from(pos);
        var cube = ((CubeSource) game.player.level().getChunkSource()).cc_getCube(cubePos.getX(), cubePos.getY(), cubePos.getZ(), ChunkStatus.FULL,
                false);
        if (cube instanceof LevelCube full && full.getBlockState(pos) == update.getBlockState()) {
            return CCClientboundCubeBlockChangesPacket.capture(full, Coords.blockToIndex(pos),
                    new ShortOpenHashSet(new short[] { SectionPos.sectionRelativePos(pos) }));
        }
        var change = new CCClientboundCubeBlockChangesPacket.Change(SectionPos.sectionRelativePos(pos), update.getBlockState(), null);
        return new CCClientboundCubeBlockChangesPacket(cubePos, Coords.blockToIndex(pos), List.of(change));
    }
}
