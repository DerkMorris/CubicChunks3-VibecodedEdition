package io.github.opencubicchunks.cubicchunks.platform.fabric;

import java.nio.file.Path;

import io.github.opencubicchunks.cubicchunks.platform.Platform;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityDataRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

public final class FabricPlatform implements Platform {
    @Override public Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override public void send(ServerPlayer player, CustomPacketPayload packet) {
        ServerPlayNetworking.send(player, packet);
    }

    @Override public <T> void registerEntityDataSerializer(Identifier id, EntityDataSerializer<T> serializer) {
        if (FabricEntityDataRegistry.get(id) == null) {
            FabricEntityDataRegistry.register(id, serializer);
        }
    }
}
