package io.github.opencubicchunks.smoke.mixin;

import java.net.InetAddress;
import java.net.UnknownHostException;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.server.LanServerPinger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The two-process development probe opens a real TCP listener only on IPv4 loopback, without LAN announcements. */
@Mixin(IntegratedServer.class)
public abstract class LoopbackLanProbe {
    @ModifyArg(method = "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;I)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerConnectionListener;startTcpServerListener(Ljava/net/InetAddress;I)V"), index = 0)
    private InetAddress smokeLoopback(InetAddress original) throws UnknownHostException {
        return "host".equals(System.getProperty("cubicchunks.smoke.multiplayer")) ? InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }) : original;
    }

    @WrapOperation(method = "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;I)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/server/LanServerPinger;start()V"))
    private void smokeNoLanBroadcast(LanServerPinger pinger, Operation<Void> original) {
        if (!"host".equals(System.getProperty("cubicchunks.smoke.multiplayer")))
            original.call(pinger);
    }
}
