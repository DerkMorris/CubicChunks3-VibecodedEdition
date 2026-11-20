package io.github.opencubicchunks.smoke;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.block.entity.CubeBeaconBeam;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSource;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.TagValueInput;

/** Real server ticks, native area matchers, effects and cube block-entity packets. No manual beacon ticks. */
final class WorldEnvironmentProbe {
    private volatile Scene scene;
    private volatile Snapshot snapshot;
    private CompletableFuture<Void> action;
    private int stage;
    private int lastProgress = -1;
    private int clientWait;

    WorldEnvironmentProbe() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Scene current = scene;
            if (current != null && server.overworld() == current.level) {
                current.ticks++;
                if (current.ticks % 100 == 0) {
                    current.diagnose();
                }
                snapshot = current.capture();
                if (current.ticks > 2000) {
                    throw new IllegalStateException("Environment probe timed out: " + snapshot);
                }
            }
        });
    }

    void tick(Minecraft client) {
        if (action == null) {
            prepare(client, 32000);
            return;
        }
        if (!action.isDone()) {
            return;
        }
        action.join();
        Scene current = scene;
        Snapshot observed = snapshot;
        if (current == null || observed == null || observed.y != current.pos.getY()) {
            return;
        }
        if (++clientWait > 2400) {
            throw new IllegalStateException("Client environment state did not converge at stage " + stage + ": " + observed);
        }
        if (observed.ticks / 100 != lastProgress) {
            lastProgress = observed.ticks / 100;
            System.out.println("CC_ENVIRONMENT_PROGRESS stage=" + stage + " " + observed);
        }
        var clientEntity = client.level.getBlockEntity(current.pos);
        if (!(clientEntity instanceof BeaconBlockEntity beacon) || Math.abs(client.player.getY() - current.playerY()) > 8) {
            return;
        }
        boolean sameBeam = Arrays.equals(observed.beam, CubeBeaconBeam.encode(beacon.getBeamSections()));
        boolean clientEffects = client.player.hasEffect(MobEffects.SPEED) && client.player.hasEffect(MobEffects.REGENERATION);
        if ((stage == 0 || stage == 2) && observed.active() && sameBeam && clientEffects) {
            current.requireScan();
            System.out.println("CC_ENVIRONMENT_BEACON_ACTIVE stage=" + stage + " y=" + observed.y
                    + " levels=4 colors=true serverEffects=true clientEffects=true clientBeam=true nativeBoxScan=164 orderedScan=true");
            if (stage == 0) {
                stage = 1;
                clientWait = 0;
                action = CompletableFuture.runAsync(current::addRoof, client.getSingleplayerServer());
            } else {
                stage = 3;
                clientWait = 0;
                prepare(client, -32000);
            }
            return;
        }
        if (stage == 1 && observed.phaseTicks > 400 && observed.beam.length == 0 && !observed.effects && sameBeam
                && !client.player.hasEffect(MobEffects.SPEED) && !client.player.hasEffect(MobEffects.REGENERATION)) {
            System.out.println("CC_ENVIRONMENT_DISTANT_ROOF blocked=true distance=1000000 effectsExpired=true clientBeamCleared=true");
            stage = 2;
            clientWait = 0;
            action = CompletableFuture.runAsync(current::removeRoof, client.getSingleplayerServer());
        }
        if (stage == 3 && observed.phaseTicks > 160 && observed.beam.length == 0 && !observed.effects && sameBeam) {
            current.requireScan();
            System.out.println("CC_ENVIRONMENT_DEEP_BEACON y=" + observed.y + " naturalRoof=true blocked=true nativeBoxScan=164 orderedScan=true");
            scene = null;
            System.out.println("CC_ENVIRONMENT_COMPLETE beaconBase=true beamColors=true effects=true distantRoof=true reactivated=true"
                    + " clientBeam=true nativeAreaScanning=true negativeDepthBlocked=true testBypass=" + CubicChunks.IS_IN_TEST);
            client.stop();
        }
    }

    private void prepare(Minecraft client, int height) {
        scene = null;
        snapshot = null;
        lastProgress = -1;
        var server = client.getSingleplayerServer();
        action = CompletableFuture.runAsync(() -> {
            if (CubicChunks.IS_IN_TEST) {
                throw new IllegalStateException("Environment probe requires production paths");
            }
            scene = new Scene(server.overworld(), server.getPlayerList().getPlayers().getFirst(), height);
        }, server);
    }

    private record Snapshot(int y, int ticks, int phaseTicks, int levels, int[] beam, boolean effects) {
        boolean active() {
            return levels == 4 && effects && beam.length == 6 && beam[0] == DyeColor.WHITE.getTextureDiffuseColor()
                    && beam[2] == DyeColor.RED.getTextureDiffuseColor()
                    && beam[4] == ARGB.average(DyeColor.RED.getTextureDiffuseColor(), DyeColor.BLUE.getTextureDiffuseColor());
        }

        @Override public String toString() {
            return "y=" + y + " ticks=" + ticks + " phaseTicks=" + phaseTicks + " levels=" + levels + " beam=" + Arrays.toString(beam) + " effects="
                    + effects;
        }
    }

    private static final class Scene {
        final ServerLevel level;
        final ServerPlayer player;
        final BlockPos pos;
        final int ironCount;
        final boolean orderedScan;
        int ticks;
        int phaseStarted;

        Scene(ServerLevel level, ServerPlayer player, int height) {
            this.level = level;
            this.player = player;
            this.pos = new BlockPos(31, height - 1, 31);
            for (int depth = 1; depth <= 4; depth++) {
                for (int x = -depth; x <= depth; x++) {
                    for (int z = -depth; z <= depth; z++) {
                        put(pos.offset(x, -depth, z), Blocks.IRON_BLOCK.defaultBlockState());
                    }
                }
            }
            // Player platform is beside the beam and outside the base's top layer.
            for (BlockPos cell : BlockPos.betweenClosed(pos.offset(3, 0, -3), pos.offset(7, 3, 3))) {
                put(cell, (cell.getY() == pos.getY() ? Blocks.STONE : Blocks.AIR).defaultBlockState());
            }
            put(pos, Blocks.BEACON.defaultBlockState());
            if (height > 0) {
                put(pos.above(), Blocks.STAINED_GLASS.red().defaultBlockState());
                put(pos.above(2), Blocks.BEDROCK.defaultBlockState());
                put(pos.above(3), Blocks.STAINED_GLASS.blue().defaultBlockState());
            } else if (level.getBlockState(pos.above()).getLightDampening() < 15) {
                throw new IllegalStateException("Deep environment scene requires its untouched natural stone roof");
            }
            var beacon = (BeaconBlockEntity) level.getBlockEntity(pos);
            if (beacon == null) {
                throw new IllegalStateException("Beacon placement did not create its block entity");
            }
            CompoundTag powers = new CompoundTag();
            powers.putString("primary_effect", "minecraft:speed");
            powers.putString("secondary_effect", "minecraft:regeneration");
            beacon.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), powers));
            beacon.setChanged();
            player.removeAllEffects();
            player.teleportTo(pos.getX() + 5.5, playerY(), pos.getZ() + .5);
            int[] count = { 0 };
            level.findBlocksIn(pos.offset(-4, -4, -4), pos.offset(4, -1, 4)).filterState(state -> state.is(Blocks.IRON_BLOCK))
                    .forEach((block, state) -> count[0]++);
            ironCount = count[0];
            orderedScan = level.findBlocksIn(List.of(pos.below(), pos.offset(4, -4, 4), pos.offset(-4, -4, -4)))
                    .filterState(state -> state.is(Blocks.IRON_BLOCK)).allMatched();
            requireScan();
        }

        double playerY() {
            return pos.getY() + 1;
        }

        void requireScan() {
            if (ironCount != 164 || !orderedScan) {
                throw new IllegalStateException("Native area scan did not see the pyramid: count=" + ironCount + " ordered=" + orderedScan);
            }
        }

        void put(BlockPos target, net.minecraft.world.level.block.state.BlockState state) {
            CubePos cube = CubePos.from(target);
            ((CubicLevelReader) level).cc_getCube(cube.getX(), cube.getY(), cube.getZ(), ChunkStatus.FULL, true);
            level.setBlock(target, state, 3);
        }

        void addRoof() {
            put(pos.above(1_000_000), Blocks.TINTED_GLASS.defaultBlockState());
            phaseStarted = ticks;
        }

        void removeRoof() {
            put(pos.above(1_000_000), Blocks.AIR.defaultBlockState());
            phaseStarted = ticks;
        }

        Snapshot capture() {
            var beacon = (BeaconBlockEntity) level.getBlockEntity(pos);
            if (beacon == null) {
                throw new IllegalStateException("Ticking beacon disappeared");
            }
            return new Snapshot(pos.getY(), ticks, ticks - phaseStarted, beacon.saveCustomOnly(level.registryAccess()).getIntOr("Levels", -1),
                    CubeBeaconBeam.encode(beacon.getBeamSections()), player.hasEffect(MobEffects.SPEED) && player.hasEffect(MobEffects.REGENERATION));
        }

        void diagnose() {
            var key = CubePos.from(pos);
            var cube = ((CubeSource) level.getChunkSource()).cc_getCubeNow(key.getX(), key.getY(), key.getZ());
            var beacon = (BeaconBlockEntity) level.getBlockEntity(pos);
            var map = level.getChunkSource().chunkMap;
            var column = ((CubeColumnSource) map).cc_column(pos.getX(), pos.getZ());
            System.out.println("CC_ENVIRONMENT_DIAGNOSTIC ticks=" + ticks + " pos=" + pos + " cube="
                    + (cube == null ? "null" : cube.getFullStatus() + "/" + cube.getPersistedStatus() + "/ticking=" + cube.isTicking(pos))
                    + " lastCheckY=" + field(beacon, "lastCheckY") + " rawBeam=" + field(beacon, "beamSections") + " checking="
                    + field(beacon, "checkingBeamSections") + " scanY=" + field(field(beacon, "cc_beam"), "nextY"));
            if (column != null) {
                System.out.println("CC_ENVIRONMENT_COLUMN bounds=" + column.minGeneratedY() + ".." + column.maxGeneratedY() + " known="
                        + column.cubes().keySet() + " unknown=" + column.unknown() + " states=" + java.util.stream.IntStream.range(0, 5)
                                .mapToObj(offset -> String.valueOf(column.state(pos.getX(), pos.getY() + offset, pos.getZ()))).toList());
            }
            Object sources = field(map, "cc_columnSources");
            CubePos horizontal = CubePos.of(key.getX(), 0, key.getZ());
            if (sources instanceof java.util.Map<?, ?> indexed && indexed.get(horizontal) instanceof List<?> cubes) {
                System.out.println("CC_ENVIRONMENT_COLUMN_SOURCES " + cubes.stream().map(source -> {
                    CubeAccess observed = (CubeAccess) source;
                    return observed.cc_getCubePos().getY() + ":" + observed.getPersistedStatus();
                }).toList());
            }
            Object cache = field(map, "cc_gameplayColumns");
            Object entries = field(cache, "entries");
            if (entries instanceof java.util.Map<?, ?> all) {
                Object entry = all.get(horizontal);
                Object saved = field(entry, "saved");
                String failure = "none";
                if (saved instanceof CompletableFuture<?> future && future.isCompletedExceptionally()) {
                    try {
                        future.join();
                    } catch (RuntimeException error) {
                        failure = error.toString();
                    }
                }
                System.out.println("CC_ENVIRONMENT_COLUMN_CACHE entries=" + all.size() + " saved=" + saved + " overBudget="
                        + field(entry, "overBudget") + " failure=" + failure);
            }
        }

        private static Object field(Object value, String name) {
            if (value == null) {
                return null;
            }
            for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    var field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(value);
                } catch (NoSuchFieldException absent) {
                    // Mixin fields may be declared in a superclass.
                } catch (ReflectiveOperationException failure) {
                    return failure.toString();
                }
            }
            return "missing:" + name;
        }
    }
}
