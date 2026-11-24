package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.cache.LoadingCache;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldData;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStorage;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.SerializableCubeData;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SculkSensorBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestDeepStructureRegion extends BaseTest {
    @TempDir
    Path directory;

    @Test
    void nativeTerrainFillLightingAndDiskReopenKeepConfiguredEntitiesInsteadOfDefaults() throws Exception {
        var f = new Fixture(-32000);
        var chestPos = new BlockPos(15, f.y, 15);
        var spawnerPos = chestPos.east();
        var sensorPos = new BlockPos(3, f.y + 1, 3);
        f.region.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 2);
        var chest = (ChestBlockEntity) f.region.getBlockEntity(chestPos);
        chest.setLootTable(BuiltInLootTables.ABANDONED_MINESHAFT);
        chest.setLootTableSeed(918273L);
        f.region.setBlock(spawnerPos, Blocks.SPAWNER.defaultBlockState(), 2);
        ((SpawnerBlockEntity) f.region.getBlockEntity(spawnerPos)).setEntityId(EntityTypes.CAVE_SPIDER, RandomSource.create(9));
        var result = f.region.finish();
        var expectedChest = f.region.getChunk(chestPos).getBlockEntityNbtForSaving(chestPos, f.level.registryAccess());
        var expectedSpawner = f.region.getChunk(spawnerPos).getBlockEntityNbtForSaving(spawnerPos, f.level.registryAccess());

        when(f.level.dimension()).thenReturn(Level.OVERWORLD);
        var data = mock(PrimaryLevelData.class);
        when(f.level.getLevelData()).thenReturn(data);
        when(((CubicWorldData) data).cc_deepCaveSettings()).thenReturn(new DeepCaveSettings(true, 64, 65, 35));
        var biome = f.level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.LUSH_CAVES);
        when(f.level.getChunkSource().getGenerator().getBiomeSource()).thenReturn(new FixedBiomeSource(biome));
        var terrain = new NativeBiomeTerrain(f.level, Runnable::run);
        var cube = f.cube();
        cachePlacement(terrain, cube, result, sensorPos);
        var lightInput = terrain.blocks(cube.cc_getCubePos()).join();
        terrain.fill(cube).join();
        cube.setPersistedStatus(ChunkStatus.FEATURES);
        assertEquals(expectedChest, cube.getBlockEntityNbtForSaving(chestPos, f.level.registryAccess()));
        assertEquals(expectedSpawner, cube.getBlockEntityNbtForSaving(spawnerPos, f.level.registryAccess()));
        assertNotNull(cube.getBlockEntity(sensorPos), "Base sculk still needs its normal generated block entity");
        assertTrue(lightInput.sameStates(CubeLightBlockSnapshot.capture(cube)));

        try (var storage = new CubeStorage(directory)) {
            storage.write(cube.cc_getCubePos(), SerializableCubeData.copyOf(f.level, cube).write()).join();
        }
        ProtoCube restored;
        try (var storage = new CubeStorage(directory)) {
            var saved = storage.read(cube.cc_getCubePos()).join().orElseThrow();
            restored = SerializableCubeData.parse(saved, cube.cc_getCubePos()).read(f.level);
        }
        assertEquals(expectedChest, restored.getBlockEntityNbtForSaving(chestPos, f.level.registryAccess()));
        assertEquals(expectedSpawner, restored.getBlockEntityNbtForSaving(spawnerPos, f.level.registryAccess()));
        var full = new LevelCube(f.level, restored, null);
        var restoredChest = (ChestBlockEntity) full.getBlockEntity(chestPos);
        assertEquals(BuiltInLootTables.ABANDONED_MINESHAFT, restoredChest.getLootTable());
        assertEquals(918273L, restoredChest.getLootTableSeed());
        assertNotNull(full.getBlockEntity(spawnerPos));
        assertNotNull(full.getBlockEntity(sensorPos));
        assertTrue(lightInput.sameStates(CubeLightBlockSnapshot.capture(restored)));
    }

    @SuppressWarnings("unchecked")
    private static void cachePlacement(NativeBiomeTerrain terrain, ProtoCube cube, DeepStructureResult result, BlockPos sensorPos) throws Exception {
        // Control only the completed generation output; exercise the unmodified production fill/default-BE/persistence path.
        var contentField = NativeBiomeTerrain.class.getDeclaredField("deepContent");
        contentField.setAccessible(true);
        var content = contentField.get(terrain);
        var sectionsField = DeepStructureTerrain.class.getDeclaredField("sections");
        sectionsField.setAccessible(true);
        var sections = (LoadingCache<SectionPos, Object>) sectionsField.get(content);
        var record = Class.forName(DeepStructureTerrain.class.getName() + "$Section");
        var constructor = record.getDeclaredConstructor(LevelChunkSection.class, List.class);
        constructor.setAccessible(true);
        var pos = cube.cc_getCubePos();
        for (int i = 0; i < cube.getSections().length; i++) {
            var key = SectionPos.of(Coords.cubeToSection(pos.getX(), Coords.indexToX(i)),
                    Coords.cubeToSection(pos.getY(), Coords.indexToY(i)), Coords.cubeToSection(pos.getZ(), Coords.indexToZ(i)));
            var section = cube.getSections()[i].copy();
            if (key.equals(SectionPos.of(sensorPos))) {
                section.setBlockState(SectionPos.sectionRelative(sensorPos.getX()), SectionPos.sectionRelative(sensorPos.getY()),
                        SectionPos.sectionRelative(sensorPos.getZ()), Blocks.SCULK_SENSOR.defaultBlockState(), false);
            }
            var slice = result.forSection(key);
            slice.apply(section, key.x(), key.y(), key.z());
            sections.put(key, constructor.newInstance(section, List.of(slice)));
        }
    }

    @Test
    void strongholdCorridorsKeepTheirShellAndEveryNativeDoorwayInEntirelyOpenCaves() {
        for (int y : new int[] { -320, -32000, -32000000 }) {
            var cave = new Fixture(y, 37, Blocks.AIR.defaultBlockState());
            var rock = new Fixture(y);
            var corridor = openCorridor(cave);
            placePiece(cave, corridor, cave.region);
            placePiece(rock, openCorridor(rock), rock.region);
            var bounds = corridor.getBoundingBox();
            for (var pos : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                assertEquals(rock.region.getBlockState(pos), cave.region.getBlockState(pos), "Cave air removed native corridor blocks at " + pos);
            }
            assertBrick(cave.region.getBlockState(new BlockPos(2, y, 3)));
            assertBrick(cave.region.getBlockState(new BlockPos(2, y + 4, 3)));
            assertBrick(cave.region.getBlockState(new BlockPos(0, y + 2, 1)));
            assertBrick(cave.region.getBlockState(new BlockPos(4, y + 2, 5)));
            assertCorridorOpenings(cave);
        }
    }

    @Test
    void strongholdLibraryKeepsItsOuterWallsFloorAndCeilingInCaveAir() {
        var f = new Fixture(-32000, 37, Blocks.AIR.defaultBlockState());
        var bounds = new BoundingBox(0, f.y, 0, 13, f.y + 10, 14);
        var room = new StrongholdPieces.Library(0, RandomSource.create(1), bounds, Direction.SOUTH);
        placePiece(f, room, f.region);
        assertBrick(f.region.getBlockState(new BlockPos(7, f.y, 7)));
        assertBrick(f.region.getBlockState(new BlockPos(7, f.y + 10, 7)));
        assertBrick(f.region.getBlockState(new BlockPos(0, f.y + 5, 7)));
        assertBrick(f.region.getBlockState(new BlockPos(13, f.y + 5, 7)));
        assertBrick(f.region.getBlockState(new BlockPos(7, f.y + 5, 14)));
    }

    @Test
    void nativeRegionStrongholdsRetainVanillaAirSkipping() {
        var f = new Fixture(-32000, 37, Blocks.AIR.defaultBlockState());
        // Delegate storage to the fixture without identifying this vanilla placement view as a deep region.
        var nativeView = mock(WorldGenLevel.class, delegatesTo(f.region));
        placePiece(f, openCorridor(f), nativeView);
        assertTrue(f.region.getBlockState(new BlockPos(2, f.y, 3)).isAir());
        assertTrue(f.region.getBlockState(new BlockPos(2, f.y + 4, 3)).isAir());
        assertTrue(f.region.getBlockState(new BlockPos(0, f.y + 2, 1)).isAir());
        assertCorridorOpenings(f);
    }

    @Test
    void nativePortalRoomPlacesAllFramesAndConfiguredSilverfishSpawnerAcrossCubeBoundary() {
        var f = new Fixture(-32004);
        var bounds = new BoundingBox(0, f.y, 0, 10, f.y + 7, 15);
        var room = new StrongholdPieces.PortalRoom(0, bounds, Direction.SOUTH);
        var type = f.level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(BuiltinStructures.STRONGHOLD);
        var start = new StructureStart(type, new ChunkPos(0, 0), 0, new PiecesContainer(List.of(room)));
        synchronized (StrongholdPieces.class) {
            start.placeInChunk(f.region, f.level.structureManager().forWorldGenRegion(f.region), f.level.getChunkSource().getGenerator(),
                    RandomSource.create(42), bounds, new ChunkPos(0, 0));
        }
        var result = f.region.finish();
        var cubes = new HashMap<CubePos, ProtoCube>();
        for (var section : result.sections()) {
            var pos = CubePos.from(new BlockPos(section.minBlockX(), section.minBlockY(), section.minBlockZ()));
            cubes.computeIfAbsent(pos, key -> {
                var cube = new ProtoCube(key, UpgradeData.EMPTY, f.level, f.level.palettedContainerFactory(), null);
                apply(result, cube);
                return cube;
            });
        }
        assertTrue(cubes.size() > 1, "The native room must actually cross a vertical cube boundary");
        int frames = 0, spawners = 0;
        for (var pos : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            var cube = cubes.get(CubePos.from(pos));
            var state = cube.getBlockState(pos);
            if (state.is(Blocks.END_PORTAL_FRAME)) {
                frames++;
            } else if (state.is(Blocks.SPAWNER)) {
                spawners++;
                var saved = cube.getBlockEntityNbtForSaving(pos, f.level.registryAccess());
                assertTrue(saved.toString().contains("minecraft:silverfish"), "Native spawner configuration must survive slicing");
            }
        }
        assertEquals(12, frames);
        assertEquals(1, spawners);
    }

    @Test
    void stagingCopiesOnlyReadSectionsAndLeavesBaseAndLiveWorldUntouched() {
        var f = new Fixture(-32000);
        var a = new BlockPos(15, f.y, 15);
        var b = a.east();
        assertEquals(0, f.reads.get());
        assertTrue(f.region.getBlockState(a).is(Blocks.DEEPSLATE));
        assertEquals(1, f.reads.get());
        assertTrue(f.region.getBlockState(a.above()).is(Blocks.DEEPSLATE));
        assertEquals(1, f.reads.get());
        assertTrue(f.region.setBlock(a, Blocks.GOLD_BLOCK.defaultBlockState(), 2));
        assertTrue(f.region.setBlock(b, Blocks.AIR.defaultBlockState(), 2));
        assertEquals(2, f.reads.get());
        assertFalse(f.region.setBlock(a.atY(f.minY - 1), Blocks.GOLD_BLOCK.defaultBlockState(), 2));
        assertTrue(f.base.getBlockState(15, 0, 15).is(Blocks.DEEPSLATE));

        var result = f.region.finish();
        assertEquals(2, result.sections().size());
        var sliced = result.forSection(SectionPos.of(a));
        assertEquals(Set.of(SectionPos.of(a)), sliced.sections());
        assertSame(DeepStructureResult.EMPTY, sliced.forSection(SectionPos.of(b)));
        var section = f.base.copy();
        result.apply(section, 0, SectionPos.blockToSectionCoord(f.y), 0);
        assertTrue(section.getBlockState(15, Math.floorMod(f.y, 16), 15).is(Blocks.GOLD_BLOCK));
        assertTrue(section.getBlockState(14, Math.floorMod(f.y, 16), 15).is(Blocks.DEEPSLATE));
        assertEquals(2, f.reads.get(), "Capturing the delta must not materialize untouched terrain");
        verify(f.level, never()).updatePOIOnBlockStateChange(any(), any(), any());
        verify(f.level, never()).getChunkAt(any());
    }

    @Test
    void configuredBlockEntitiesTicksAndPostprocessingSurviveCubeSlicingAtFullY() {
        for (int y : new int[] { -320, -32000, -32000000 }) {
            var f = new Fixture(y);
            var chestPos = new BlockPos(15, y, 15);
            var spawnerPos = chestPos.east();
            var removedPos = new BlockPos(1, y, 1);
            f.region.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 2);
            var chest = (ChestBlockEntity) f.region.getBlockEntity(chestPos);
            chest.setLootTable(BuiltInLootTables.ABANDONED_MINESHAFT);
            chest.setLootTableSeed(918273L);
            f.region.setBlock(spawnerPos, Blocks.SPAWNER.defaultBlockState(), 2);
            ((SpawnerBlockEntity) f.region.getBlockEntity(spawnerPos)).setEntityId(EntityTypes.CAVE_SPIDER, RandomSource.create(9));
            f.region.scheduleTick(spawnerPos, Blocks.SPAWNER, 7);
            f.region.getChunk(spawnerPos).markPosForPostProcessing(spawnerPos);
            f.region.setBlock(removedPos, Blocks.AIR.defaultBlockState(), 2);
            var expectedChest = f.region.getChunk(chestPos).getBlockEntityNbtForSaving(chestPos, f.level.registryAccess());
            var expectedSpawner = f.region.getChunk(spawnerPos).getBlockEntityNbtForSaving(spawnerPos, f.level.registryAccess());
            var expectedTicks = f.region.getChunk(spawnerPos).getTicksForSerialization(0).blocks();
            var result = f.region.finish();

            var cube = f.cube();
            cube.setBlockState(removedPos, Blocks.SCULK_SENSOR.defaultBlockState(), 2);
            cube.setBlockEntity(new SculkSensorBlockEntity(removedPos, Blocks.SCULK_SENSOR.defaultBlockState()));
            apply(result, cube);
            assertEquals(expectedChest, cube.getBlockEntityNbtForSaving(chestPos, f.level.registryAccess()));
            assertEquals(expectedSpawner, cube.getBlockEntityNbtForSaving(spawnerPos, f.level.registryAccess()));
            assertFalse(cube.getBlockEntitiesPos().contains(removedPos));
            assertTrue(cube.getBlockState(removedPos).isAir());
            // ProtoChunkTicks intentionally makes world-generation ticks immediate when a chunk starts ticking.
            assertEquals(expectedTicks, cube.getTicksForSerialization(0).blocks());
            assertFalse(cube.getPostProcessing()[Coords.blockToIndex(spawnerPos)].isEmpty());

            var second = f.cube();
            apply(result, second);
            cube.getBlockEntityNbt(chestPos).putString("LootTable", "minecraft:empty");
            assertEquals(expectedChest, second.getBlockEntityNbtForSaving(chestPos, f.level.registryAccess()), "Results must own their saved NBT");
        }
    }

    @Test
    void generatedEntityIdentityIsStableAcrossRegenerationAndDistinctBetweenPlacements() {
        var first = entityResult(37);
        var same = entityResult(37);
        var different = entityResult(38);
        assertEquals(first.getEntities().getFirst().read("UUID", UUIDUtil.CODEC), same.getEntities().getFirst().read("UUID", UUIDUtil.CODEC));
        assertNotEquals(first.getEntities().getFirst().read("UUID", UUIDUtil.CODEC),
                different.getEntities().getFirst().read("UUID", UUIDUtil.CODEC));
        assertEquals(first.getEntities().getFirst().getList("Pos"), same.getEntities().getFirst().getList("Pos"));
    }

    @Test
    void laterWritesEraseEarlierBlockEntitiesWithoutInventingDefaults() {
        var first = new Fixture(-32000);
        var second = new Fixture(-32000);
        var at = new BlockPos(4, first.y, 4);
        first.region.setBlock(at, Blocks.CHEST.defaultBlockState(), 2);
        ((ChestBlockEntity) first.region.getBlockEntity(at)).setLootTable(BuiltInLootTables.ABANDONED_MINESHAFT);
        second.region.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
        var cube = first.cube();
        apply(first.region.finish(), cube);
        assertTrue(cube.getBlockEntitiesPos().contains(at));
        apply(second.region.finish(), cube);
        assertTrue(cube.getBlockState(at).isAir());
        assertFalse(cube.getBlockEntitiesPos().contains(at));
        assertSame(DeepStructureResult.EMPTY, new Fixture(-32000).region.finish());
    }

    private static ProtoCube entityResult(long seed) {
        var f = new Fixture(-32000, seed);
        var entity = new ArmorStand(f.level, 8.5, f.y, 8.5);
        assertTrue(f.region.addFreshEntity(entity));
        var cube = f.cube();
        apply(f.region.finish(), cube);
        assertEquals(1, cube.getEntities().size());
        return cube;
    }

    private static StrongholdPieces.Straight openCorridor(Fixture fixture) {
        var bounds = new BoundingBox(0, fixture.y, 0, 4, fixture.y + 4, 6);
        var piece = new StrongholdPieces.Straight(0, RandomSource.create(1), bounds, Direction.SOUTH);
        var saved = piece.createTag(StructurePieceSerializationContext.fromLevel(fixture.level));
        saved.putString("EntryDoor", "OPENING");
        saved.putBoolean("Left", true);
        saved.putBoolean("Right", true);
        return new StrongholdPieces.Straight(saved);
    }

    private static void placePiece(Fixture fixture, StructurePiece piece, WorldGenLevel target) {
        synchronized (StrongholdPieces.class) {
            piece.postProcess(target, fixture.level.structureManager().forWorldGenRegion(fixture.region),
                    fixture.level.getChunkSource().getGenerator(), RandomSource.create(42), piece.getBoundingBox(), new ChunkPos(0, 0),
                    piece.getBoundingBox().getCenter());
        }
    }

    private static void assertBrick(BlockState state) {
        assertTrue(state.is(Blocks.STONE_BRICKS) || state.is(Blocks.CRACKED_STONE_BRICKS) || state.is(Blocks.MOSSY_STONE_BRICKS)
                || state.is(Blocks.INFESTED_STONE_BRICKS), "Expected an intact native stronghold shell, got " + state);
    }

    private static void assertCorridorOpenings(Fixture fixture) {
        for (int y = 1; y <= 3; y++) {
            for (int width = 1; width <= 3; width++) {
                assertTrue(fixture.region.getBlockState(new BlockPos(width, fixture.y + y, 0)).isAir(), "Entry doorway was sealed");
                assertTrue(fixture.region.getBlockState(new BlockPos(width, fixture.y + y, 6)).isAir(), "Forward doorway was sealed");
                assertTrue(fixture.region.getBlockState(new BlockPos(0, fixture.y + y, width + 1)).isAir(), "Left doorway was sealed");
                assertTrue(fixture.region.getBlockState(new BlockPos(4, fixture.y + y, width + 1)).isAir(), "Right doorway was sealed");
            }
        }
    }

    private static void apply(DeepStructureResult result, ProtoCube cube) {
        var pos = cube.cc_getCubePos();
        for (int i = 0; i < cube.getSections().length; i++) {
            result.apply(cube.getSections()[i], Coords.cubeToSection(pos.getX(), Coords.indexToX(i)),
                    Coords.cubeToSection(pos.getY(), Coords.indexToY(i)), Coords.cubeToSection(pos.getZ(), Coords.indexToZ(i)));
            result.copyExtras(cube, i);
        }
    }

    private static final class Fixture {
        private final ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
        private final AtomicInteger reads = new AtomicInteger();
        private final LevelChunkSection base = new LevelChunkSection(level.palettedContainerFactory());
        private final int y;
        private final int minY;
        private final DeepStructureRegion region;

        private Fixture(int y) {
            this(y, 37);
        }

        private Fixture(int y, long seed) {
            this(y, seed, Blocks.DEEPSLATE.defaultBlockState());
        }

        private Fixture(int y, long seed, BlockState terrain) {
            this.y = y;
            minY = Math.floorDiv(y, 512) * 512;
            Misc.setupLevelHeight(level, -64, 384);
            var registries = level.registryAccess();
            when(level.dimensionType()).thenReturn(registries.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD).value());
            var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
            when(level.getChunkSource().randomState()).thenReturn(RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings));
            when(level.getServer().registryAccess()).thenReturn(registries.freeze());
            var biome = registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.LUSH_CAVES);
            when(level.uncachedBiomeResolver()).thenReturn((x, by, z) -> biome);
            for (int x = 0; x < 16; x++) {
                for (int by = 0; by < 16; by++) {
                    for (int z = 0; z < 16; z++) {
                        base.setBlockState(x, by, z, terrain, false);
                    }
                }
            }
            region = new DeepStructureRegion(level, new BoundingBox(-32, minY, -32, 63, minY + 511, 63), minY, 512, seed,
                    (x, by, z) -> biome, (sx, sy, sz) -> {
                        reads.incrementAndGet();
                        return base;
                    });
        }

        private ProtoCube cube() {
            var cube = new ProtoCube(CubePos.from(new BlockPos(0, y, 0)), UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
            for (int i = 0; i < cube.getSections().length; i++) {
                cube.getSections()[i] = base.copy();
            }
            return cube;
        }
    }
}
