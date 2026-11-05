package io.github.opencubicchunks.cubicchunks.platform.neoforge;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.gui.render.pip.WorldLoadingCubeStatusesRenderer;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeLightRefresh;
import io.github.opencubicchunks.cubicchunks.network.*;
import io.github.opencubicchunks.cubicchunks.platform.*;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldMode;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

@EventBusSubscriber(modid = CubicChunks.MODID, value = Dist.CLIENT)
public final class CubicChunksNeoForgeClient {
    private static final io.github.opencubicchunks.cubicchunks.client.EntityDiagnostics DIAGNOSTICS = new io.github.opencubicchunks.cubicchunks.client.EntityDiagnostics();
    private static final net.minecraft.client.KeyMapping REPORT_KEY = new net.minecraft.client.KeyMapping("key.cubicchunks.entity_report",
            com.mojang.blaze3d.platform.InputConstants.KEY_F9, net.minecraft.client.KeyMapping.Category.MISC);

    @SubscribeEvent
    public static void keys(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(REPORT_KEY);
    }

    @SubscribeEvent
    public static void itemUsed(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
        DIAGNOSTICS.itemUsed(event.getEntity(), event.getLevel(), event.getHand());
    }

    @SubscribeEvent
    public static void entityLoaded(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        DIAGNOSTICS.entityLoaded(event.getEntity(), event.getLevel());
    }

    private CubicChunksNeoForgeClient() {}

    @SubscribeEvent
    public static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(CCClientboundWorldModePacket.TYPE, (packet, context) -> CubicWorldMode.clientOverworld = packet.cubicOverworld());
        event.register(CCClientboundLevelCubeWithLightPacket.TYPE, (packet, context) -> new CCClientboundLevelCubeWithLightPacket.Handler()
                .receive(packet, new ClientPacketContext(Minecraft.getInstance(), Minecraft.getInstance().player)));
        event.register(CCClientboundCubeLightInvalidatePacket.TYPE, (packet, context) -> new CCClientboundCubeLightInvalidatePacket.Handler()
                .receive(packet, new ClientPacketContext(Minecraft.getInstance(), Minecraft.getInstance().player)));
        event.register(CCClientboundCubeLightRevokePacket.TYPE, (packet, context) -> new CCClientboundCubeLightRevokePacket.Handler().receive(packet,
                new ClientPacketContext(Minecraft.getInstance(), Minecraft.getInstance().player)));
        event.register(CCClientboundCubeBlockChangesPacket.TYPE, (packet, context) -> new CCClientboundCubeBlockChangesPacket.Handler()
                .receive(packet, new ClientPacketContext(Minecraft.getInstance(), Minecraft.getInstance().player)));
        event.register(CCClientboundCubeOpenSignEditorPacket.TYPE, (packet, context) -> new CCClientboundCubeOpenSignEditorPacket.Handler()
                .receive(packet, new ClientPacketContext(Minecraft.getInstance(), Minecraft.getInstance().player)));
        event.register(CCClientboundCubeBlockEntityDataPacket.TYPE, (packet, context) -> new CCClientboundCubeBlockEntityDataPacket.Handler()
                .receive(packet, new ClientPacketContext(Minecraft.getInstance(), Minecraft.getInstance().player)));
        event.register(CCClientboundCubeBlockEventPacket.TYPE, (packet, context) -> new CCClientboundCubeBlockEventPacket.Handler().receive(packet,
                new ClientPacketContext(Minecraft.getInstance(), Minecraft.getInstance().player)));
        event.register(CCClientboundCubeLevelEventPacket.TYPE, (packet, context) -> new CCClientboundCubeLevelEventPacket.Handler().receive(packet,
                new ClientPacketContext(Minecraft.getInstance(), Minecraft.getInstance().player)));
        event.register(CCClientboundLevelChunkPacket.TYPE, (packet, context) -> new CCClientboundLevelChunkPacket.Handler().receive(packet,
                new ClientPacketContext(Minecraft.getInstance(), Minecraft.getInstance().player)));
        event.register(CCClientboundForgetLevelCloPacket.TYPE, (packet, context) -> new CCClientboundForgetLevelCloPacket.Handler().receive(packet,
                new ClientPacketContext(Minecraft.getInstance(), Minecraft.getInstance().player)));
        event.register(CCClientboundSetCubeCacheCenterPacket.TYPE, (packet, context) -> new CCClientboundSetCubeCacheCenterPacket.Handler()
                .receive(packet, new ClientPacketContext(Minecraft.getInstance(), Minecraft.getInstance().player)));
    }

    @SubscribeEvent
    public static void renderers(RegisterPictureInPictureRenderersEvent event) {
        event.register(io.github.opencubicchunks.cubicchunks.client.gui.render.state.pip.WorldLoadingCubeStatusesRenderState.class,
                WorldLoadingCubeStatusesRenderer::new);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        DIAGNOSTICS.sample(Minecraft.getInstance(), REPORT_KEY.consumeClick());
        ClientCubeLightRefresh.tick(Minecraft.getInstance(), () -> ClientPlatform.get().canSend(CCServerboundCubeLightRefreshPacket.TYPE),
                ClientPacketDistributor::sendToServer);
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        CubicWorldMode.clientOverworld = false;
    }
}
