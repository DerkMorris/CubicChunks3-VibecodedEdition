package io.github.opencubicchunks.cubicchunks.test.world.level.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.StreamSupport;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.testutils.CubeSpawnTestWorld;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeCatSpawning;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubePatrolSpawning;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeSpawnPoints;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeSpecialSpawnSupport;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeTraderSpawning;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.equine.TraderLlama;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.monster.PatrollingMonster;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.saveddata.WanderingTraderData;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubeSpecialSpawning extends BaseTest {
    @TempDir
    Path directory;

    private static RandomSource zeroRandom(CubeSpawnTestWorld f) {
        RandomSource random = mock();
        when(f.level.getRandom()).thenReturn(random);
        return random;
    }

    private static ServerPlayer player(CubeSpawnTestWorld f, int x, int y, int z) {
        var player = f.player(x, y, z);
        when(player.blockPosition()).thenReturn(new BlockPos(x, y, z));
        return player;
    }

    private static <T extends Entity> List<T> entities(CubeSpawnTestWorld f, Class<T> type) {
        return StreamSupport.stream(f.level.getAllEntities().spliterator(), false).filter(type::isInstance).map(type::cast).toList();
    }

    private static void entityQueries(CubeSpawnTestWorld f) {
        doAnswer(i -> entities(f, (Class<Entity>) i.getArgument(0)).stream()
                .filter(entity -> entity.getBoundingBox().intersects((AABB) i.getArgument(1))).toList()).when(f.level)
                .getEntitiesOfClass(any(), any(AABB.class));
    }

    private static void sky(CubeSpawnTestWorld f, int value) {
        for (var cube : f.world.cubes.values()) {
            byte[][] sky = new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE];
            for (byte[] section : sky) {
                Arrays.fill(section, (byte) (value | value << 4));
            }
            f.lights.put(cube.cc_getCubePos(),
                    CubeLightView.fromExact(cube.cc_getCubePos(), new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE], sky));
        }
    }

    private static int delay(Object spawner, String fieldName) throws Exception {
        var field = spawner.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(spawner);
    }

    private static void insomnia(CubeSpawnTestWorld f, ServerPlayer player) {
        ServerStatsCounter stats = mock();
        when(stats.getValue(Stats.CUSTOM.get(Stats.TIME_SINCE_REST))).thenReturn(80000);
        when(player.getStats()).thenReturn(stats);
        when(f.level.getSkyDarken()).thenReturn(6);
        var random = zeroRandom(f);
        when(random.nextInt(anyInt())).thenAnswer(i -> switch ((int) i.getArgument(0)) {
            case 80000 -> 72000;
            case 21 -> 10;
            default -> 0;
        });
    }

    @Test
    void actualPhantomSpawnerUsesInsomniaAndLocalSkyAtBothHeightSigns() throws Exception {
        for (int y : List.of(32000008, -32000024)) {
            try (var f = new CubeSpawnTestWorld(directory.resolve(Integer.toString(y)))) {
                f.platform(y, 0);
                sky(f, 15);
                var player = player(f, 16, y, 16);
                insomnia(f, player);
                var spawner = new PhantomSpawner();
                spawner.tick(f.level, true);
                var phantoms = entities(f, Phantom.class);
                assertEquals(1, phantoms.size());
                assertEquals(y + 20, phantoms.getFirst().getY());
                assertTrue(f.world.ticks.contains(phantoms.getFirst()));
                assertEquals(1199, delay(spawner, "nextTick"));
                spawner.tick(f.level, true);
                assertEquals(1, entities(f, Phantom.class).size());
                verify(f.level, never()).getChunk(anyInt(), anyInt());
            }
        }
    }

    @Test
    void phantomRejectsCoveredUnknownAndUnloadedSpawnPositions() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = 32000024;
            f.platform(y, 0);
            var player = player(f, 16, y, 16);
            insomnia(f, player);
            new PhantomSpawner().tick(f.level, true);
            assertTrue(entities(f, Phantom.class).isEmpty());
            sky(f, 15);
            new PhantomSpawner().tick(f.level, true);
            assertTrue(entities(f, Phantom.class).isEmpty());
            f.lights.clear();
            new PhantomSpawner().tick(f.level, true);
            assertTrue(entities(f, Phantom.class).isEmpty());
            verify(f.level, never()).tryAddFreshEntityWithPassengers(any());
        }
    }

    @Test
    void patrolTickSpawnsAtLocalSurfaceAndLeaderTargetRetainsY() throws Exception {
        for (int y : List.of(32000008, -32000024)) {
            try (var f = new CubeSpawnTestWorld(directory.resolve(Integer.toString(y)))) {
                f.platform(y, 15);
                player(f, -8, y + 3, -8);
                zeroRandom(f);
                when(f.level.isBrightOutside()).thenReturn(true);
                when(f.level.environmentAttributes()).thenReturn(
                        EnvironmentAttributeSystem.builder().addConstantLayer(EnvironmentAttributes.CAN_PILLAGER_PATROL_SPAWN, old -> true).build());
                assertFalse(CubePatrolSpawning.spawnMember(f.level, new BlockPos(16, y + 3, 16), true));
                sky(f, 15);
                var spawner = new PatrolSpawner();
                spawner.tick(f.level, true);
                var patrol = entities(f, PatrollingMonster.class);
                assertFalse(patrol.isEmpty());
                assertEquals(y, patrol.getFirst().getY());
                assertEquals(y, patrol.getFirst().getPatrolTarget().getY());
                assertTrue(delay(spawner, "nextTick") > 0);
                verify(f.level, never()).getHeight(any(), anyInt(), anyInt());
            }
        }
    }

    @Test
    void surfaceSearchIsBoundedAndNeverJumpsAcrossMissingCubes() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = -32000024;
            f.platform(y, 15);
            BlockPos floor = new BlockPos(16, y, 16);
            assertEquals(floor, CubeSpecialSpawnSupport.surface(f.level, floor.above(3), EntityTypes.PILLAGER).orElseThrow());
            assertTrue(CubeSpecialSpawnSupport.surface(f.level, floor.above(64), EntityTypes.PILLAGER).isEmpty());
            f.world.cube(floor.above(64));
            assertTrue(CubeSpecialSpawnSupport.surface(f.level, floor.above(64), EntityTypes.PILLAGER).isEmpty());
            ((CubicServerLevel) f.level).cc_onCubeEntityStatus(CubePos.from(floor), FullChunkStatus.FULL);
            assertFalse(CubePatrolSpawning.spawnMember(f.level, floor, true));
        }
    }

    @Test
    void occupiedHomesProtectPatrolsWithoutAliasingOtherHeights() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = 32000008;
            f.platform(y, 15);
            BlockPos home = new BlockPos(16, y, 16);
            f.world.block(home,
                    Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState().setValue(AbstractBedBlock.PART, BedPart.HEAD));
            assertFalse(CubeSpawnPoints.nearHomes(f.level, home));
            assertTrue(f.poi.take(type -> true, (type, point) -> point.equals(home), home, 0).isPresent());
            assertTrue(CubeSpawnPoints.nearHomes(f.level, home));
            assertFalse(CubeSpawnPoints.nearHomes(f.level, home.above(4096)));
            f.world.block(home, Blocks.AIR.defaultBlockState());
            assertFalse(CubeSpawnPoints.nearHomes(f.level, home));
        }
    }

    @Test
    void hutCatsUseStructurePiecesFullYAndLocalPopulationCap() throws Exception {
        for (int y : List.of(32000008, -32000024)) {
            try (var f = new CubeSpawnTestWorld(directory.resolve(Integer.toString(y)))) {
                f.platform(y, 15);
                entityQueries(f);
                BlockPos pos = new BlockPos(16, y, 16);
                var hut = f.level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(BuiltinStructures.SWAMP_HUT);
                f.world.cube(pos).setStartForStructure(hut, TestCubeStructureSpawning.start(hut, pos));
                assertTrue(CubeCatSpawning.spawnAt(f.level, pos));
                var cats = entities(f, Cat.class);
                assertEquals(1, cats.size());
                assertEquals(y, cats.getFirst().getY());
                assertTrue(cats.getFirst().isPersistenceRequired());
                assertFalse(CubeCatSpawning.spawnAt(f.level, pos));
                assertFalse(CubeCatSpawning.spawnAt(f.level, pos.above(4096)));
            }
        }
    }

    @Test
    void catTickUsesNativeCooldownAndVillageNeedsConfirmedDistinctHomes() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = -32000024;
            f.platform(y, 15);
            entityQueries(f);
            BlockPos pos = new BlockPos(16, y, 16);
            player(f, 8, y, 8);
            zeroRandom(f);
            for (int i = 0; i < 5; i++) {
                BlockPos home = new BlockPos(20 + i, y, 20);
                f.world.block(home,
                        Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState().setValue(AbstractBedBlock.PART, BedPart.HEAD));
                assertTrue(f.poi.take(type -> true, (type, point) -> point.equals(home), home, 0).isPresent());
                var villager = EntityTypes.VILLAGER.create(f.level, EntitySpawnReason.LOAD);
                villager.snapTo(20 + i, y + 1, 20);
                villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(f.level.dimension(), home));
                assertTrue(f.level.addFreshEntity(villager));
            }
            assertEquals(5, CubeSpawnPoints.occupiedHomes(f.level, pos).size());
            var spawner = new CatSpawner();
            spawner.tick(f.level, false);
            assertEquals(1, entities(f, Cat.class).size());
            assertFalse(entities(f, Cat.class).getFirst().isPersistenceRequired());
            assertEquals(1200, delay(spawner, "nextTick"));
            spawner.tick(f.level, false);
            assertEquals(1, entities(f, Cat.class).size());
        }
    }

    @Test
    void traderNativeClockKeepsSavedDataAndSpawnsLeashedLlamasAtHighY() throws Exception {
        for (int y : List.of(32000008, -32000024)) {
            try (var f = new CubeSpawnTestWorld(directory.resolve(Integer.toString(y)))) {
                f.platform(y, 15);
                player(f, 16, y + 2, 16);
                zeroRandom(f);
                RandomSource random = mock();
                when(random.nextInt(anyInt())).thenAnswer(i -> {
                    int bound = i.getArgument(0);
                    return bound == 96 || bound == 8 ? bound / 2 : 0;
                });
                var data = new WanderingTraderData(1200, 25);
                SavedDataStorage storage = mock();
                when(storage.computeIfAbsent(WanderingTraderData.TYPE)).thenReturn(data);
                var spawner = new WanderingTraderSpawner(storage);
                CubeMobTestWorld.setField(WanderingTraderSpawner.class, spawner, "random", random);
                CubeMobTestWorld.setField(WanderingTraderSpawner.class, spawner, "tickDelay", 1);
                spawner.tick(f.level, true);
                var traders = entities(f, WanderingTrader.class);
                assertEquals(1, traders.size());
                assertEquals(y, traders.getFirst().getY());
                assertEquals(48000, traders.getFirst().getDespawnDelay());
                var llamas = entities(f, TraderLlama.class);
                assertEquals(2, llamas.size());
                assertTrue(llamas.stream().allMatch(llama -> llama.getLeashHolder() == traders.getFirst()));
                assertEquals(24000, data.spawnDelay());
                assertEquals(25, data.spawnChance());
                assertTrue(data.isDirty());
                verify(f.level, never()).getHeight(any(), anyInt(), anyInt());
            }
        }
    }

    @Test
    void meetingPointsUseFullYAndTraderDefersUnknownLight() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = 32000008;
            f.platform(y, 15);
            player(f, 16, y, 16);
            BlockPos bell = new BlockPos(20, y, 20);
            f.world.block(bell, Blocks.BELL.defaultBlockState());
            assertEquals(bell, CubeSpawnPoints.meeting(f.level, new BlockPos(16, y, 16)).orElseThrow());
            assertTrue(CubeSpawnPoints.meeting(f.level, bell.below(4096)).isEmpty());
            f.lights.clear();
            RandomSource random = mock();
            when(random.nextInt(anyInt())).thenAnswer(i -> (int) i.getArgument(0) / 2);
            when(random.nextInt(10)).thenReturn(0);
            assertFalse(CubeTraderSpawning.spawn(f.level, random));
            verify(f.level, never()).tryAddFreshEntityWithPassengers(any());
        }
    }

    @Test
    void individualRulesAndEnemySettingPreserveNativeClocks() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            var phantom = new PhantomSpawner();
            var patrol = new PatrolSpawner();
            var trader = new WanderingTraderSpawner(mock());
            f.level.getGameRules().set(GameRules.SPAWN_PHANTOMS, false, null);
            f.level.getGameRules().set(GameRules.SPAWN_PATROLS, false, null);
            f.level.getGameRules().set(GameRules.SPAWN_WANDERING_TRADERS, false, null);
            phantom.tick(f.level, true);
            patrol.tick(f.level, true);
            trader.tick(f.level, true);
            assertEquals(0, delay(phantom, "nextTick"));
            assertEquals(0, delay(patrol, "nextTick"));
            assertEquals(1200, delay(trader, "tickDelay"));
            f.level.getGameRules().set(GameRules.SPAWN_PHANTOMS, true, null);
            f.level.getGameRules().set(GameRules.SPAWN_PATROLS, true, null);
            phantom.tick(f.level, false);
            patrol.tick(f.level, false);
            assertEquals(0, delay(phantom, "nextTick"));
            assertEquals(0, delay(patrol, "nextTick"));
        }
    }

    @Test
    void freezeAndGameRulesStopSpecialClocksAndOrdinaryWorldsKeepNativeRoute() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            var phantom = new PhantomSpawner();
            var patrol = new PatrolSpawner();
            var cat = new CatSpawner();
            var trader = new WanderingTraderSpawner(mock());
            when(f.level.tickRateManager().runsNormally()).thenReturn(false);
            phantom.tick(f.level, true);
            patrol.tick(f.level, true);
            cat.tick(f.level, true);
            trader.tick(f.level, true);
            assertEquals(0, delay(phantom, "nextTick"));
            assertEquals(0, delay(patrol, "nextTick"));
            assertEquals(0, delay(cat, "nextTick"));
            assertEquals(1200, delay(trader, "tickDelay"));
            when(f.level.tickRateManager().runsNormally()).thenReturn(true);
            f.level.getGameRules().set(GameRules.SPAWN_MOBS, false, null);
            cat.tick(f.level, true);
            assertEquals(0, delay(cat, "nextTick"));
            when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
            cat.tick(f.level, true);
            assertEquals(1200, delay(cat, "nextTick"));
        }
    }
}
