package io.github.opencubicchunks.cubicchunks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.server.network.CubeBlockEntityEditing;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.platform.Platform;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.SignTextSlot;

public record CCServerboundCubeSignUpdatePacket(BlockPos pos, List<String> lines, SignTextSlot slot) implements CustomPacketPayload {

    public static final Type<CCServerboundCubeSignUpdatePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "cube_sign_update"));
    public static final StreamCodec<FriendlyByteBuf, CCServerboundCubeSignUpdatePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override public CCServerboundCubeSignUpdatePacket decode(FriendlyByteBuf buffer) {
            BlockPos pos = CubeActionCodec.readPosition(buffer);
            List<String> lines = new ArrayList<>(CubeEditCodec.SIGN_LINES);
            for (int i = 0; i < CubeEditCodec.SIGN_LINES; i++) {
                lines.add(buffer.readUtf(CubeEditCodec.SIGN_LINE_LENGTH));
            }
            return new CCServerboundCubeSignUpdatePacket(pos, lines, CubeActionCodec.readEnum(buffer, SignTextSlot.values()));
        }

        @Override public void encode(FriendlyByteBuf buffer, CCServerboundCubeSignUpdatePacket packet) {
            CubeActionCodec.writePosition(buffer, packet.pos);
            packet.lines.forEach(line -> buffer.writeUtf(line, CubeEditCodec.SIGN_LINE_LENGTH));
            buffer.writeEnum(packet.slot);
        }
    };

    public CCServerboundCubeSignUpdatePacket {
        pos = CubicBlockBounds.requireValid(pos);
        lines = List.copyOf(lines);
        if (lines.size() != CubeEditCodec.SIGN_LINES) {
            throw new IllegalArgumentException("A sign must have four lines");
        }
        lines.forEach(line -> CubeEditCodec.string(line, CubeEditCodec.SIGN_LINE_LENGTH));
        Objects.requireNonNull(slot, "slot");
    }

    public static CCServerboundCubeSignUpdatePacket from(ServerboundSignUpdatePacket packet) {
        return new CCServerboundCubeSignUpdatePacket(packet.pos(), packet.lines(), packet.slot());
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class Handler {
        public void receive(CCServerboundCubeSignUpdatePacket packet, net.minecraft.server.level.ServerPlayer player) {
            CubeBlockEntityEditing.handle(player, packet);
        }
    }
}
