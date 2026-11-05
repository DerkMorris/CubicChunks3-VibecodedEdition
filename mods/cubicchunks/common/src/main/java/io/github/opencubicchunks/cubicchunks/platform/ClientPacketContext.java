package io.github.opencubicchunks.cubicchunks.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** The loader dispatches play payloads on the client thread before constructing this context. */
public record ClientPacketContext(Minecraft client, LocalPlayer player) {}
