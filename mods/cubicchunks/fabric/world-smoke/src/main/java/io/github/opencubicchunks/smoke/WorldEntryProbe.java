package io.github.opencubicchunks.smoke;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Runs real game code with real disk storage. This separate development mod is never shipped. */
public final class WorldEntryProbe implements ClientModInitializer {
    private static final net.minecraft.core.BlockPos MARKER = new net.minecraft.core.BlockPos(8,
            io.github.opencubicchunks.cubicchunks.world.level.levelgen.CubeTerrainGenerator.surface(246813579L, 8, 8) + 1, 8);
    private final boolean reopen = Boolean.getBoolean("cubicchunks.smoke.reopen");
    private final boolean asyncSaves = Boolean.getBoolean("cubicchunks.smoke.asyncSaves");
    private final java.util.concurrent.CompletableFuture<Void> asyncProbe = new java.util.concurrent.CompletableFuture<>();
    private final DaylightProbe daylight = Boolean.getBoolean("cubicchunks.smoke.daylight") ? new DaylightProbe() : null;
    private final BiomeProbe biomes = Boolean.getBoolean("cubicchunks.smoke.biomes") ? new BiomeProbe() : null;
    private final StreamingProbe streaming = Boolean.getBoolean("cubicchunks.smoke.streaming") ? new StreamingProbe() : null;
    private final CameraTurnProbe turns = Boolean.getBoolean("cubicchunks.smoke.flicker") ? new CameraTurnProbe() : null;
    private final StructureGenerationProbe structures = Boolean.getBoolean("cubicchunks.smoke.structures") ? new StructureGenerationProbe() : null;
    private final DeepCavesProbe caves = Boolean.getBoolean("cubicchunks.smoke.deepCaves") ? new DeepCavesProbe() : null;
    private boolean launched;
    private int ticks;
    private final WorldLightProbe lighting = Boolean.getBoolean("cubicchunks.smoke.lighting") ? new WorldLightProbe(reopen) : null;
    private final WorldLightDigProbe digging = Boolean.getBoolean("cubicchunks.smoke.lightPrediction") ? new WorldLightDigProbe() : null;
    private final WorldLightDecreaseProbe darkening = Boolean.getBoolean("cubicchunks.smoke.lightDecrease") ? new WorldLightDecreaseProbe() : null;
    private final WorldMobTickProbe mobs = Boolean.getBoolean("cubicchunks.smoke.mobTicks") ? new WorldMobTickProbe() : null;
    private final WorldEntityTravelProbe travel = Boolean.getBoolean("cubicchunks.smoke.entityTravel") ? new WorldEntityTravelProbe() : null;

    @Override public void onInitializeClient() {
        if (turns != null) { turns.register(); }
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        if (!launched && caves != null && !reopen) {
            launched = caves.create(client);
            if (!launched) { return; }
        }
        if (!launched && client.gui.screen() instanceof TitleScreen) {
            if (caves != null) { CubicChunks.config().setDeepCaveSettings(io.github.opencubicchunks.cubicchunks.world.level.levelgen.DeepCaveSettings.CLASSIC); }
            launched = true;
            CubicChunks.config().setGenerateNewWorldsAsCC(!reopen);
            CubicChunks.config().setVerticalViewDistance(2);
            client.options.renderDistance().set(travel == null && streaming == null && turns == null ? 2 : 5);
            client.options.simulationDistance().set(5);
            client.options.pauseOnLostFocus = false;
            String world = System.getProperty("cubicchunks.smoke.world", "cubic-entry");
            System.out.println("CC_WORLD_SMOKE_START " + world);
            if (reopen) {
                client.createWorldOpenFlows().openWorld(world, () -> { throw new IllegalStateException("Reopen canceled"); });
            } else {
            client.createWorldOpenFlows().createFreshLevel(world,
                    new LevelSettings(world, GameType.CREATIVE, new LevelSettings.DifficultySettings(mobs == null && travel == null && daylight == null && biomes == null ? Difficulty.PEACEFUL : Difficulty.NORMAL, false, false),
                            true, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(246813579L, structures != null, false), WorldPresets::createNormalWorldDimensions, client.gui.screen());
            }
        }
        if (client.level != null && client.player != null) {
            if (++ticks % 20 == 0) {
                System.out.println("CC_WORLD_SMOKE_PLAYER ticks=" + ticks + " cubic=" + ((CanBeCubic) client.level).cc_isCubic()
                        + " pos=" + client.player.position() + " screen=" + client.gui.screen()
                        + " ground=" + client.level.getBlockState(client.player.blockPosition().below())
                        + " render=" + client.levelRenderer.visibleSections().size());
            }
            if (ticks == 100 && !reopen) {
                var server = client.getSingleplayerServer();
                server.execute(() -> server.overworld().setBlockAndUpdate(MARKER, net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState()));
            }
            if (ticks == 400 && asyncSaves && !reopen) {
                startAsyncProbe(client);
            }
            if (asyncProbe.isCompletedExceptionally()) {
                asyncProbe.join();
            }
            if (lighting != null && ticks >= 160) {
                lighting.tick(client, ticks);
            }
            if (digging != null && ticks >= 160) {
                digging.tick(client);
            }
            if (darkening != null && ticks >= 160) {
                darkening.tick(client);
            }
            if (caves != null && ticks >= 120 && client.gui.screen() == null) { caves.tick(client, reopen); }
            if (structures != null && ticks >= 120 && client.gui.screen() == null) { structures.tick(client, reopen); }
            if (turns != null && ticks >= 120 && client.gui.screen() == null) { turns.tick(client); }
            if (streaming != null && ticks >= 120 && client.gui.screen() == null) { streaming.tick(client); }
            if (biomes != null && ticks >= 120 && client.gui.screen() == null) { biomes.tick(client); }
            if (daylight != null && ticks >= 160) { daylight.tick(client); }
            if (mobs != null && ticks >= 160) {
                mobs.tick(client);
            }
            if (travel != null && ticks >= 160) { travel.tick(client); }
            if (caves == null && structures == null && turns == null && streaming == null && lighting == null && digging == null && darkening == null && mobs == null && travel == null && daylight == null && biomes == null && ticks >= Integer.getInteger("cubicchunks.smoke.ticks", 200) && client.gui.screen() == null
                    && (!asyncSaves || reopen || asyncProbe.isDone())
                    && (!asyncSaves || client.level.getBlockState(MARKER.above()).is(net.minecraft.world.level.block.Blocks.EMERALD_BLOCK))
                    && client.levelRenderer.visibleSections().size() > 0
                    && !client.level.getBlockState(client.player.blockPosition().below()).isAir()
                    && client.level.getBlockState(MARKER).is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK)) {
                System.out.println("CC_WORLD_SMOKE_ENTERED marker=" + client.level.getBlockState(MARKER) + " reopen=" + reopen
                        + " testBypass=" + CubicChunks.IS_IN_TEST);
                client.stop();
            }
            if (ticks == (caves == null && structures == null && turns == null && streaming == null && lighting == null && digging == null && darkening == null && mobs == null && travel == null && daylight == null && biomes == null ? 1200 : 6000)) {
                throw new IllegalStateException("World entry timed out: " + client.gui.screen());
            }
        }
    }

