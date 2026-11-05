package io.github.opencubicchunks.cubicchunks.platform;

import java.nio.file.Path;
import java.util.ServiceLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Services supplied by the selected loader. Implementations have no client class references. */
public interface Platform {
    static Platform get() {
        return Provider.INSTANCE;
    }

    Path configDirectory();
    void send(ServerPlayer player, CustomPacketPayload packet);
    <T> void registerEntityDataSerializer(Identifier id, EntityDataSerializer<T> serializer);

    final class Provider {
        private static final Platform INSTANCE = ServiceLoader.load(Platform.class, Platform.class.getClassLoader()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing Cubic Chunks platform provider"));
        private Provider() {}
    }
}
