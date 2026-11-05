package io.github.opencubicchunks.cubicchunks.platform.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.InteractionResult;

final class EntityDiagnostics {
    void register() {
        var diagnostics = new io.github.opencubicchunks.cubicchunks.client.EntityDiagnostics();
        UseItemCallback.EVENT.register((player, level, hand) -> {
            diagnostics.itemUsed(player, level, hand);
            return InteractionResult.PASS;
        });
        ClientEntityEvents.ENTITY_LOAD.register(diagnostics::entityLoaded);
        var key = KeyMappingHelper
                .registerKeyMapping(new KeyMapping("key.cubicchunks.entity_report", InputConstants.KEY_F9, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> diagnostics.sample(client, key.consumeClick()));
    }
}
