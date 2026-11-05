package io.github.opencubicchunks.cubicchunks.mixin.core.client.multiplayer;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubePlayerActionPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeSetCommandBlockPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeSetStructureBlockPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeSignUpdatePacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeUseItemOnPacket;
import io.github.opencubicchunks.cubicchunks.platform.ClientPlatform;
import io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetStructureBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class MixinClientCommonPacketListenerImpl {
    @Shadow @Final protected Minecraft minecraft;

    @ModifyVariable(method = "send", at = @At("HEAD"), argsOnly = true)
    private Packet<?> cc_encodeCubeAction(Packet<?> packet) {
        if (!((Object) this instanceof ClientPacketListener) || minecraft.level == null || !((CanBeCubic) minecraft.level).cc_isCubic()) {
            return packet;
        }
        CustomPacketPayload payload = cc_cubePayload(packet);
        if (payload == null) {
            return packet;
        }
        if (!ClientPlatform.get().canSend(payload.type())) {
            throw new IllegalStateException("Cubic player actions require a matching server protocol");
        }
        return new ServerboundCustomPayloadPacket(payload);
    }

    private static CustomPacketPayload cc_cubePayload(Packet<?> packet) {
        if (packet instanceof ServerboundPlayerActionPacket action && CCServerboundCubePlayerActionPacket.isBlockAction(action.getAction())) {
            return new CCServerboundCubePlayerActionPacket(action.getPos(), action.getAction(), action.getDirection(), action.getSequence());
        } else if (packet instanceof ServerboundUseItemOnPacket use) {
            return CCServerboundCubeUseItemOnPacket.from(use);
        } else if (packet instanceof ServerboundSignUpdatePacket sign) {
            return CCServerboundCubeSignUpdatePacket.from(sign);
        } else if (packet instanceof ServerboundSetCommandBlockPacket command) {
            return CCServerboundCubeSetCommandBlockPacket.from(command);
        } else if (packet instanceof ServerboundSetStructureBlockPacket structure) {
            return CCServerboundCubeSetStructureBlockPacket.from(structure);
        }
        return null;
    }
}
