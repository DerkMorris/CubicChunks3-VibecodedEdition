package io.github.opencubicchunks.smoke;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;

/** Actual server raid waves and boss ticks at both height signs, observed through ordinary client tracking. */
final class WorldRaidBossProbe {
    private static final int[] HEIGHTS = { 32000, -32000 };
    private volatile Scene scene;
    private volatile Snapshot snapshot;
    private CompletableFuture<Void> setup;
    private int phase;
    private boolean clientSawRaider;
    private boolean clientSawWither;

    WorldRaidBossProbe() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Scene current = scene;
            if (current != null && !current.done) {
                current.tick();
                snapshot = current.capture();
            }
        });
    }

    void tick(Minecraft client) {
        if (setup == null) {
            prepare(client);
            return;
        }
        if (!setup.isDone())
            return;
        setup.join();
        Snapshot current = snapshot;
        if (current == null || current.height != HEIGHTS[phase])
            return;
        for (int id : current.raiders)
            clientSawRaider |= client.level.getEntity(id) instanceof Raider;
        clientSawWither |= client.level.getEntity(current.witherId) instanceof WitherBoss;
        if (!current.done)
            return;
        if (!clientSawRaider || !clientSawWither)
            throw new IllegalStateException("Boss/raid client tracking missing: " + current);
        System.out.println("CC_RAID_BOSS_PHASE height=" + current.height + " waves=" + current.waves
                + " raidVictory=true witherCharge=true explosion=true skullHit=true netherStar=true clientTracked=true");
        scene = null;
        if (++phase == HEIGHTS.length) {
            System.out.println("CC_RAID_BOSS_COMPLETE heights=2 nativeRaid=true nativeWither=true testBypass=" + CubicChunks.IS_IN_TEST);
            client.stop();
        } else {
            setup = null;
            snapshot = null;
            clientSawRaider = false;
            clientSawWither = false;
        }
    }

    private void prepare(Minecraft client) {
        int y = HEIGHTS[phase];
        var server = client.getSingleplayerServer();
        setup = CompletableFuture.runAsync(() -> {
            if (CubicChunks.IS_IN_TEST)
                throw new IllegalStateException("Raid/boss probe requires production ticking");
            ServerLevel level = server.overworld();
            server.setDifficulty(Difficulty.NORMAL, true);
            level.getGameRules().set(GameRules.MOB_GRIEFING, true, server);
            level.getGameRules().set(GameRules.MOB_DROPS, true, server);
            level.getGameRules().set(GameRules.RAIDS, true, server);
            for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(-20, y - 1, -20), new BlockPos(20, y + 14, 20))) {
                CubePos cube = CubePos.from(pos);
                ((CubicLevelReader) level).cc_getCube(cube.getX(), cube.getY(), cube.getZ(), ChunkStatus.FULL, true);
                level.setBlock(pos, (pos.getY() == y - 1 ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
            }
            if (!Raid.RAVAGER_SPAWN_PLACEMENT_TYPE.isSpawnPositionOk(level, new BlockPos(4, y, 0), EntityTypes.RAVAGER)) {
                throw new IllegalStateException("Raid fixture must provide a native valid spawn floor at Y=" + y);
            }
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.CREATIVE);
            player.teleportTo(0.5, y, 12.5);
            BlockPos home = new BlockPos(0, y, 0);
            level.setBlock(home.south(), Blocks.BED.white().defaultBlockState(), 2);
            level.setBlock(home, Blocks.BED.white().defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD), 2);
            var poi = level.getPoiManager();
            poi.add(home, level.registryAccess().lookupOrThrow(Registries.POINT_OF_INTEREST_TYPE).getOrThrow(PoiTypes.HOME));
            if (poi.take(type -> true, (type, pos) -> pos.equals(home), home, 0).isEmpty())
                throw new IllegalStateException("No occupied home");
            scene = new Scene(level, player, y);
        }, server);
    }

    private record Snapshot(int height, int ticks, int stage, int waves, List<Integer> raiders, int witherId, boolean done) {}

    private static final class Scene {
        final ServerLevel level;
        final ServerPlayer player;
        final int y;
        Raid raid;
        WitherBoss wither;
        IronGolem target;
        int ticks;
        int stage;
        int stageTicks;
        int waves;
        int waveTicks;
        boolean sawSkull;
        boolean done;

        Scene(ServerLevel level, ServerPlayer player, int y) {
            this.level = level;
            this.player = player;
            this.y = y;
        }

        AABB area() {
            return new AABB(-40, y - 10, -40, 40, y + 50, 40);
        }

        BlockPos blastMarker() {
            return new BlockPos(2, y + 4, 0);
        }

        void tick() {
            ticks++;
            stageTicks++;
            if (ticks % 100 == 0)
                System.out.println("CC_RAID_BOSS_PROGRESS " + capture());
            if (ticks > 1800)
                throw new IllegalStateException("Raid/boss did not progress: " + capture());
            if (stage == 0) {
                if (stageTicks < 40 || !level.isVillage(new BlockPos(0, y, 0)))
                    return;
                // Advancing the native 300-tick preparation countdown is safe only after the new
                // player's cube neighborhood has reached the same entity-ticking state it normally awaits.
                SpawnReadiness readiness = spawnReadiness();
                if (stageTicks == 40 || stageTicks % 20 == 0)
                    System.out.println("CC_RAID_SPAWN_READINESS height=" + y + " " + readiness);
                if (!readiness.ready())
                    return;
                player.addEffect(new MobEffectInstance(MobEffects.RAID_OMEN, 600, 0));
                raid = level.getRaids().createOrExtendRaid(player, new BlockPos(0, y, 0));
                player.removeEffect(MobEffects.RAID_OMEN);
                require(raid != null && raid.getCenter().getY() == y, "Raid creation/center failed");
                System.out.println("CC_RAID_CREATED height=" + y + " center=" + raid.getCenter() + " village=" + level.isVillage(raid.getCenter()));
                nextStage();
            } else if (stage == 1) {
                require(!raid.isStopped() && !raid.isLoss(),
                        "Native raid stopped before victory: started=" + raid.isStarted() + " waves=" + raid.getGroupsSpawned() + " active="
                                + raid.isActive() + " village=" + level.isVillage(raid.getCenter()) + " spawnReadiness=" + spawnReadiness());
                if (raid.getGroupsSpawned() != waves) {
                    waves = raid.getGroupsSpawned();
                    waveTicks = 0;
                    require(raid.getTotalRaidersAlive() > 0 && raid.getLeader(waves) != null, "Wave missing participants/leader");
                    System.out.println("CC_RAID_WAVE height=" + y + " wave=" + waves + " participants=" + raid.getTotalRaidersAlive());
                    for (Raider raider : raid.getAllRaiders())
                        require(Math.abs(raider.getY() - y) < 8, "Wave spawned on wrong vertical surface");
                    var nbt = Raids.CODEC.encodeStart(NbtOps.INSTANCE, level.getRaids()).getOrThrow();
                    require(nbt.equals(Raids.CODEC.encodeStart(NbtOps.INSTANCE, Raids.CODEC.parse(NbtOps.INSTANCE, nbt).getOrThrow()).getOrThrow()),
                            "Native raid state codec lost data");
                }
                if (raid.getTotalRaidersAlive() == 0 && waves < raid.getNumGroups(Difficulty.NORMAL))
                    setCooldown(raid, 1);
                if (++waveTicks >= 30)
                    for (Raider raider : List.copyOf(raid.getAllRaiders())) {
                        raider.hurtServer(level, level.damageSources().genericKill(), Float.MAX_VALUE);
                    }
                if (!raid.isVictory())
                    return;
                require(waves == raid.getNumGroups(Difficulty.NORMAL), "Raid skipped waves");
                raid.stop();
                level.getPoiManager().release(new BlockPos(0, y, 0));
                for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(-20, y - 1, -20), new BlockPos(20, y - 1, 20))) {
                    level.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), 2);
                }
                level.setBlockAndUpdate(blastMarker(), Blocks.STONE.defaultBlockState());
                wither = EntityTypes.WITHER.create(level, EntitySpawnReason.TRIGGERED);
                require(wither != null, "Wither creation failed");
                wither.snapTo(0.5, y + 2, 0.5);
                wither.makeInvulnerable();
                level.addFreshEntity(wither);
                nextStage();
            } else if (stage == 2) {
                if (wither.getInvulnerableTicks() > 0)
                    return;
                require(wither.tickCount >= 220 && wither.getHealth() == wither.getMaxHealth(), "Wither charge/healing failed");
                require(level.getBlockState(blastMarker()).isAir(), "Native Wither spawn explosion did not destroy nearby stone");
                float before = wither.getHealth();
                require(wither.hurtServer(level, level.damageSources().playerAttack(player), 20) && wither.getHealth() < before,
                        "Wither player damage failed");
                target = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.TRIGGERED);
                require(target != null, "Wither target creation failed");
                target.snapTo(14.5, y, 0.5);
                target.setNoAi(true);
                target.setPersistenceRequired();
                level.addFreshEntity(target);
                wither.setTarget(target);
                nextStage();
            } else if (stage == 3) {
                sawSkull |= !level.getEntitiesOfClass(WitherSkull.class, area()).isEmpty();
                if (!sawSkull || target.getHealth() == target.getMaxHealth())
                    return;
                require(target.hasEffect(MobEffects.WITHER), "Native skull impact omitted Wither effect");
                wither.hurtServer(level, level.damageSources().genericKill(), Float.MAX_VALUE);
                target.discard();
                nextStage();
            } else if (stage == 4) {
                boolean star = level.getEntitiesOfClass(ItemEntity.class, area()).stream().anyMatch(item -> item.getItem().is(Items.NETHER_STAR));
                if (star && wither.isRemoved())
                    done = true;
            }
        }

        Snapshot capture() {
            return new Snapshot(y, ticks, stage, waves, raid == null ? List.of() : raid.getAllRaiders().stream().map(Entity::getId).toList(),
                    wither == null ? -1 : wither.getId(), done);
        }

        SpawnReadiness spawnReadiness() {
            int loaded = 0;
            int ticking = 0;
            int valid = 0;
            // Cooldown 1 yields native radial candidates inside this square. All four intersected
            // cubes and their supporting cubes must be present before the probe shortens that cooldown.
            for (int x = -10; x <= 10; x++)
                for (int z = -10; z <= 10; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (CubeMobBlockView.loadedCube(level, pos.below()) != null && CubeMobBlockView.loadedCube(level, pos) != null
                            && CubeMobBlockView.loadedCube(level, pos.above()) != null)
                        loaded++;
                    if (level.isPositionEntityTicking(pos))
                        ticking++;
                    if (Raid.RAVAGER_SPAWN_PLACEMENT_TYPE.isSpawnPositionOk(level, pos, EntityTypes.RAVAGER))
                        valid++;
                }
            boolean neighborhood = ((CubicLevelReader) level).cc_hasCubesAt(-20, y - 1, -20, 20, y + 3, 20);
            return new SpawnReadiness(loaded, ticking, valid, neighborhood, player.getY());
        }

        private record SpawnReadiness(int loaded, int ticking, int nativeValid, boolean neighborhood, double playerY) {
            boolean ready() {
                return loaded == 441 && ticking == 441 && nativeValid >= 430 && neighborhood;
            }
        }

        void nextStage() {
            stage++;
            stageTicks = 0;
        }

        void require(boolean condition, String message) {
            if (!condition)
                throw new IllegalStateException(message + " at Y=" + y);
        }

        static void setCooldown(Raid raid, int value) {
            try {
                Field field = Raid.class.getDeclaredField("raidCooldownTicks");
                field.setAccessible(true);
                field.setInt(raid, value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
