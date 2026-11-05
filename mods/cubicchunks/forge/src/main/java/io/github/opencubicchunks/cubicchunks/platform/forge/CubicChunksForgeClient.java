package io.github.opencubicchunks.cubicchunks.platform.forge;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.gui.render.pip.WorldLoadingCubeStatusesRenderer;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeLightRefresh;
import io.github.opencubicchunks.cubicchunks.network.*;
import io.github.opencubicchunks.cubicchunks.platform.*;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldMode;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterPictureInPictureRendererEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = CubicChunks.MODID, value = Dist.CLIENT)
public final class CubicChunksForgeClient {
    private static final io.github.opencubicchunks.cubicchunks.client.EntityDiagnostics DIAGNOSTICS = new io.github.opencubicchunks.cubicchunks.client.EntityDiagnostics();
    private static final net.minecraft.client.KeyMapping REPORT_KEY = new net.minecraft.client.KeyMapping("key.cubicchunks.entity_report",
            com.mojang.blaze3d.platform.InputConstants.KEY_F9, net.minecraft.client.KeyMapping.Category.MISC);

    @SubscribeEvent
    public static void keys(net.minecraftforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(REPORT_KEY);
    }

    @SubscribeEvent
    public static void itemUsed(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
        DIAGNOSTICS.itemUsed(event.getEntity(), event.getLevel(), event.getHand());
    }

    @SubscribeEvent
    public static void entityLoaded(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        DIAGNOSTICS.entityLoaded(event.getEntity(), event.getLevel());
    }

    private CubicChunksForgeClient() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        ForgeNetwork.clientHandler = CubicChunksForgeClient::receive;
    }

    private static void receive(CustomPacketPayload payload) {
        var context = new ClientPacketContext(Minecraft.getInstance(), Minecraft.getInstance().player);
        if (payload instanceof CCClientboundLevelCubeWithLightPacket packet)
            new CCClientboundLevelCubeWithLightPacket.Handler().receive(packet, context);
        else if (payload instanceof CCClientboundCubeLightInvalidatePacket packet)
            new CCClientboundCubeLightInvalidatePacket.Handler().receive(packet, context);
        else if (payload instanceof CCClientboundCubeLightRevokePacket packet)
            new CCClientboundCubeLightRevokePacket.Handler().receive(packet, context);
        else if (payload instanceof CCClientboundCubeBlockChangesPacket packet)
            new CCClientboundCubeBlockChangesPacket.Handler().receive(packet, context);
        else if (payload instanceof CCClientboundCubeOpenSignEditorPacket packet)
            new CCClientboundCubeOpenSignEditorPacket.Handler().receive(packet, context);
        else if (payload instanceof CCClientboundCubeBlockEntityDataPacket packet)
            new CCClientboundCubeBlockEntityDataPacket.Handler().receive(packet, context);
        else if (payload instanceof CCClientboundCubeBlockEventPacket packet)
            new CCClientboundCubeBlockEventPacket.Handler().receive(packet, context);
        else if (payload instanceof CCClientboundCubeLevelEventPacket packet)
            new CCClientboundCubeLevelEventPacket.Handler().receive(packet, context);
        else if (payload instanceof CCClientboundLevelChunkPacket packet)
            new CCClientboundLevelChunkPacket.Handler().receive(packet, context);
        else if (payload instanceof CCClientboundForgetLevelCloPacket packet)
            new CCClientboundForgetLevelCloPacket.Handler().receive(packet, context);
        else if (payload instanceof CCClientboundSetCubeCacheCenterPacket packet)
            new CCClientboundSetCubeCacheCenterPacket.Handler().receive(packet, context);
        else
            throw new IllegalArgumentException("Unknown cubic payload: " + payload.type());
    }

    @SubscribeEvent
    public static void renderers(RegisterPictureInPictureRendererEvent event) {
        event.register(new WorldLoadingCubeStatusesRenderer());
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent.Post event) {
        DIAGNOSTICS.sample(Minecraft.getInstance(), REPORT_KEY.consumeClick());
        ClientCubeLightRefresh.tick(Minecraft.getInstance(), () -> ClientPlatform.get().canSend(CCServerboundCubeLightRefreshPacket.TYPE),
                packet -> Minecraft.getInstance().getConnection().send(ClientPlatform.get().outboundPacket(packet)));
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        CubicWorldMode.clientOverworld = false;
    }
}
