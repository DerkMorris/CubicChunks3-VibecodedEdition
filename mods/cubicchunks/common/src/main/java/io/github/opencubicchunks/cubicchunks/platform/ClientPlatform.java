package io.github.opencubicchunks.cubicchunks.platform;

import java.util.ServiceLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Loaded only by client code, independently of server platform services. */
public interface ClientPlatform {
    static ClientPlatform get() {
        return Provider.INSTANCE;
    }

    boolean canSend(CustomPacketPayload.Type<?> type);

    final class Provider {
        private static final ClientPlatform INSTANCE = ServiceLoader.load(ClientPlatform.class, ClientPlatform.class.getClassLoader()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing Cubic Chunks client platform provider"));
        private Provider() {}
    }
}