    private void startAsyncProbe(Minecraft client) {
        var server = client.getSingleplayerServer();
        server.execute(() -> {
            try {
                var level = server.overworld();
                var map = level.getChunkSource().chunkMap;
                var storageField = net.minecraft.world.level.chunk.storage.SimpleRegionStorage.class.getDeclaredField("cc_cubeStorage");
                storageField.setAccessible(true);
                Object storage = storageField.get(map);
                var executorField = io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStorage.class.getDeclaredField("executor");
                executorField.setAccessible(true);
                var executor = (java.util.concurrent.ExecutorService) executorField.get(storage);
                var entered = new java.util.concurrent.CountDownLatch(1);
                var returned = new java.util.concurrent.CountDownLatch(1);
                var release = new java.util.concurrent.CountDownLatch(1);
                executor.execute(() -> {
                    entered.countDown();
                    try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                });
                var before = new java.util.concurrent.atomic.AtomicLong();
                Thread owner = Thread.currentThread();
                Thread.ofPlatform().daemon().name("CC async save smoke gate").start(() -> {
                    try {
                        if (!entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new IllegalStateException("I/O barrier did not start");
                        }
                        server.execute(() -> {
                            before.set(level.getGameTime());
                            long start = System.nanoTime();
                            try {
                                level.setBlockAndUpdate(MARKER.above(), net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK.defaultBlockState());
                                var cube = ((io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader) level)
                                        .cc_getCube(io.github.opencubicchunks.cc_core.api.CubePos.from(MARKER).getX(),
                                                io.github.opencubicchunks.cc_core.api.CubePos.from(MARKER).getY(),
                                                io.github.opencubicchunks.cc_core.api.CubePos.from(MARKER).getZ(),
                                                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
                                var save = net.minecraft.server.level.ChunkMap.class.getDeclaredMethod("cc_save",
                                        io.github.opencubicchunks.cubicchunks.world.level.chunklike.CloAccess.class);
                                save.setAccessible(true);
                                save.invoke(map, cube);
                                server.saveAllChunks(true, false, true);
                                level.setBlockAndUpdate(MARKER.above(), net.minecraft.world.level.block.Blocks.EMERALD_BLOCK.defaultBlockState());
                                long millis = (System.nanoTime() - start) / 1000000;
                                System.out.println("CC_ASYNC_SAVE_RETURN milliseconds=" + millis + " diskBlocked=" + (release.getCount() == 1));
                                if (millis >= 1000 || release.getCount() == 0) {
                                    throw new IllegalStateException("Autosave waited for blocked disk");
                                }
                            } catch (Throwable exception) { asyncProbe.completeExceptionally(exception); }
                            finally { returned.countDown(); }
                        });
                        if (!returned.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Autosave did not return while disk blocked");
                        }
                        for (int sample = 0; sample < 5; sample++) {
                            Thread.sleep(300);
                            System.out.println("CC_ASYNC_SAVE_STACK " + java.util.Arrays.toString(owner.getStackTrace()));
                        }
                        server.execute(() -> {
                            long advanced = level.getGameTime() - before.get();
                            System.out.println("CC_ASYNC_SAVE_TICKS advanced=" + advanced + " diskBlocked=" + (release.getCount() == 1));
                            if (advanced < 10 || release.getCount() == 0) {
                                asyncProbe.completeExceptionally(new IllegalStateException("Server did not keep ticking with pending writes"));
                            } else {
                                asyncProbe.complete(null);
                            }
                            release.countDown();
                        });
                    } catch (Throwable exception) {
                        release.countDown();
                        asyncProbe.completeExceptionally(exception);
                    }
                });
            } catch (Throwable exception) { asyncProbe.completeExceptionally(exception); }
        });
    }
}





