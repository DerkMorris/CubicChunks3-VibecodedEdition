package io.github.opencubicchunks.cubicchunks.platform;

import java.util.ServiceLoader;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Loaded only by client code, independently of server platform services. */
public interface ClientPlatform {
    static ClientPlatform get() {
        return Provider.INSTANCE;
    }

    boolean canSend(CustomPacketPayload.Type<?> type);

    /** Wraps a play payload using the loader's wire representation. */
    default net.minecraft.network.protocol.Packet<?> outboundPacket(CustomPacketPayload payload) {
        return new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(payload);
    }

    final class Provider {
        private static final ClientPlatform INSTANCE = ServiceLoader.load(ClientPlatform.class, ClientPlatform.class.getClassLoader()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing Cubic Chunks client platform provider"));

        private Provider() {}
    }
}
