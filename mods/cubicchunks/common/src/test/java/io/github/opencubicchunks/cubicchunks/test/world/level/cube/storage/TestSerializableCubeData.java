package io.github.opencubicchunks.cubicchunks.test.world.level.cube.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ImposterProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStorage;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.SerializableCubeData;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestSerializableCubeData extends BaseTest {
    @TempDir
    Path directory;

    private ServerLevel level() {
        ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
        Misc.setupLevelHeight(level, -64, 384);
        when(level.getGameTime()).thenReturn(2000L);
        when(level.getSeed()).thenReturn(45L);
        when(level.getServer().registryAccess()).thenReturn(VanillaRegistriesExtension.shared());
        return level;
    }

    private ProtoCube proto(ServerLevel level, CubePos pos) {
        return new ProtoCube(pos, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
    }

    private ProtoCube reopen(ServerLevel level, CubeAccess cube) throws Exception {
        return reopenTag(level, cube.cc_getCubePos(), SerializableCubeData.copyOf(level, cube).write());
    }

    private ProtoCube reopenTag(ServerLevel level, CubePos pos, CompoundTag data) throws Exception {
        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(pos, data).join();
        }
        try (CubeStorage reopened = new CubeStorage(directory)) {
            CompoundTag saved = reopened.read(pos).join().orElseThrow();
            return SerializableCubeData.parse(saved, pos).read(level);
        }
    }

    @Test
    void protoSurvivesDiskReopenWithAllSectionsBiomesTicksEntitiesAndStructures() throws Exception {
        ServerLevel level = level();
        CubePos pos = CubePos.of(-17, -100000, 31);
        ProtoCube cube = proto(level, pos);
        cube.setPersistedStatus(ChunkStatus.FEATURES);
        cube.setInhabitedTime(123456789L);
        var desert = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.DESERT);
        for (int i = 0; i < CubicConstants.SECTION_COUNT; i++) {
            var section = cube.getSection(i);
            section.setBlockState(1, 2, 3, i % 2 == 0 ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.WATER.defaultBlockState());
            section.fillBiomesFromNoise((x, y, z) -> desert, 0, 0, 0);
            cube.addPackedPostProcess(ShortArrayList.wrap(new short[] { (short) (i + 1) }), i);
        }
        BlockPos blockPos = pos.asBlockPos(5, 6, 7);
        cube.setBlockState(blockPos, Blocks.CHEST.defaultBlockState());
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "minecraft:chest");
        blockEntity.putInt("x", blockPos.getX());
        blockEntity.putInt("y", blockPos.getY());
        blockEntity.putInt("z", blockPos.getZ());
        blockEntity.putBoolean("keepPacked", true);
        blockEntity.putString("PersistenceProbe", "inventory data is retained");
        cube.setBlockEntityNbt(blockEntity);
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:pig");
        entity.putString("PersistenceProbe", "proto entity data is retained");
        cube.addEntity(entity);
        cube.getBlockTicks().schedule(new ScheduledTick<>(Blocks.STONE, blockPos, 2010, TickPriority.HIGH, 0));
        cube.getFluidTicks().schedule(new ScheduledTick<>(Fluids.WATER, blockPos, 2020, TickPriority.LOW, 1));
        var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(BuiltinStructures.BURIED_TREASURE);
        var start = new StructureStart(structure, new ChunkPos(-34, 62), 2,
                new PiecesContainer(List.of(new BuriedTreasurePieces.BuriedTreasurePiece(blockPos))));
        cube.setStartForStructure(structure, start);
        cube.addReferenceForStructure(structure, CubePos.of(-17, -99999, 31).asLong());
        cube.markUnsaved();

        ProtoCube loaded = reopen(level, cube);
        assertEquals(pos, loaded.cc_getCubePos());
        assertEquals(ChunkStatus.FEATURES, loaded.getPersistedStatus());
        assertEquals(cube.getInhabitedTime(), loaded.getInhabitedTime());
        for (int i = 0; i < CubicConstants.SECTION_COUNT; i++) {
            assertEquals(cube.getSection(i).getBlockState(1, 2, 3), loaded.getSection(i).getBlockState(1, 2, 3));
            assertEquals(desert, loaded.getSection(i).getNoiseBiome(0, 0, 0));
            assertEquals(cube.getPostProcessing()[i], loaded.getPostProcessing()[i]);
        }
        assertEquals(blockEntity, loaded.getBlockEntityNbts().get(blockPos));
        assertEquals(cube.getEntities(), loaded.getEntities());
        assertEquals(cube.getTicksForSerialization(2000), loaded.getTicksForSerialization(2000));
        assertEquals(start.getChunkPos(), loaded.getStartForStructure(structure).getChunkPos());
        assertEquals(start.getReferences(), loaded.getStartForStructure(structure).getReferences());
        assertEquals(start.getBoundingBox(), loaded.getStartForStructure(structure).getBoundingBox());
        assertEquals(cube.getAllReferences(), loaded.getAllReferences());
        assertFalse(loaded.isUnsaved());
        assertFalse(loaded.isLightCorrect());
        assertTrue(cube.isUnsaved(), "Taking a snapshot must not mark the live cube saved");
    }

    @Test
    void fullCubeAtExtremeYRetainsInventoryAndRelativeTickDelay() throws Exception {
        ServerLevel level = level();
        CubePos pos = CubePos.of(3, 1000000, -8);
        LevelCube cube = new LevelCube(level, pos);
        BlockPos blockPos = pos.asBlockPos(9, 10, 11);
        cube.getSection(Coords.blockToIndex(blockPos)).setBlockState(9, 10, 11, Blocks.CHEST.defaultBlockState());
        ChestBlockEntity chest = new ChestBlockEntity(blockPos, Blocks.CHEST.defaultBlockState());
        chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
        cube.setBlockEntity(chest);
        cube.getBlockTicks().schedule(new ScheduledTick<>(Blocks.STONE, blockPos, 2017, TickPriority.VERY_HIGH, 0));
        cube.setInhabitedTime(991);

        ImposterProtoCube loaded = assertInstanceOf(ImposterProtoCube.class, reopen(level, cube));
        LevelCube full = (LevelCube) loaded.cc_getWrappedClo();
        assertEquals(ChunkStatus.FULL, full.getPersistedStatus());
        assertEquals(991, full.getInhabitedTime());
        assertEquals(cube.getBlockState(blockPos), full.getBlockState(blockPos));
        // A save before runPostLoad must still retain the block entity tag.
        assertEquals(1, SerializableCubeData.copyOf(level, full).write().getListOrEmpty("block_entities").size());
        full.runPostLoad();
        ChestBlockEntity restored = assertInstanceOf(ChestBlockEntity.class, full.getBlockEntity(blockPos));
        assertEquals(7, restored.getItem(0).getCount());
        assertTrue(restored.getItem(0).is(Items.DIAMOND));
        LevelChunkTicks<?> ticks = (LevelChunkTicks<?>) full.getBlockTicks();
        ticks.unpack(4000);
        assertEquals(4017, ticks.peek().triggerTick());
        assertEquals(TickPriority.VERY_HIGH, ticks.peek().priority());
    }

    @Test
    void snapshotIsDetachedFromLiveCubeAndReturnedTags() {
        ServerLevel level = level();
        ProtoCube cube = proto(level, CubePos.of(1, -20, 2));
        BlockPos pos = cube.cc_getCubePos().asBlockPos(1, 2, 3);
        cube.setBlockState(pos, Blocks.STONE.defaultBlockState());
        SerializableCubeData snapshot = SerializableCubeData.copyOf(level, cube);
        cube.setBlockState(pos, Blocks.DIRT.defaultBlockState());
        cube.setBlockState(cube.cc_getCubePos().asBlockPos(1, 20, 3), Blocks.OAK_LEAVES.defaultBlockState());
        CompoundTag external = snapshot.write();
        external.putString("Status", "minecraft:full");
        external.getListOrEmpty("sections").clear();
        external.getCompoundOrEmpty("CubeHeightmaps").putLongArray(Heightmap.Types.WORLD_SURFACE.getSerializationKey(), new long[0]);
        ProtoCube loaded = snapshot.read(level);
        assertEquals(Blocks.STONE.defaultBlockState(), loaded.getBlockState(pos));
        assertEquals(ChunkStatus.EMPTY, loaded.getPersistedStatus());
        assertEquals(pos.getY(), loaded.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ()));
        assertEquals(cube.getMinY() + 20, cube.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ()));
    }

    @Test
    void mismatchedMetadataAndCorruptPalettesFailInsteadOfRegenerating() {
        ServerLevel level = level();
        CubePos pos = CubePos.of(-2, -3, -4);
        ProtoCube cube = proto(level, pos);
        cube.getBlockTicks().schedule(new ScheduledTick<>(Blocks.STONE, pos.asBlockPos(1, 2, 3), 2010, 0));
        CompoundTag original = SerializableCubeData.copyOf(level, cube).write();
        List<Consumer<CompoundTag>> corruptions = List.of(tag -> tag.putInt("CubeFormat", SerializableCubeData.FORMAT_VERSION + 1),
                tag -> tag.putInt("DataVersion", 1), tag -> tag.putInt("yPos", pos.getY() + 1), tag -> tag.putInt("CubeDiameter", 9),
                tag -> tag.putString("Status", "missing:status"), tag -> tag.putBoolean("isLightOn", true), tag -> tag.putInt("isLightOn", 0),
                tag -> tag.putString("UnexpectedSystemData", "must not be discarded"), tag -> tag.getListOrEmpty("sections").remove(0),
                tag -> ((CompoundTag) tag.getListOrEmpty("sections").get(1)).putInt("i", 0),
                tag -> ((CompoundTag) tag.getListOrEmpty("sections").get(0)).put("block_states", new CompoundTag()),
                tag -> ((ListTag) tag.getListOrEmpty("PostProcessing").get(0)).add(IntTag.valueOf(65536)),
                tag -> ((CompoundTag) tag.getListOrEmpty("block_ticks").get(0)).putInt("p", TickPriority.EXTREMELY_LOW.getValue() + 1),
                tag -> ((CompoundTag) tag.getListOrEmpty("block_ticks").get(0)).putLong("t", 1L << 32));
        for (Consumer<CompoundTag> corrupt : corruptions) {
            CompoundTag data = original.copy();
            corrupt.accept(data);
            assertThrows(IllegalArgumentException.class, () -> SerializableCubeData.parse(data, pos).read(level));
        }
    }

    @Test
    void unsupportedLitCubeAndMisplacedTicksCannotBeSaved() {
        ServerLevel level = level();
        ProtoCube cube = proto(level, CubePos.of(0, 10, 0));
        cube.setLightCorrect(true);
        assertThrows(IllegalArgumentException.class, () -> SerializableCubeData.copyOf(level, cube));
        cube.setLightCorrect(false);
        cube.getBlockTicks().schedule(new ScheduledTick<>(Blocks.STONE, BlockPos.ZERO, 2010, 0));
        assertThrows(IllegalArgumentException.class, () -> SerializableCubeData.copyOf(level, cube));
    }

    @Test
    void aliasedCoordinatesAndDuplicateRecordsAreRejected() {
        ServerLevel level = level();
        CubePos pos = CubePos.of(1, 2, 3);
        ProtoCube cube = proto(level, pos);
        BlockPos blockPos = pos.asBlockPos(1, 2, 3);
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "minecraft:chest");
        blockEntity.putInt("x", blockPos.getX());
        blockEntity.putInt("y", blockPos.getY());
        blockEntity.putInt("z", blockPos.getZ());
        blockEntity.putBoolean("keepPacked", true);
        cube.setBlockEntityNbt(blockEntity);
        cube.getBlockTicks().schedule(new ScheduledTick<>(Blocks.STONE, blockPos, 2010, 0));
        CompoundTag original = SerializableCubeData.copyOf(level, cube).write();
        for (String key : List.of("block_entities", "block_ticks")) {
            CompoundTag duplicate = original.copy();
            ListTag values = duplicate.getListOrEmpty(key);
            values.add(values.get(0).copy());
            assertThrows(IllegalArgumentException.class, () -> SerializableCubeData.parse(duplicate, pos).read(level));
        }
        CubePos aliased = CubePos.of(Integer.MAX_VALUE, 2, 3);
        CompoundTag invalid = original.copy();
        invalid.putInt("xPos", Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> SerializableCubeData.parse(invalid, aliased));
        assertThrows(IllegalArgumentException.class, () -> SerializableCubeData.copyOf(level, proto(level, aliased)));
    }

    @Test
    void missingEntityTypesFailWhileVanillaDummyBlockEntitiesRemainPacked() {
        ServerLevel level = level();
        CubePos pos = CubePos.of(2, -11, 3);
        ProtoCube cube = proto(level, pos);
        BlockPos blockPos = pos.asBlockPos(1, 2, 3);
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "DUMMY");
        blockEntity.putInt("x", blockPos.getX());
        blockEntity.putInt("y", blockPos.getY());
        blockEntity.putInt("z", blockPos.getZ());
        blockEntity.putBoolean("keepPacked", true);
        cube.setBlockEntityNbt(blockEntity);
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:pig");
        cube.addEntity(entity);
        CompoundTag original = SerializableCubeData.copyOf(level, cube).write();
        assertEquals(blockEntity, SerializableCubeData.parse(original, pos).read(level).getBlockEntityNbts().get(blockPos));
        for (String key : List.of("block_entities", "entities")) {
            for (String id : List.of("missing_mod:entity", "Invalid id!")) {
                CompoundTag invalid = original.copy();
                ((CompoundTag) invalid.getListOrEmpty(key).get(0)).putString("id", id);
                assertThrows(IllegalArgumentException.class, () -> SerializableCubeData.parse(invalid, pos).read(level));
            }
        }
        CompoundTag passenger = new CompoundTag();
        passenger.putString("id", "missing_mod:passenger");
        ListTag passengers = new ListTag();
        passengers.add(passenger);
        entity.put("Passengers", passengers);
        assertThrows(IllegalArgumentException.class, () -> SerializableCubeData.copyOf(level, cube));
    }

    @Test
    void allHeightmapTypesSurviveDiskReopenAtBothVerticalLimits() throws Exception {
        ServerLevel level = level();
        for (int cubeY : new int[] { -CubePos.MAX_COORDINATE_VALUE - 1, CubePos.MAX_COORDINATE_VALUE }) {
            for (boolean full : new boolean[] { false, true }) {
                CubePos pos = CubePos.of(-4, cubeY, 5);
                ProtoCube source = heightmapProbe(level, pos);
                source.cc_snapshotHeightmaps();
                CubeAccess cube = full ? new LevelCube(level, source, null) : source;
                CubeAccess loaded = unwrap(reopen(level, cube));
                assertEquals(full ? ChunkStatus.FULL : source.getPersistedStatus(), loaded.getPersistedStatus());
                assertHeightmapsMatchBlocks(loaded);
                var expectedMaps = cube.cc_snapshotHeightmaps();
                var actualMaps = loaded.cc_snapshotHeightmaps();
                for (Heightmap.Types type : Heightmap.Types.values()) {
                    assertArrayEquals(expectedMaps.get(type), actualMaps.get(type), type.getSerializationKey());
                    assertEquals(pos.minCubeY() - 1, loaded.getHeight(type, pos.minCubeX() + 2, pos.minCubeZ() + 2));
                    assertEquals(pos.minCubeY() + CubicConstants.DIAMETER_IN_BLOCKS - 1,
                            loaded.getHeight(type, pos.minCubeX() + 31, pos.minCubeZ() + 31));
                }
                assertFalse(loaded.isUnsaved());
            }
        }
    }

    @Test
    void formatOneRebuildsDerivedHeightmapsAndNextSaveWritesFormatTwo() throws Exception {
        ServerLevel level = level();
        CubePos pos = CubePos.of(3, -50000, -9);
        for (boolean full : new boolean[] { false, true }) {
            ProtoCube source = heightmapProbe(level, pos);
            CubeAccess cube = full ? new LevelCube(level, source, null) : source;
            CompoundTag legacy = SerializableCubeData.copyOf(level, cube).write();
            legacy.putInt("CubeFormat", 1);
            legacy.remove("CubeHeightmaps");
            CubeAccess loaded = unwrap(reopenTag(level, pos, legacy));
            assertHeightmapsMatchBlocks(loaded);
            assertFalse(loaded.isUnsaved(), "Reconstructing derived maps must not change the saved blocks");
            assertHeightmapsMatchBlocks(unwrap(reopen(level, loaded)));
            try (CubeStorage storage = new CubeStorage(directory)) {
                CompoundTag upgraded = storage.read(pos).join().orElseThrow();
                assertEquals(2, upgraded.getInt("CubeFormat").orElseThrow());
                assertEquals(Heightmap.Types.values().length, upgraded.getCompoundOrEmpty("CubeHeightmaps").size());
            }
        }
    }

    @Test
    void malformedHeightmapsFailWithoutBeingRebuiltOrTruncated() {
        ServerLevel level = level();
        CubePos pos = CubePos.of(-5, -12, 7);
        CompoundTag original = SerializableCubeData.copyOf(level, heightmapProbe(level, pos)).write();
        String surface = Heightmap.Types.WORLD_SURFACE.getSerializationKey();
        List<Consumer<CompoundTag>> corruptions = List.of(tag -> tag.remove("CubeHeightmaps"), tag -> tag.putInt("CubeFormat", 1),
                tag -> tag.put("CubeHeightmaps", new ListTag()), tag -> tag.getCompoundOrEmpty("CubeHeightmaps").remove(surface),
                tag -> tag.getCompoundOrEmpty("CubeHeightmaps").putLongArray("UNKNOWN_TYPE", new long[103]),
                tag -> tag.getCompoundOrEmpty("CubeHeightmaps").putIntArray(surface, new int[103]),
                tag -> tag.getCompoundOrEmpty("CubeHeightmaps").putLongArray(surface, new long[102]),
                tag -> corruptHeightmapWord(tag, surface, 0, 63L), tag -> corruptHeightmapWord(tag, surface, 0, 1L << 63),
                tag -> corruptHeightmapWord(tag, surface, 102, 1L << 24));
        for (Consumer<CompoundTag> corrupt : corruptions) {
            CompoundTag invalid = original.copy();
            corrupt.accept(invalid);
            assertThrows(IllegalArgumentException.class, () -> SerializableCubeData.parse(invalid, pos));
        }
        CompoundTag wrongDataVersion = original.copy();
        wrongDataVersion.putInt("CubeFormat", 1);
        wrongDataVersion.remove("CubeHeightmaps");
        wrongDataVersion.putInt("DataVersion", 1);
        assertThrows(IllegalArgumentException.class, () -> SerializableCubeData.parse(wrongDataVersion, pos));
    }

    @Test
    void surfaceSnapshotsRebuildLegacySectionsWithoutLoadingOtherWorldData() {
        ServerLevel level = level();
        CubePos pos = CubePos.of(-6, -100000, 7);
        ProtoCube source = heightmapProbe(level, pos);
        LevelCube cube = new LevelCube(level, source, null);
        var expected = cube.cc_snapshotHeightmaps();
        for (int format : new int[] { 1, 2 }) {
            CompoundTag tag = SerializableCubeData.copyOf(level, cube).write();
            tag.putInt("CubeFormat", format);
            if (format == 1) {
                tag.remove("CubeHeightmaps");
            }
            // A surface snapshot must not attempt block-entity or structure loading.
            tag.getListOrEmpty("block_entities").add(new CompoundTag());
            tag.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").put("missing:structure", new CompoundTag());
            SerializableCubeData saved = SerializableCubeData.parse(tag, pos);
            assertThrows(IllegalArgumentException.class, () -> saved.read(level));
            var first = saved.surfaceHeightmaps(level.palettedContainerFactory());
            for (Heightmap.Types type : Heightmap.Types.values()) {
                assertArrayEquals(expected.get(type), first.get(type));
                first.get(type)[0] = -1L;
            }
            first.clear();
            var second = saved.surfaceHeightmaps(level.palettedContainerFactory());
            for (Heightmap.Types type : Heightmap.Types.values()) {
                assertArrayEquals(expected.get(type), second.get(type));
            }
            assertEquals(format, saved.write().getInt("CubeFormat").orElseThrow());
        }
    }

    private static void corruptHeightmapWord(CompoundTag tag, String type, int index, long value) {
        CompoundTag heightmaps = tag.getCompoundOrEmpty("CubeHeightmaps");
        long[] packed = heightmaps.getLongArray(type).orElseThrow().clone();
        packed[index] = value;
        heightmaps.putLongArray(type, packed);
    }

    private ProtoCube heightmapProbe(ServerLevel level, CubePos pos) {
        ProtoCube cube = proto(level, pos);
        cube.setPersistedStatus(ChunkStatus.FEATURES);
        setProbeBlock(cube, 0, 0, 0, Blocks.STONE.defaultBlockState());
        setProbeBlock(cube, 15, 15, 15, Blocks.STONE.defaultBlockState());
        setProbeBlock(cube, 15, 16, 15, Blocks.WATER.defaultBlockState());
        setProbeBlock(cube, 15, 31, 15, Blocks.OAK_LEAVES.defaultBlockState());
        setProbeBlock(cube, 16, 16, 16, Blocks.STONE.defaultBlockState());
        setProbeBlock(cube, 16, 31, 16, Blocks.SHORT_GRASS.defaultBlockState());
        setProbeBlock(cube, 31, 31, 31, Blocks.STONE.defaultBlockState());
        return cube;
    }

    private static void setProbeBlock(CubeAccess cube, int x, int y, int z, BlockState state) {
        cube.setBlockState(cube.cc_getCubePos().asBlockPos(x, y, z), state);
    }

    private static CubeAccess unwrap(ProtoCube cube) {
        return cube instanceof ImposterProtoCube imposter ? (LevelCube) imposter.cc_getWrappedClo() : cube;
    }

    private static void assertHeightmapsMatchBlocks(CubeAccess cube) {
        CubePos pos = cube.cc_getCubePos();
        for (Heightmap.Types type : Heightmap.Types.values()) {
            for (int z = 0; z < CubicConstants.DIAMETER_IN_BLOCKS; z++) {
                for (int x = 0; x < CubicConstants.DIAMETER_IN_BLOCKS; x++) {
                    int expected = cube.getMinY() - 1;
                    for (int y = CubicConstants.DIAMETER_IN_BLOCKS - 1; y >= 0; y--) {
                        if (type.isOpaque().test(cube.getBlockState(pos.asBlockPos(x, y, z)))) {
                            expected = pos.minCubeY() + y;
                            break;
                        }
                    }
                    assertEquals(expected, cube.getHeight(type, pos.minCubeX() + x, pos.minCubeZ() + z),
                            type.getSerializationKey() + " at " + pos + ": " + x + "," + z);
                }
            }
        }
    }
}
