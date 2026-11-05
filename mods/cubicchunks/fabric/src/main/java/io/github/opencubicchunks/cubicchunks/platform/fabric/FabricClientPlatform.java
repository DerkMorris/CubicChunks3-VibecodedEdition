package io.github.opencubicchunks.cubicchunks.platform.fabric;

import io.github.opencubicchunks.cubicchunks.platform.ClientPlatform;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public final class FabricClientPlatform implements ClientPlatform {
    @Override public boolean canSend(CustomPacketPayload.Type<?> type) {
        return ClientPlayNetworking.canSend(type);
    }
}
