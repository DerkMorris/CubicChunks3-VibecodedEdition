package io.github.opencubicchunks.cubicchunks.network;

import java.util.Objects;

import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldData;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.WorldHeightSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/** World-owned settings arrive during configuration, before rendering the first play-stage frame. */
public record CCClientboundWorldHeightsPacket(WorldHeightSettings heights) implements CustomPacketPayload {
    public static final Type<CCClientboundWorldHeightsPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath("cubicchunks", "world_heights"));
    public static final StreamCodec<FriendlyByteBuf, CCClientboundWorldHeightsPacket> STREAM_CODEC = StreamCodec.of((buffer, packet) -> {
        buffer.writeInt(packet.heights.cloudHeight());
        buffer.writeInt(packet.heights.deepslateStartY());
        buffer.writeInt(packet.heights.skyFadeStartY());
    }, buffer -> new CCClientboundWorldHeightsPacket(new WorldHeightSettings(buffer.readInt(), buffer.readInt(), buffer.readInt())));

    public CCClientboundWorldHeightsPacket {
        Objects.requireNonNull(heights);
    }

    public static CCClientboundWorldHeightsPacket from(ServerLevel level) {
        return new CCClientboundWorldHeightsPacket(level.getLevelData() instanceof CubicWorldData data && data.cc_isCubicWorld()
                ? data.cc_deepCaveSettings().heights() : WorldHeightSettings.DEFAULT);
    }

    @Override public Type<CCClientboundWorldHeightsPacket> type() {
        return TYPE;
    }
}
