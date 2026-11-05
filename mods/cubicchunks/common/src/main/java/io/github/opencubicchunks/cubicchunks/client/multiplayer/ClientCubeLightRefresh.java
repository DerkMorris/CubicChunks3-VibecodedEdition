package io.github.opencubicchunks.cubicchunks.client.multiplayer;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeLightRefreshPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** Per-tick transport adapter; recovery state belongs to the current level's cache, never a static client-world reference. */

public final class ClientCubeLightRefresh {
    private ClientCubeLightRefresh() {}

    public static void tick(Minecraft client, BooleanSupplier canSend, Consumer<CCServerboundCubeLightRefreshPacket> send) {
        ClientLevel level = client.level;
        if (level == null || client.getConnection() == null || !((CanBeCubic) level).cc_isCubic() || !canSend.getAsBoolean()) {
            return;
        }
        if (level.getChunkSource() instanceof ClientCubeCache cache) {
            CCServerboundCubeLightRefreshPacket request = cache.cc_pollCubeLightRefresh();
            if (request != null && client.level == level) {
                send.accept(request);
            }
        }
    }
}
