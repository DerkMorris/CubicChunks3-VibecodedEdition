package io.github.opencubicchunks.smoke.mixin;

import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubeWithLightPacket;
import io.github.opencubicchunks.smoke.WorldLightPacketChecks;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CCClientboundLevelCubeWithLightPacket.Handler.class, remap = false)
public class InitialCubeLightProbe {
    @Unique private boolean initial;

    @Inject(method = "receive(Lio/github/opencubicchunks/cubicchunks/network/CCClientboundLevelCubeWithLightPacket;Lio/github/opencubicchunks/cubicchunks/platform/ClientPacketContext;)V", at = @At("HEAD"))
    private void before(CCClientboundLevelCubeWithLightPacket packet, io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext context, CallbackInfo ci) {
        var cache = (ClientCubeCache) context.client().level.getChunkSource();
        initial = cache.cc_getCube(packet.pos().getX(), packet.pos().getY(), packet.pos().getZ(), false) == null;
    }

    @Inject(method = "receive(Lio/github/opencubicchunks/cubicchunks/network/CCClientboundLevelCubeWithLightPacket;Lio/github/opencubicchunks/cubicchunks/platform/ClientPacketContext;)V", at = @At("RETURN"))
    private void after(CCClientboundLevelCubeWithLightPacket packet, io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext context, CallbackInfo ci) {
        if (!initial || !Boolean.getBoolean("cubicchunks.smoke.lighting")) {
            return;
        }
        var cache = (ClientCubeCache) context.client().level.getChunkSource();
        if (cache.cc_getCube(packet.pos().getX(), packet.pos().getY(), packet.pos().getZ(), false) == null) {
            return;
        }
        if (packet.light() == null || !cache.cc_isCubeLightReady(packet.pos()) || cache.cc_getVisibleCubeLightView(packet.pos()) == null) {
            throw new IllegalStateException("First cube packet did not install exact light before rendering: " + packet.pos());
        }
        WorldLightPacketChecks.initialPackets++;
    }
}
