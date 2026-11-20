package io.github.opencubicchunks.smoke;

import java.util.HashSet;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.server.level.CubeWeatherTicks;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSource;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;

/** Natural server weather ticks and real client updates; the rare lightning probability is exercised separately by unit tests. */
final class WorldWeatherProbe {
    private static final int[] HEIGHTS = { -32000, 96, 32000 };
    private volatile Scene scene;
    private volatile Result result;
    private CompletableFuture<Void> setup;
    private int phase;
    private int lastProgress;
    private WeatherRenderingChecks rendering;

    WorldWeatherProbe() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Scene current = scene;
            if (current != null && current.level == server.overworld()) {
                current.tick();
                result = current.capture();
                if (current.age > 2400) {
                    throw new IllegalStateException("Weather probe timed out: " + result);
                }
            }
        });
    }

    void tick(Minecraft client) {
        if (setup == null) {
            int y = HEIGHTS[phase];
            setup = CompletableFuture.runAsync(() -> prepare(client, y), client.getSingleplayerServer());
            return;
        }
        if (!setup.isDone()) {
            return;
        }
        setup.join();
        Result observed = result;
        Scene current = scene;
        if (observed == null || current == null || observed.y != HEIGHTS[phase]) {
            return;
        }
        if (observed.age / 100 > lastProgress) {
            lastProgress = observed.age / 100;
            System.out.println("CC_WEATHER_PROGRESS " + observed);
        }
        if (!observed.complete() || Math.abs(client.player.getY() - observed.y) > 8) {
            return;
        }
        Result clientResult = current.capture(client.level);
        if (observed.y < 0 ? clientResult.snow != 0 || clientResult.ice != 0
                : clientResult.snow < 4 || clientResult.ice < 1 || clientResult.coveredSnow != 0 || clientResult.coveredIce != 0) {
            return;
        }
        if (rendering == null) {
            rendering = new WeatherRenderingChecks(current.level, current.y);
        }
        if (!rendering.tick(client)) {
            return;
        }
        System.out.println("CC_WEATHER_PHASE " + observed + " clientConfirmed=true testBypass=" + CubicChunks.IS_IN_TEST);
        scene = null;
        if (++phase == HEIGHTS.length) {
            System.out.println("CC_WEATHER_COMPLETE heights=3 precipitation=true snowIce=true glassRoof=true buriedDry=true lightningRod=true");
            client.stop();
        } else {
            setup = null;
            result = null;
            lastProgress = 0;
            rendering = null;
        }
    }

    private void prepare(Minecraft client, int y) {
        if (CubicChunks.IS_IN_TEST) {
            throw new IllegalStateException("Weather smoke requires production ticks");
        }
        var server = client.getSingleplayerServer();
        var level = server.overworld();
        level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 128, server);
        server.setWeatherParameters(0, 12000, true, true);
        // The cold fixture keeps the native biome, light and snow accumulation logic active at all selected heights.
        var cold = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.SNOWY_PLAINS);
        var cubes = new HashSet<CubePos>();
        int clearTop = y == 96 ? level.getMaxY() : y + 6;
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(64, y - 2, 0), new BlockPos(95, clearTop, 31))) {
            CubePos cubePos = CubePos.from(pos);
            if (cubes.add(cubePos)) {
                LevelCube cube = (LevelCube) ((CubicLevelReader) level).cc_getCube(cubePos.getX(), cubePos.getY(), cubePos.getZ(), ChunkStatus.FULL,
                        true);
                for (var section : cube.getSections()) {
                    section.fillBiomesFromNoise((x, by, z) -> cold, 0, 0, 0);
                }
                cube.markUnsaved();
            }
            var state = pos.getY() < y ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (level.getBlockState(pos) != state) {
                level.setBlock(pos, state, 2);
            }
        }
        for (int x = 64; x <= 95; x++) {
            for (int z = 0; z <= 31; z++) {
                if (x >= 80) {
                    level.setBlock(new BlockPos(x, y + 4, z), Blocks.GLASS.defaultBlockState(), 2);
                }
                if (z >= 8 && z <= 15 && (x >= 72 && x <= 79 || x >= 88 && x <= 91)) {
                    level.setBlock(new BlockPos(x, y - 1, z), Blocks.WATER.defaultBlockState(), 2);
                }
            }
        }
        server.getPlayerList().getPlayers().getFirst().teleportTo(80.5, y + 1, 24.5);
        scene = new Scene(level, y, cubes);
    }

    private record Result(
            int y, int age, int snow, int ice, int coveredSnow, int coveredIce, int knownSurface, boolean lightningStarted, boolean rodPowered,
            boolean rodReset
    ) {
        boolean complete() {
            if (y < 0) {
                return age >= 160 && snow == 0 && ice == 0 && knownSurface > 0;
            }
            return snow >= 4 && ice >= 1 && coveredSnow == 0 && coveredIce == 0 && (y != 96 || rodPowered && rodReset);
        }
    }

    private static final class Scene {
        private final ServerLevel level;
        private final int y;
        private final HashSet<CubePos> cubes;
        private int age;
        private boolean lightningStarted;
        private boolean rodPlaced;
        private boolean rodPowered;
        private boolean rodReset;

        private Scene(ServerLevel level, int y, HashSet<CubePos> cubes) {
            this.level = level;
            this.y = y;
            this.cubes = cubes;
        }

        private BlockPos rod() {
            return new BlockPos(68, y, 4);
        }

        private void tick() {
            age++;
            Result observed = capture();
            if (y == 96 && (rodPlaced || observed.snow >= 4 && observed.ice >= 1) && !lightningStarted) {
                if (!rodPlaced) {
                    var warm = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
                    for (CubePos pos : cubes) {
                        var cube = ((CubicLevelReader) level).cc_getCube(pos.getX(), pos.getY(), pos.getZ(), ChunkStatus.FULL, true);
                        for (var section : cube.getSections()) {
                            section.fillBiomesFromNoise((x, by, z) -> warm, 0, 0, 0);
                        }
                        cube.markUnsaved();
                    }
                    level.setBlockAndUpdate(rod(), Blocks.LIGHTNING_ROD.weathering().unaffected().defaultBlockState());
                    rodPlaced = true;
                }
                var source = (CubeColumnSource) level.getChunkSource().chunkMap;
                var target = CubeWeatherTicks.surface(source, Heightmap.Types.MOTION_BLOCKING, rod());
                if (rod().above().equals(target) && level.getPoiManager().existsAtPosition(PoiTypes.LIGHTNING_ROD, rod())) {
                    lightningStarted = CubeWeatherTicks.thunder(level, source, target);
                    if (!lightningStarted) {
                        throw new IllegalStateException("Weather probe could not create native lightning");
                    }
                    System.out.println("CC_WEATHER_LIGHTNING nativeStrikeBody=true randomGate=unitTested target=" + rod());
                }
            }
            if (lightningStarted) {
                boolean powered = level.getBlockState(rod()).getValue(LightningRodBlock.POWERED);
                rodReset |= rodPowered && !powered;
                rodPowered |= powered;
            }
        }

        private Result capture() {
            return capture(level);
        }

        private Result capture(Level view) {
            int snow = 0, ice = 0, coveredSnow = 0, coveredIce = 0;
            for (int x = 65; x <= 94; x++) {
                for (int z = 1; z <= 30; z++) {
                    boolean isSnow = view.getBlockState(new BlockPos(x, y, z)).is(Blocks.SNOW);
                    boolean isIce = view.getBlockState(new BlockPos(x, y - 1, z)).is(Blocks.ICE);
                    if (x < 80) {
                        if (isSnow) {
                            snow++;
                        }
                        if (isIce) {
                            ice++;
                        }
                    } else {
                        if (isSnow) {
                            coveredSnow++;
                        }
                        if (isIce) {
                            coveredIce++;
                        }
                    }
                }
            }
            int surface = Integer.MIN_VALUE;
            if (view == level) {
                var top = CubeWeatherTicks.surface((CubeColumnSource) level.getChunkSource().chunkMap, Heightmap.Types.MOTION_BLOCKING,
                        new BlockPos(68, y, 4));
                if (top != null) {
                    surface = top.getY();
                }
            }
            return new Result(y, age, snow, ice, coveredSnow, coveredIce, surface, lightningStarted, rodPowered, rodReset);
        }
    }
}
