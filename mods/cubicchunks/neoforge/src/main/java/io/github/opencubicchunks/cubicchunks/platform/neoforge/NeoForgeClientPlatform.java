package io.github.opencubicchunks.cubicchunks.platform.neoforge;

import io.github.opencubicchunks.cubicchunks.network.*;
import io.github.opencubicchunks.cubicchunks.platform.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public final class NeoForgeClientPlatform implements ClientPlatform {
    @Override public boolean canSend(CustomPacketPayload.Type<?> type) {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(type);
    }
}
