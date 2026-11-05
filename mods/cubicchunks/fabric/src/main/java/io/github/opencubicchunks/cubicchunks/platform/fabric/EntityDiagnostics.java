package io.github.opencubicchunks.cubicchunks.platform.fabric;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.server.network.CubeEntityTrackingSender;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;

final class EntityDiagnostics {
    private static final int HISTORY_SIZE = 120;
    private static final int SAMPLE_INTERVAL = 5;
    private static final int SERVER_TIMEOUT_SECONDS = 5;
    private static final int RADIUS = 96;
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private ClientLevel world;
    private int ticks;
    private boolean writing;

    void register() {
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level == world && player.getItemInHand(hand).is(net.minecraft.world.item.Items.FIREWORK_ROCKET)) {
                record("rocket use hand=" + hand + " gliding=" + player.isFallFlying() + " velocity=" + player.getDeltaMovement());
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (level == world && entity instanceof FireworkRocketEntity) {
                record("rocket arrived " + describe(entity));
            }
        });
        var key = KeyMappingHelper
                .registerKeyMapping(new KeyMapping("key.cubicchunks.entity_report", InputConstants.KEY_F9, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> sample(client, key.consumeClick()));
    }

    private void sample(Minecraft client, boolean capture) {
        if (world != client.level) {
            history.clear();
            world = client.level;
        }

        if (world == null || client.player == null || !((CanBeCubic) world).cc_isCubic()) {
            return;
        }
        if (++ticks % SAMPLE_INTERVAL == 0 || capture) {
            record("player=" + client.player.position() + " velocity=" + client.player.getDeltaMovement() + " gliding=" + client.player.isFallFlying()
                    + " hand=" + client.player.getMainHandItem().getItem());
        }
        if (capture && !writing) {
            save(client);
        }
    }

    private void record(String event) {
        if (history.size() >= HISTORY_SIZE) {
            history.removeFirst();
        }
        history.addLast(Instant.now() + " " + event);
    }

    private void save(Minecraft client) {
        StringBuilder output = new StringBuilder(String.join("\n", history)).append("\nCLIENT ").append(world.dimension()).append('\n');
        var cache = (ClientCubeCache) world.getChunkSource();
        for (var entity : world.entitiesForRendering()) {
            if (near(entity, client.player)) {
                var pos = CubePos.from(entity.blockPosition());
                output.append(describe(entity)).append(" ticking=").append(world.isTickingEntity(entity)).append(" resident=")
                        .append(cache.cc_getCube(pos.getX(), pos.getY(), pos.getZ(), false) != null).append(" mesh=")
                        .append(client.levelRenderer.isSectionCompiledAndVisible(entity.blockPosition(), 0)).append('\n');
            }
        }
        var server = client.getSingleplayerServer();
        var id = client.player.getUUID();
        var dimension = world.dimension();
        var snapshot = server == null ? CompletableFuture.completedFuture("remote server") : server.submit(() -> {
            var player = server.getPlayerList().getPlayer(id);
            return player != null && player.level().dimension().equals(dimension) ? serverSnapshot(player) : "player changed world";
        });
        Path directory = client.gameDirectory.toPath().resolve("logs/cubicchunks");
        writing = true;
        snapshot.completeOnTimeout("server sample timed out", SERVER_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS).thenApplyAsync(data -> {
            try {
                Files.createDirectories(directory);
                return Files.writeString(directory.resolve("entities-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".txt"),
                        output + "\nSERVER\n" + data);
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }).whenComplete((path, failure) -> client.execute(() -> {
            writing = false;
            client.gui.chatListener()
                    .handleSystemMessage(failure == null ? Component.translatable("cubicchunks.report.saved", path.getFileName().toString())
                            : Component.translatable("cubicchunks.report.failed", failure.toString()), false);
        }));
    }

    private static String serverSnapshot(ServerPlayer player) {
        var level = player.level();
        var paired = new java.util.HashSet<Integer>();
        level.getChunkSource().chunkMap.forEachEntityTrackedBy(player, entity -> paired.add(entity.getId()));
        var output = new StringBuilder().append(Instant.now()).append(" player=").append(player.position()).append(" velocity=")
                .append(player.getDeltaMovement()).append(" gliding=").append(player.isFallFlying()).append('\n');
        for (Entity entity : level.getAllEntities()) {
            if (near(entity, player)) {
                var pos = CubePos.from(entity.blockPosition());
                output.append(describe(entity)).append(" paired=").append(paired.contains(entity.getId())).append(" sentCube=")
                        .append(((CubeEntityTrackingSender) player.connection.chunkSender).cc_hasSentEntityCube(level, pos)).append('\n');
            }
        }
        return output.toString();
    }

    private static boolean near(Entity entity, Entity player) {
        return (entity instanceof Mob || entity instanceof FireworkRocketEntity) && entity.distanceToSqr(player) <= RADIUS * RADIUS;
    }

    private static String describe(Entity entity) {
        return entity.getType() + " id=" + entity.getId() + " pos=" + entity.position() + " velocity=" + entity.getDeltaMovement() + " age="
                + entity.tickCount + " invisible=" + entity.isInvisible();
    }
}
