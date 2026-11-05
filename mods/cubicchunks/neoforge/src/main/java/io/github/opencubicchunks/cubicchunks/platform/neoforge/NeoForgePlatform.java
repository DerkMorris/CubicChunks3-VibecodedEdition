package io.github.opencubicchunks.cubicchunks.platform.neoforge;

import java.nio.file.Path;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.network.*;
import io.github.opencubicchunks.cubicchunks.platform.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class NeoForgePlatform implements Platform {
    static final DeferredRegister<EntityDataSerializer<?>> SERIALIZERS = DeferredRegister.create(NeoForgeRegistries.ENTITY_DATA_SERIALIZERS,
            CubicChunks.MODID);

    @Override public Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override public void send(ServerPlayer player, CustomPacketPayload packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }

    @Override public <T> void registerEntityDataSerializer(Identifier id, EntityDataSerializer<T> serializer) {
        if (!CubicChunks.MODID.equals(id.getNamespace()))
            throw new IllegalArgumentException("Foreign serializer: " + id);
        SERIALIZERS.register(id.getPath(), () -> serializer);
    }
}
