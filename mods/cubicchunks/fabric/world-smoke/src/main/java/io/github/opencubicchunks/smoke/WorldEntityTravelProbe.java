package io.github.opencubicchunks.smoke;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.server.level.CubicChunkMap;
import io.github.opencubicchunks.cubicchunks.server.network.CubeEntityTrackingSender;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Real movement, entity pairing and item use after returning from a deep cube. */
final class WorldEntityTravelProbe {
    private final List<Mob> mobs = new ArrayList<>();
    private volatile List<State> states = List.of();
    private volatile int rocketId = -1;
    private volatile int rocketAge;
    private volatile boolean serverGliding;
    private CompletableFuture<Void> work;
    private int phase;
    private int ticks;
    private int visibleTicks;
    private boolean sawRocket;
    private double maxSpeed;

    WorldEntityTravelProbe() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getPlayerList().getPlayers().isEmpty()) { return; }
            var player = server.getPlayerList().getPlayers().getFirst();
            var level = player.level();
            var map = level.getChunkSource().chunkMap;
            states = mobs.stream().map(mob -> {
                CubePos pos = CubePos.from(mob.blockPosition());
                return new State(mob.getId(), mob.isRemoved(), map.isTrackedByAnyPlayer(mob),
                        ((CubeEntityTrackingSender) player.connection.chunkSender).cc_hasSentEntityCube(level, pos),
                        ((CubicChunkMap) map).cc_isChunkTracked(player, pos.getX(), pos.getY(), pos.getZ()));
            }).toList();
            serverGliding = player.isFallFlying();
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof FireworkRocketEntity) { rocketId = entity.getId(); rocketAge = entity.tickCount; }
            }
        });
    }

    void tick(Minecraft client) {
        if (work != null && !work.isDone()) { return; }
        if (work != null) { work.join(); }
        ticks++;
        if (phase == 0) {
            work = CompletableFuture.runAsync(() -> prepare(client), client.getSingleplayerServer());
            advance();
        } else if (phase == 1) {
            if (client.player.getY() < -390 && resident(client)) {
                work = CompletableFuture.runAsync(() -> spawn(client), client.getSingleplayerServer());
                advance();
            }
        } else if (phase == 2 || phase == 4) {
            checkMobs(client);
        } else if (phase == 3) {
            client.player.getAbilities().flying = true;
            client.player.setDeltaMovement(0, 0, 0);
            double y = Math.min(67, client.player.getY() + 1.4);
            client.player.setPos(pathX(y), y, pathZ(y));
            if (client.player.getY() >= 67 && resident(client)) {
                work = CompletableFuture.runAsync(() -> spawn(client), client.getSingleplayerServer());
                advance();
            }
        } else if (phase == 5) {
            if (resident(client) && client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
                client.player.getAbilities().flying = false;
                client.player.onUpdateAbilities();
                client.player.setOnGround(false);
                client.player.setYRot(0);
                client.player.setXRot(0);
                client.player.startFallFlying();
                client.player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(client.player,
                        net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                advance();
            }
        } else if (phase == 6) {
            if (serverGliding && client.player.isFallFlying()) {
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                advance();
            }
        } else if (phase == 7) {
            if (ticks == 10) {
                net.minecraft.client.KeyMapping.click(net.minecraft.client.KeyMapping.get("key.cubicchunks.entity_report").getDefaultKey());
            }
            var rocket = client.level.getEntity(rocketId);
            sawRocket |= rocket instanceof FireworkRocketEntity && rocket.tickCount > 0;
            maxSpeed = Math.max(maxSpeed, client.player.getDeltaMovement().horizontalDistance());
            if (ticks % 20 == 0) {
                System.out.println("CC_FLIGHT_STATE serverRocket=" + rocketId + " age=" + rocketAge + " client=" + rocket
                        + " gliding=" + client.player.isFallFlying() + " maxSpeed=" + maxSpeed);
            }
            if (ticks >= 60) {
                if (!sawRocket || maxSpeed < 0.9) { throw new IllegalStateException("Firework boost failed: clientRocket=" + sawRocket + " speed=" + maxSpeed); }
                System.out.println("CC_ENTITY_TRAVEL_COMPLETE ascent=-400..67 rocketTicked=true maxSpeed=" + maxSpeed);
                client.stop();
            }
        }
        if (ticks > 800) { throw new IllegalStateException("Travel phase timed out: " + phase + " entities=" + states); }
    }

    private boolean resident(Minecraft client) {
        var pos = CubePos.from(client.player.blockPosition());
        return ((ClientCubeCache) client.level.getChunkSource()).cc_getCube(pos.getX(), pos.getY(), pos.getZ(), false) != null;
    }

    private void checkMobs(Minecraft client) {
        client.player.setYRot(0);
        client.player.setXRot(0);
        int visible = 0;
        int renderable = 0;
        for (State state : states) {
            var entity = client.level.getEntity(state.id);
            if (entity != null && client.level.isTickingEntity(entity)) { visible++; }
            if (entity != null && client.levelRenderer.isSectionCompiledAndVisible(entity.blockPosition(), 0)) { renderable++; }
        }
        if (ticks % 40 == 0) { System.out.println("CC_ENTITY_TRAVEL_STATE phase=" + phase + " client=" + visible + " renderable=" + renderable + " server=" + states); }
        if (states.size() == 12 && visible == 12 && renderable == 12) { visibleTicks++; } else { visibleTicks = 0; }
        if (visibleTicks < 40) { return; }
        System.out.println("CC_ENTITY_TRAVEL_VISIBLE phase=" + phase + " ticks=" + ticks);
        if (phase == 4) {
            Screenshot.grab(client.gameDirectory, "entity-travel-return.png", client.gameRenderer.mainRenderTarget(), 1, message -> {});
            work = CompletableFuture.runAsync(() -> {
                var player = client.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                player.teleportTo(117.5, 92, 77.5);
                player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FIREWORK_ROCKET, 64));
                player.inventoryMenu.broadcastChanges();
            }, client.getSingleplayerServer());
        }
        advance();
    }

    private void prepare(Minecraft client) {
        ServerLevel level = client.getSingleplayerServer().overworld();
        // Shaft for a continuous native creative ascent, including real movement packets and cube transitions.
        for (int y = -401; y <= 96; y++) {
            int centerX = (int) pathX(y);
            int centerZ = (int) pathZ(y);
            for (int x = centerX - 4; x <= centerX + 4; x++) {
                for (int z = centerZ - 4; z <= centerZ + 4; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    CubePos cube = CubePos.from(pos);
                    ((CubicLevelReader) level).cc_getCube(cube.getX(), cube.getY(), cube.getZ(), ChunkStatus.FULL, true);
                    level.setBlock(pos, (y == -401 ? Blocks.GLOWSTONE : Blocks.AIR).defaultBlockState(), 2);
                }
            }
        }
        ServerPlayer player = client.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
        for (int x = 110; x <= 125; x++) {
            for (int z = 70; z <= 89; z++) {
                for (int y = 66; y <= 72; y++) {
                    if (x >= 116 && x <= 119 && z >= 76 && z <= 79) { continue; }
                    level.setBlock(new BlockPos(x, y, z), (y == 66 ? Blocks.GRASS_BLOCK : Blocks.AIR).defaultBlockState(), 2);
                }
            }
        }
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.teleportTo(pathX(-400), -400, pathZ(-400));
    }

    private void spawn(Minecraft client) {
        mobs.forEach(Entity::discard);
        mobs.clear();
        var player = client.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
        for (int i = 0; i < 12; i++) {
            Mob mob = EntityTypes.ENDERMAN.spawn(player.level(), new BlockPos((int) player.getX() - 2 + i % 4, (int) player.getY(),
                    (int) player.getZ() + 3 + i / 4), EntitySpawnReason.SPAWN_ITEM_USE);
            if (mob == null) { throw new IllegalStateException("Native spawn failed"); }
            mob.setNoAi(true);
            mob.setPersistenceRequired();
            mobs.add(mob);
        }
    }

    private void advance() { phase++; ticks = 0; visibleTicks = 0; }
    private static double pathX(double y) { return 117.5 + Math.max(0, 67 - y) * 135 / 467; }
    private static double pathZ(double y) { return 77.5 + Math.max(0, 67 - y) * 52 / 467; }
    private record State(int id, boolean removed, boolean paired, boolean sent, boolean tracked) {}
}

