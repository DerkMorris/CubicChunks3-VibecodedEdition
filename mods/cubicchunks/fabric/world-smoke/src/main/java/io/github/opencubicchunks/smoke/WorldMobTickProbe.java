package io.github.opencubicchunks.smoke;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/** Uses actual tracking packets/client ticks, plus a native navigation goal. No development replacement of the tick pipeline. */
final class WorldMobTickProbe {
    private static final int[] HEIGHTS = Boolean.getBoolean("cubicchunks.smoke.mobTravel") ? new int[] { 96, -400, 96, 32000 } : new int[] { 96, 32000 };
    private volatile Scene scene;
    private volatile Snapshot snapshot;
    private CompletableFuture<Void> setup;
    private int phase;
    private int clientTicks;
    private int waiting;
    private boolean started;
    private CompletableFuture<Void> teleport;
    private boolean sawDamage;
    private boolean walked;
    private boolean attacked;
    private boolean screenshot;
    private boolean finalShot;
    private boolean finalShotDone;
    private final Map<Integer, Integer> initialTicks = new HashMap<>();

    WorldMobTickProbe() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Scene current = scene;
            if (current != null && current.running && current.level == server.overworld()) {
                advanceServer(current);
            }
        });
    }

    void tick(Minecraft client) {
        if (setup == null) { prepare(client); return; }
        if (!setup.isDone()) { return; }
        setup.join();
        Snapshot current = snapshot;
        if (current == null || current.height != HEIGHTS[phase]) { return; }
        List<Mob> visible = new ArrayList<>();
        for (State state : current.entities) {
            Entity entity = client.level.getEntity(state.id);
            if (!(entity instanceof Mob mob)) {
                if (++waiting % 100 == 0) {
                    client.getSingleplayerServer().execute(() -> {
                        var player = client.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                        var map = scene.level.getChunkSource().chunkMap;
                        var pos = CubePos.from(scene.controlled.getFirst().blockPosition());
                        System.out.println("CC_MOB_TRAVEL_WAIT height=" + scene.height + " missing=" + state.id
                                + " sent=" + ((io.github.opencubicchunks.cubicchunks.server.network.CubeEntityTrackingSender) player.connection.chunkSender).cc_hasSentEntityCube(scene.level, pos)
                                + " trackedCube=" + ((io.github.opencubicchunks.cubicchunks.server.level.CubicChunkMap) map).cc_isChunkTracked(player, pos.getX(), pos.getY(), pos.getZ())
                                + " trackedEntity=" + map.isTrackedByAnyPlayer(scene.controlled.getFirst())
                                + " view=" + ((io.github.opencubicchunks.cubicchunks.server.level.CCServerPlayer) player).cc_getCloTrackingView());
                    });
                }
                if (waiting > 600) { throw new IllegalStateException("Entity did not arrive after vertical travel: height=" + current.height + " id=" + state.id); }
                return;
            }
            visible.add(mob);
        }
        if (Boolean.getBoolean("cubicchunks.smoke.endermanTeleport")) {
            checkTeleport(client, current, visible);
            return;
        }
        if (!started) {
            if (!((ClientCubeCache) client.level.getChunkSource()).cc_isCubeLightReady(CubePos.from(visible.getFirst().blockPosition()))) { return; }
            client.getSingleplayerServer().execute(() -> scene.running = true);
            visible.forEach(mob -> initialTicks.put(mob.getId(), mob.tickCount));
            started = true;
        }
        client.player.setYRot(90);
        client.player.setXRot(8);
        clientTicks++;
        for (Mob mob : visible) {
            if (!client.level.isTickingEntity(mob)) {
                throw new IllegalStateException("Resident mob missing from native client tick list: " + mob);
            }
            sawDamage |= mob.hurtTime > 0;
            walked |= mob.walkAnimation.position() > 0.2F;
        }
        if (current.ticks >= 65 && !attacked) {
            client.gameMode.attack(client.player, visible.get(3)); // real player hit on the enderman
            attacked = true;
        }
        if (current.ticks >= 55 && !screenshot) {
            screenshot = true;
            Screenshot.grab(client.gameDirectory, "mob-ticks-" + current.height + ".png", client.gameRenderer.mainRenderTarget(), 1,
                    message -> System.out.println("CC_MOB_TICK_SCREENSHOT " + message.getString()));
        }
        if (current.ticks < 150 || clientTicks < 60) { return; }
        double error = 0;
        for (int i = 0; i < current.entities.size(); i++) {
            Mob mob = visible.get(i);
            State server = current.entities.get(i);
            error = Math.max(error, mob.position().distanceTo(server.position));
            if (mob.tickCount < initialTicks.get(mob.getId()) + clientTicks - 1 || mob.hurtTime != 0) {
                throw new IllegalStateException("Frozen client animation/damage countdown: " + mob + " ticks=" + mob.tickCount + " hurt=" + mob.hurtTime);
            }
        }
        if (error > 0.1 || !walked || !sawDamage || current.walkerDistance < 4 || !current.endermanHurt) {
            throw new IllegalStateException("Mob sync failed: error=" + error + " walking=" + walked + " damage=" + sawDamage
                    + " navigation=" + current.walkerDistance + " playerHit=" + current.endermanHurt);
        }
        if (!finalShot) {
            finalShot = true;
            Screenshot.grab(client.gameDirectory, "mob-ticks-" + current.height + "-complete.png", client.gameRenderer.mainRenderTarget(), 1,
                    message -> { finalShotDone = true; System.out.println("CC_MOB_TICK_SCREENSHOT " + message.getString()); });
            return;
        }
        if (!finalShotDone) { return; }
        System.out.println("CC_MOB_TICK_PHASE height=" + current.height + " mobs=" + visible.size() + " clientTicks=" + clientTicks
                + " positionError=" + error + " navigationDistance=" + current.walkerDistance + " damageExpired=true playerHit=true");
        if (++phase == HEIGHTS.length) {
            System.out.println("CC_MOB_TICK_COMPLETE heights=" + HEIGHTS.length + " controlledTypes=6 nativeNavigator=1");
            client.stop();
        } else {
            setup = null; started = false; sawDamage = false; walked = false; attacked = false; screenshot = false; finalShot = false;
            finalShotDone = false; clientTicks = 0; waiting = 0; initialTicks.clear();
        }
    }

    private void checkTeleport(Minecraft client, Snapshot current, List<Mob> visible) {
        if (teleport == null) {
            teleport = CompletableFuture.runAsync(() -> {
                if (scene.controlled.get(3) instanceof net.minecraft.world.entity.monster.Enderman unloadedCheck
                        && unloadedCheck.teleport(100000.5, current.height, 9.5)) {
                    throw new IllegalStateException("Unloaded destination accepted");
                }
                var enderman = (net.minecraft.world.entity.monster.Enderman) scene.controlled.get(3);
                if (!enderman.teleport(12.5, current.height + 3, 9.5)) {
                    throw new IllegalStateException("Enderman could not reach loaded floor at " + current.height);
                }
            }, client.getSingleplayerServer());
            return;
        }
        if (!teleport.isDone()) { return; }
        teleport.join();
        if (visible.get(3).position().distanceTo(new Vec3(12.5, current.height, 9.5)) > 0.05) { return; }
        System.out.println("CC_ENDERMAN_TELEPORT height=" + current.height + " client=" + visible.get(3).position());
        teleport = null;
        if (++phase == HEIGHTS.length) {
            client.stop();
        } else {
            setup = null;
        }
    }

    private void prepare(Minecraft client) {
        int height = HEIGHTS[phase];
        var server = client.getSingleplayerServer();
        setup = CompletableFuture.runAsync(() -> {
            if (scene != null) { scene.all().forEach(Entity::discard); }
            scene = null;
            var level = server.overworld();
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = height - 1; y <= height + 5; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        CubePos cube = CubePos.from(pos);
                        ((CubicLevelReader) level).cc_getCube(cube.getX(), cube.getY(), cube.getZ(), ChunkStatus.FULL, true);
                        level.setBlockAndUpdate(pos, (y > height - 1 && y < height + 5 ? Blocks.AIR
                                : y == height - 1 && x % 4 == 0 ? Blocks.GLOWSTONE : Blocks.STONE).defaultBlockState());
                    }
                }
            }
            List<Mob> controlled = new ArrayList<>();
            for (EntityType<? extends Mob> type : List.of(EntityTypes.PIG, EntityTypes.COW, EntityTypes.ZOMBIE, EntityTypes.ENDERMAN, EntityTypes.BAT, EntityTypes.COD)) {
                Mob mob = type.create(level, EntitySpawnReason.COMMAND);
                mob.setNoAi(true);
                mob.setNoGravity(true);
                if (mob instanceof net.minecraft.world.entity.ambient.Bat bat) { bat.setResting(false); }
                mob.setPersistenceRequired();
                mob.snapTo(6.5, height, 3.5 + controlled.size() * 2);
                level.addFreshEntity(mob);
                controlled.add(mob);
            }
            Mob walker = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
            walker.setPersistenceRequired();
            walker.snapTo(4.5, height, 14.5);
            walker.getGoalSelector().removeAllGoals(goal -> true);
            walker.getGoalSelector().addGoal(0, new Goal() {
                @Override public boolean canUse() { return true; }
                @Override public boolean requiresUpdateEveryTick() { return true; }
                @Override public void tick() {
                    if (scene == null || scene.ticks < 100) { walker.getNavigation().moveTo(22.5, height, 14.5, 1); }
                    else { walker.getNavigation().stop(); }
                }
            });
            level.addFreshEntity(walker);
            scene = new Scene(level, height, controlled, walker);
            snapshot = scene.capture();
            server.getPlayerList().getPlayers().getFirst().teleportTo(16.5, height, 9.5);
        }, server);
    }

    private void advanceServer(Scene current) {
        current.ticks++;
        for (int i = 0; i < current.controlled.size(); i++) {
            Mob mob = current.controlled.get(i);
            mob.setPos(6.5 + Math.min(current.ticks, 80) * 0.1, current.height, 3.5 + i * 2);
            mob.setYRot(-90);
            mob.setOnGround(true);
            if (current.ticks == 25) { current.level.broadcastDamageEvent(mob, current.level.damageSources().generic()); }
        }
        snapshot = current.capture();
    }

    private record State(int id, Vec3 position) {}
    private record Snapshot(int height, int ticks, List<State> entities, double walkerDistance, boolean endermanHurt) {}

    private static final class Scene {
        private final ServerLevel level;
        private final int height;
        private final List<Mob> controlled;
        private final Mob walker;
        private boolean running;
        private int ticks;

        private Scene(ServerLevel level, int height, List<Mob> controlled, Mob walker) {
            this.level = level; this.height = height; this.controlled = controlled; this.walker = walker;
        }

        private List<Mob> all() {
            List<Mob> all = new ArrayList<>(controlled);
            all.add(walker);
            return all;
        }

        private Snapshot capture() {
            return new Snapshot(height, ticks, all().stream().map(mob -> new State(mob.getId(), mob.position())).toList(),
                    walker.getX() - 4.5, controlled.get(3).getHealth() < controlled.get(3).getMaxHealth());
        }
    }
}


