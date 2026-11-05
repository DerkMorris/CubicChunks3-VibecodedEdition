package io.github.opencubicchunks.cubicchunks.test.world.level.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeSpawnTestWorld;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevel;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeSpawnContext;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubicEntityManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.SpawnUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.VillagerPanicTrigger;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.GolemSensor;
import net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubeGolemSpawning extends BaseTest {
    @TempDir
    Path directory;

    @Test
    void nativeVillagersSummonAtBothHeightSignsWithoutLoadingColumnsOrUnknownLight() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new Fixture(directory.resolve(Integer.toString(y)), y)) {
                var villagers = f.villagers(3, y);
                villagers.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
                assertEquals(1, f.golems().size());
                var golem = f.golems().getFirst();
                assertEquals(new BlockPos(6, y, 2), golem.blockPosition());
                assertFalse(golem.isPlayerCreated());
                for (var villager : villagers) {
                    assertEquals(599, villager.getBrain().getTimeUntilExpiry(MemoryModuleType.GOLEM_DETECTED_RECENTLY));
                    assertFalse(villager.wantsToSpawnGolem(5000));
                }
                villagers.get(1).spawnGolemIfNeeded(f.level, 5000, 3);
                assertEquals(1, f.golems().size());
                assertTrue(f.spawn.lights.isEmpty());
            }
        }
    }

    @Test
    void rejectedEntityAdmissionDoesNotConsumeVillagersGolemCooldown() throws Exception {
        try (var f = new Fixture(directory, 32000008)) {
            var villagers = f.villagers(3, f.y);
            var rejected = new ArrayList<Entity>();
            doAnswer(i -> {
                rejected.add(i.getArgument(0));
                return false;
            }).when(f.level).addFreshEntity(any(IronGolem.class));
            villagers.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
            assertTrue(f.golems().isEmpty());
            assertEquals(10, rejected.size());
            assertTrue(rejected.stream().allMatch(Entity::isRemoved));
            assertTrue(villagers.stream().allMatch(body -> body.wantsToSpawnGolem(5000)));
        }
    }

    @Test
    void nativeFloorAndHeadroomRulesRejectLowCeiling() throws Exception {
        try (var f = new Fixture(directory, -32000024)) {
            var villagers = f.villagers(3, f.y);
            f.spawn.world.block(new BlockPos(6, f.y + 2, 2), Blocks.STONE.defaultBlockState());
            // Fill the rest of the search column so the roof itself is not a second valid floor.
            for (int dy = 3; dy <= 8; dy++) {
                f.spawn.world.block(new BlockPos(6, f.y + dy, 2), Blocks.STONE.defaultBlockState());
            }
            villagers.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
            assertTrue(f.golems().isEmpty());
            assertTrue(villagers.getFirst().wantsToSpawnGolem(5000));
        }
    }

    @Test
    void panicUsesThreeRecentlySleepingVillagersAndNativeHundredTickCadence() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new Fixture(directory.resolve(Integer.toString(y)), y)) {
                var villagers = f.villagers(3, y);
                var body = villagers.getFirst();
                body.getBrain().setMemory(MemoryModuleType.NEAREST_HOSTILE, EntityTypes.ZOMBIE.create(f.level, EntitySpawnReason.LOAD));
                var panic = new VillagerPanicTrigger();
                assertTrue(panic.tryStart(f.level, body, 4999));
                panic.tickOrStop(f.level, body, 4999);
                assertTrue(f.golems().isEmpty());
                panic.tickOrStop(f.level, body, 5000);
                assertEquals(1, f.golems().size());
                assertEquals(y, f.golems().getFirst().blockPosition().getY());
            }
        }
    }

    @Test
    void gossipNeedsFiveVillagersAndRetainsItsOwnCooldown() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new Fixture(directory.resolve(Integer.toString(y)), y)) {
                var villagers = f.villagers(4, y);
                villagers.getFirst().gossip(f.level, villagers.get(1), 5000);
                assertTrue(f.golems().isEmpty());
                f.villagers(1, y);
                villagers.getFirst().gossip(f.level, villagers.get(1), 5001);
                assertTrue(f.golems().isEmpty());
                when(f.level.getGameTime()).thenReturn(6200L);
                villagers.getFirst().gossip(f.level, villagers.get(1), 6200);
                assertEquals(1, f.golems().size());
            }
        }
    }

    @Test
    void unsleptExpiredAndVerticallyAliasedVillagersDoNotCompleteTheQuorum() throws Exception {
        try (var f = new Fixture(directory, 32000008)) {
            var nearby = f.villagers(2, f.y);
            var distant = f.villagers(3, f.y + 4096);
            nearby.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
            assertTrue(f.golems().isEmpty());
            var third = f.villagers(1, f.y).getFirst();
            third.getBrain().eraseMemory(MemoryModuleType.LAST_SLEPT);
            nearby.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
            assertTrue(f.golems().isEmpty());
            third.getBrain().setMemory(MemoryModuleType.LAST_SLEPT, 5000L - 24000);
            nearby.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
            assertTrue(f.golems().isEmpty());
            third.getBrain().setMemory(MemoryModuleType.LAST_SLEPT, 4000L);
            nearby.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
            assertEquals(1, f.golems().size());
            assertTrue(distant.stream().allMatch(body -> body.wantsToSpawnGolem(5000)));
        }
    }

    @Test
    void nativeSensorsFindOnlyNearbyLiveGolemsUsingFullHeight() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new Fixture(directory.resolve(Integer.toString(y)), y)) {
                var body = f.villagers(1, y).getFirst();
                var remote = f.golem(new BlockPos(6, y + 4096, 2));
                sense(f, body);
                assertTrue(body.wantsToSpawnGolem(5000));
                assertFalse(body.getBrain().getMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES).orElse(List.of()).contains(remote));
                var local = f.golem(new BlockPos(6, y + 4, 2));
                sense(f, body);
                assertFalse(body.wantsToSpawnGolem(5000));
                assertEquals(599, body.getBrain().getTimeUntilExpiry(MemoryModuleType.GOLEM_DETECTED_RECENTLY));
                local.discard();
                body.getBrain().eraseMemory(MemoryModuleType.GOLEM_DETECTED_RECENTLY);
                sense(f, body);
                assertTrue(body.wantsToSpawnGolem(5000));
            }
        }
    }

    @Test
    void successfulSummonMemoryExpiresUsingNativeBrainTicks() throws Exception {
        try (var f = new Fixture(directory, -32000024)) {
            var villagers = f.villagers(3, f.y);
            villagers.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
            assertEquals(1, f.golems().size());
            f.golems().getFirst().discard();
            var tick = Brain.class.getDeclaredMethod("forgetOutdatedMemories");
            tick.setAccessible(true);
            for (int i = 0; i < 599; i++) {
                for (var body : villagers) {
                    tick.invoke(body.getBrain());
                }
            }
            assertFalse(villagers.getFirst().wantsToSpawnGolem(5599));
            for (var body : villagers) {
                tick.invoke(body.getBrain());
            }
            when(f.level.getGameTime()).thenReturn(5600L);
            assertTrue(villagers.getFirst().wantsToSpawnGolem(5600));
            villagers.getFirst().spawnGolemIfNeeded(f.level, 5600, 3);
            assertEquals(1, f.golems().size());
        }
    }

    @Test
    void missingSearchPrefixRejectsThatAttemptButDoesNotPoisonNextCandidate() throws Exception {
        try (var f = new Fixture(directory, 32000030)) {
            var villagers = f.villagers(3, f.y);
            f.spawn.world.block(new BlockPos(-6, f.y - 1, 2), Blocks.STONE.defaultBlockState());
            f.spawn.world.cubes.remove(CubePos.from(new BlockPos(-6, f.y + 6, 2)));
            when(f.random.nextInt(17)).thenReturn(0, 8, 12, 8);
            villagers.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
            assertEquals(1, f.golems().size());
            assertEquals(new BlockPos(6, f.y, 2), f.golems().getFirst().blockPosition());
            assertNull(CubeSpawnContext.current(f.level));
        }
    }

    @Test
    void entireGolemBoxNeedsLoadedTerrainAndEntityDataAcrossCubeBoundary() throws Exception {
        for (boolean missingTerrain : new boolean[] { true, false }) {
            try (var f = new Fixture(directory.resolve(Boolean.toString(missingTerrain)), -32000024)) {
                var villagers = f.villagers(3, f.y);
                villagers.forEach(body -> body.setPos(27.5, f.y, body.getZ()));
                CubePos adjacent = CubePos.from(new BlockPos(32, f.y, 2));
                var cube = f.spawn.world.cubes.get(adjacent);
                if (missingTerrain) {
                    f.spawn.world.cubes.remove(adjacent);
                } else {
                    ((CubicServerLevel) f.level).cc_unloadCubeEntities(adjacent);
                }
                villagers.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
                assertTrue(f.golems().isEmpty());
                assertTrue(villagers.getFirst().wantsToSpawnGolem(5000));
                f.spawn.world.cubes.put(adjacent, cube);
                ((CubicServerLevel) f.level).cc_onCubeEntityStatus(adjacent, FullChunkStatus.ENTITY_TICKING);
                villagers.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
                assertEquals(1, f.golems().size());
                assertEquals(new BlockPos(31, f.y, 2), f.golems().getFirst().blockPosition());
            }
        }
    }

    @Test
    void forbiddenFloorAndEntityObstructionsLeaveSummonRetryable() throws Exception {
        try (var f = new Fixture(directory, 32000008)) {
            var villagers = f.villagers(3, f.y);
            BlockPos floor = new BlockPos(6, f.y - 1, 2);
            f.spawn.world.block(floor, Blocks.GLASS.defaultBlockState());
            villagers.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
            assertTrue(f.golems().isEmpty());
            f.spawn.world.block(floor, Blocks.STONE.defaultBlockState());
            var obstacle = EntityTypes.COW.create(f.level, EntitySpawnReason.LOAD);
            obstacle.snapTo(6.5, f.y, 2.5);
            assertTrue(f.level.addFreshEntity(obstacle));
            villagers.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
            assertTrue(f.golems().isEmpty());
            obstacle.discard();
            villagers.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
            assertEquals(1, f.golems().size());
        }
    }

    @Test
    void worldBorderAndCoordinateEndpointsRejectWithoutWrappingOrConsumingCooldown() throws Exception {
        for (int y : new int[] { CubicBlockBounds.MIN, CubicBlockBounds.MAX - 1 }) {
            try (var f = new Fixture(directory.resolve(Integer.toString(y)), y)) {
                var villagers = f.villagers(3, y);
                villagers.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
                assertTrue(f.golems().isEmpty());
                assertTrue(villagers.getFirst().wantsToSpawnGolem(5000));
                assertNull(CubeSpawnContext.current(f.level));
            }
        }
        try (var f = new Fixture(directory.resolve("border"), 32000008)) {
            var villagers = f.villagers(3, f.y);
            f.level.getWorldBorder().setSize(1);
            villagers.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
            assertTrue(f.golems().isEmpty());
        }
    }

    @Test
    void ordinaryWorldDelegatesToUnchangedNativeSpawnUtility() throws Exception {
        try (var f = new Fixture(directory, 8); var nativeSpawn = org.mockito.Mockito.mockStatic(SpawnUtil.class)) {
            var villagers = f.villagers(3, f.y);
            when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
            // Keep the fixture's entity getter cubic; only the spawning adapter selects ordinary delegation.
            var box = villagers.getFirst().getBoundingBox().inflate(10);
            org.mockito.Mockito.doReturn(villagers).when(f.level).getEntitiesOfClass(Villager.class, box);
            nativeSpawn
                    .when(() -> SpawnUtil.trySpawnMob(EntityTypes.IRON_GOLEM, EntitySpawnReason.MOB_SUMMONED, f.level,
                            villagers.getFirst().blockPosition(), 10, 8, 6, SpawnUtil.Strategy.LEGACY_IRON_GOLEM, false))
                    .thenReturn(java.util.Optional.empty());
            villagers.getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
            nativeSpawn.verify(() -> SpawnUtil.trySpawnMob(EntityTypes.IRON_GOLEM, EntitySpawnReason.MOB_SUMMONED, f.level,
                    villagers.getFirst().blockPosition(), 10, 8, 6, SpawnUtil.Strategy.LEGACY_IRON_GOLEM, false));
            assertNull(CubeSpawnContext.current(f.level));
            when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(true);
        }
    }

    @Test
    void summonedGolemSurvivesRealCubeEntitySaveUnloadAndReload() throws Exception {
        try (var f = new Fixture(directory, -32000024)) {
            f.villagers(3, f.y).getFirst().spawnGolemIfNeeded(f.level, 5000, 3);
            var original = f.golems().getFirst();
            var id = original.getUUID();
            original.setHealth(73);
            var store = ((CubicEntityManager) f.spawn.world.manager).cc_entityPersistence();
            CubePos pos = CubePos.from(original.blockPosition());
            store.unload(pos);
            assertTrue(f.golems().isEmpty());
            store.load(pos);
            assertEquals(1, f.golems().size());
            assertEquals(id, f.golems().getFirst().getUUID());
            assertEquals(73, f.golems().getFirst().getHealth());
            assertFalse(f.golems().getFirst().isPlayerCreated());
            assertEquals(new BlockPos(6, f.y, 2), f.golems().getFirst().blockPosition());
        }
    }

    @Test
    void playerBuiltIronPatternUsesFullHeightAndDoesNotConsumeAliasedPattern() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new Fixture(directory.resolve(Integer.toString(y)), y)) {
                var blocks = new io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView(f.level);
                doAnswer(i -> blocks.getBlockState(i.getArgument(0))).when(f.level).getBlockState(any());
                doAnswer(i -> {
                    BlockPos pos = i.getArgument(0);
                    return f.spawn.world.cubes.get(CubePos.from(pos)).setBlockState(pos, i.getArgument(1), i.getArgument(2)) != null;
                }).when(f.level).setBlock(any(), any(), anyInt());
                BlockPos base = new BlockPos(6, y, 2);
                pattern(f, base);
                pattern(f, base.above(4096));
                f.ready();
                assertTrue(f.level.getBlockState(base).is(Blocks.IRON_BLOCK));
                assertTrue(f.level.getBlockState(base.above().west()).is(Blocks.IRON_BLOCK));
                assertTrue(f.level.getBlockState(base.above(2)).is(Blocks.CARVED_PUMPKIN));
                assertTrue(f.level.getBlockState(base.east()).isAir());
                assertTrue(f.level.hasChunkAt(base));
                assertEquals(f.level.getBlockState(base),
                        new net.minecraft.world.level.block.state.pattern.BlockInWorld(f.level, base, false).getState());
                var pumpkin = (net.minecraft.world.level.block.CarvedPumpkinBlock) Blocks.CARVED_PUMPKIN;
                assertTrue(pumpkin.canSpawnGolem(f.level, base.above(2)));
                var place = net.minecraft.world.level.block.CarvedPumpkinBlock.class.getDeclaredMethod("trySpawnGolem",
                        net.minecraft.world.level.Level.class, BlockPos.class);
                place.setAccessible(true);
                place.invoke(pumpkin, f.level, base.above(2));
                assertEquals(1, f.golems().size());
                assertTrue(f.golems().getFirst().isPlayerCreated());
                assertEquals(y + 0.05, f.golems().getFirst().getY(), 0.0001);
                assertTrue(blocks.getBlockState(base).isAir());
                assertTrue(blocks.getBlockState(base.above(2)).isAir());
                assertTrue(blocks.getBlockState(base.above(4096)).is(Blocks.IRON_BLOCK));
                assertTrue(blocks.getBlockState(base.above(4098)).is(Blocks.CARVED_PUMPKIN));
            }
        }
    }

    private static void pattern(Fixture f, BlockPos base) {
        for (BlockPos pos : List.of(base, base.above(), base.above().west(), base.above().east())) {
            f.spawn.world.block(pos, Blocks.IRON_BLOCK.defaultBlockState());
        }
        f.spawn.world.cube(base.above(2)).setBlockState(base.above(2), Blocks.CARVED_PUMPKIN.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_SKIP_ON_PLACE);
    }

    private static void sense(Fixture f, Villager body) throws Exception {
        var sensor = new NearestLivingEntitySensor<Villager>();
        var tick = NearestLivingEntitySensor.class.getDeclaredMethod("doTick", ServerLevel.class, net.minecraft.world.entity.LivingEntity.class);
        tick.setAccessible(true);
        tick.invoke(sensor, f.level, body);
        GolemSensor.checkForNearbyGolem(body);
    }

    private static final class Fixture implements AutoCloseable {
        private final CubeSpawnTestWorld spawn;
        private final ServerLevel level;
        private final int y;
        private final RandomSource random = mock();
        private int randomCalls;

        private Fixture(Path path, int y) throws Exception {
            this.y = y;
            spawn = new CubeSpawnTestWorld(path);
            level = spawn.level;
            doCallRealMethod().when(level).isInsideBuildHeight(any(BlockPos.class));
            doCallRealMethod().when(level).isOutsideBuildHeight(any(BlockPos.class));
            floor();
            ready();
            when(level.getGameTime()).thenReturn(5000L);
            when(level.getRandom()).thenReturn(random);
            when(random.nextInt(17)).thenAnswer(i -> randomCalls++ % 2 == 0 ? 12 : 8);
            doCallRealMethod().when((CubicServerLevel) level).cc_areCubeEntitiesLoaded(any());
            doCallRealMethod().when((CubicServerLevel) level).cc_unloadCubeEntities(any());
            doAnswer(i -> {
                throw new AssertionError("Golem spawning tried to load terrain");
            }).when((CubicLevel) level).cc_getCubeAt(any());
            var getter = ServerLevel.class.getDeclaredMethod("getEntities");
            getter.setAccessible(true);
            getter.invoke(doCallRealMethod().when(level));
            doCallRealMethod().when(level).getEntitiesOfClass(any(), any(AABB.class));
            doCallRealMethod().when(level).getEntitiesOfClass(any(), any(AABB.class), any());
            doCallRealMethod().when(level).getEntities(nullable(Entity.class), any(AABB.class));
            doCallRealMethod().when(level).getEntities(nullable(Entity.class), any(AABB.class), any());
            doCallRealMethod().when(level).getEntities(any(EntityTypeTest.class), any(AABB.class), any());
            doCallRealMethod().when(level).getEntities(any(EntityTypeTest.class), any(), any(), any());
            doCallRealMethod().when(level).getEntities(any(EntityTypeTest.class), any(), any(), any(), anyInt());
            when(level.dragonParts()).thenReturn(List.of());
        }

        private void floor() {
            for (int x = -4; x <= 40; x++) {
                for (int z = -4; z <= 8; z++) {
                    if (CubicBlockBounds.contains(y - 1)) {
                        spawn.world.block(new BlockPos(x, y - 1, z), Blocks.STONE.defaultBlockState());
                    }
                    spawn.world.cube(new BlockPos(x, y, z));
                    spawn.world.cube(new BlockPos(x, Math.min(y + 3, CubicBlockBounds.MAX), z));
                }
            }
        }

        private void ready() {
            for (var cube : spawn.world.cubes.values()) {
                ((CubicServerLevel) level).cc_onCubeEntityStatus(cube.cc_getCubePos(), FullChunkStatus.ENTITY_TICKING);
            }
        }

        private List<Villager> villagers(int count, int height) {
            var villagers = new ArrayList<Villager>();
            for (int i = 0; i < count; i++) {
                Villager body = EntityTypes.VILLAGER.create(level, EntitySpawnReason.LOAD);
                body.snapTo(2.5, height, 2.5 + i * 0.2);
                body.getBrain().setMemory(MemoryModuleType.LAST_SLEPT, 4000L);
                spawn.world.status(body, FullChunkStatus.ENTITY_TICKING);
                assertTrue(level.addFreshEntity(body));
                villagers.add(body);
            }
            return villagers;
        }

        private IronGolem golem(BlockPos pos) {
            var golem = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.LOAD);
            golem.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            spawn.world.status(golem, FullChunkStatus.ENTITY_TICKING);
            assertTrue(level.addFreshEntity(golem));
            return golem;
        }

        private List<IronGolem> golems() {
            return StreamSupport.stream(level.getAllEntities().spliterator(), false).filter(IronGolem.class::isInstance).map(IronGolem.class::cast)
                    .toList();
        }

        @Override public void close() throws Exception {
            // A failed ordinary-delegation assertion must still close the cube backend, not enter vanilla's mocked disk loop.
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            spawn.close();
        }
    }
}
