package io.github.opencubicchunks.cubicchunks.platform.forge;

import java.nio.file.Path;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.network.*;
import io.github.opencubicchunks.cubicchunks.platform.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public final class ForgePlatform implements Platform {
    @Override public net.minecraft.network.protocol.Packet<?> outboundPacket(CustomPacketPayload payload) {
        return new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                net.minecraftforge.network.ForgePayload.create(payload.type().id(), buffer -> ForgeNetwork.CHANNEL.encode(buffer, payload)));
    }

    static final DeferredRegister<EntityDataSerializer<?>> SERIALIZERS = DeferredRegister.create(ForgeRegistries.ENTITY_DATA_SERIALIZERS,
            CubicChunks.MODID);

    @Override public Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override public void send(ServerPlayer player, CustomPacketPayload packet) {
        ForgeNetwork.CHANNEL.send(packet, PacketDistributor.PLAYER.with(player));
    }

    @Override public <T> void registerEntityDataSerializer(Identifier id, EntityDataSerializer<T> serializer) {
        if (!CubicChunks.MODID.equals(id.getNamespace()))
            throw new IllegalArgumentException("Foreign serializer: " + id);
        SERIALIZERS.register(id.getPath(), () -> serializer);
    }
}
