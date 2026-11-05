package io.github.opencubicchunks.cubicchunks.platform.forge;

import io.github.opencubicchunks.cubicchunks.network.*;
import io.github.opencubicchunks.cubicchunks.platform.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraftforge.network.ForgePayload;

public final class ForgeClientPlatform implements ClientPlatform {
    @Override public boolean canSend(CustomPacketPayload.Type<?> type) {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null
                && (connection.getConnection().isMemoryConnection() || ForgeNetwork.CHANNEL.isRemotePresent(connection.getConnection()));
    }

    @Override public Packet<?> outboundPacket(CustomPacketPayload payload) {
        return new ServerboundCustomPayloadPacket(ForgePayload.create(payload.type().id(), buffer -> ForgeNetwork.CHANNEL.encode(buffer, payload)));
    }
}
