package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestDeepStructureFeatures extends BaseTest {
    @TempDir
    Path directory;

    @Test
    void nativeMonsterRoomsKeepConfiguredSpawnersAndDungeonLootAcrossCubeBoundaries() throws Exception {
        for (int y : new int[] { -320, -32000 }) {
            try (var fixture = new Fixture(directory.resolve("room" + y), Biomes.PLAINS)) {
                var candidate = fixture.candidate("monster_room", y);
                var origin = candidate.origin();
                fixture.blocks = pos -> pos.getZ() == origin.getZ() && pos.getX() >= origin.getX() + 3
                        && pos.getY() >= origin.getY() && pos.getY() <= origin.getY() + 1 ? Blocks.CAVE_AIR.defaultBlockState()
                                : Blocks.DEEPSLATE.defaultBlockState();
                var result = fixture.generate(candidate);
                var generated = fixture.blocks(result);
                assertTrue(generated.get(origin).is(Blocks.SPAWNER));
                assertTrue(generated.values().stream().anyMatch(value -> value.is(Blocks.MOSSY_COBBLESTONE)));
                var tags = fixture.blockEntities(result);
                var spawner = tags.stream().filter(tag -> tag.getString("id").orElse("").equals("minecraft:mob_spawner")).findFirst().orElseThrow();
                assertEquals(origin.getY(), spawner.getInt("y").orElseThrow());
                assertTrue(spawner.getCompoundOrEmpty("SpawnData").getCompoundOrEmpty("entity").getString("id").isPresent(),
                        "The generated spawner must retain its native entity configuration");
                assertTrue(tags.stream().anyMatch(tag -> tag.getString("LootTable").orElse("").equals("minecraft:chests/simple_dungeon")),
                        "Dungeon chests need their native deferred loot table");
                assertTrue(result.sections().size() > 1, "A complete room must include writes outside its candidate section");
                assertSame(result, fixture.generate(candidate), "Complete results must be reused across requests");
            }
        }
    }

    @Test
    void genuineGeodesAndBothFossilVariantsRetainTheirMaterialsAtDeepY() throws Exception {
        for (int y : new int[] { -320, -32000 }) {
            try (var fixture = new Fixture(directory.resolve("features" + y), Biomes.DESERT)) {
                var geode = fixture.generate(fixture.candidate("amethyst_geode", y));
                var states = fixture.blocks(geode).values();
                for (var block : List.of(Blocks.SMOOTH_BASALT, Blocks.CALCITE, Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST)) {
                    assertTrue(states.stream().anyMatch(value -> value.is(block)), "Native geode missing " + block + " at " + y);
                }
                assertTrue(states.stream().anyMatch(BlockState::isAir));
                for (String name : List.of("fossil_coal", "fossil_diamonds")) {
                    var candidate = fixture.candidate(name, y);
                    var fossil = fixture.generate(candidate);
                    var bones = fixture.blocks(fossil).entrySet().stream().filter(entry -> entry.getValue().is(Blocks.BONE_BLOCK)).toList();
                    assertFalse(bones.isEmpty(), "Native fossil template missing for " + name);
                    assertTrue(bones.stream().allMatch(entry -> entry.getKey().getY() < candidate.origin().getY()),
                            "Fossils must bury below their actual candidate height, not a band's ceiling");
                    assertTrue(bones.stream().allMatch(entry -> entry.getKey().getY() > candidate.origin().getY() - 64));
                }
            }
        }
    }

    @Test
    void biomeMembershipStructureConflictsAndNativeFloorPreventUnrelatedWrites() throws Exception {
        try (var fixture = new Fixture(directory.resolve("eligibility"), Biomes.PLAINS)) {
            var fossil = fixture.candidate("fossil_coal", -32000);
            assertTrue(fixture.generateAll(fossil).isEmpty(), "Plains must not acquire desert/swamp fossils");
            int reads = fixture.terrainReads.get();
            assertTrue(fixture.generateAll(fossil).isEmpty());
            assertEquals(reads, fixture.terrainReads.get(), "Rejected feature outcomes must be cached");
            var geode = fixture.candidate("amethyst_geode", -32000);
            assertTrue(fixture.features.intersecting(fixture.features.bounds(geode), bounds -> true).isEmpty());
            assertEquals(reads, fixture.terrainReads.get(), "A structure conflict must reject before terrain generation");
            assertTrue(fixture.features.intersecting(new BoundingBox(-16, -64, -16, 16, 319, 16), bounds -> false).isEmpty());
            var generator = fixture.level.getChunkSource().getGenerator();
            doReturn(BiomeGenerationSettings.EMPTY).when(generator).getBiomeGenerationSettings(any());
            assertTrue(fixture.generateAll(geode).isEmpty(), "Generator-provided biome settings override the biome's original feature membership");
        }
    }

    @Test
    void rejectedAndIneligibleFeaturesSkipStructureLookupsWithoutReadingMoreTerrain() throws Exception {
        try (var fixture = new Fixture(directory.resolve("preflight"), Biomes.PLAINS)) {
            var room = fixture.candidate("monster_room", -32000);
            var wanted = fixture.features.bounds(room);
            var roomChecks = new AtomicInteger();
            java.util.function.Predicate<BoundingBox> conflict = bounds -> {
                if (bounds.equals(wanted)) {
                    roomChecks.incrementAndGet();
                    return false;
                }
                return true;
            };
            // Solid terrain has no cave entrance, so vanilla rejects the first room attempt.
            assertTrue(fixture.features.intersecting(wanted, conflict).isEmpty());
            assertEquals(1, roomChecks.get());
            int reads = fixture.terrainReads.get();
            assertTrue(reads > 0);
            assertTrue(fixture.features.intersecting(wanted, conflict).isEmpty());
            assertEquals(1, roomChecks.get(), "Cached native rejection must skip structure discovery");
            assertEquals(reads, fixture.terrainReads.get());

            var generator = fixture.level.getChunkSource().getGenerator();
            doReturn(BiomeGenerationSettings.EMPTY).when(generator).getBiomeGenerationSettings(any());
            var ineligibleChecks = new AtomicInteger();
            assertTrue(fixture.features.intersecting(wanted, bounds -> {
                ineligibleChecks.incrementAndGet();
                return true;
            }).isEmpty());
            assertEquals(0, ineligibleChecks.get(), "Biome rejection must precede structure discovery");
            assertEquals(reads, fixture.terrainReads.get());
        }
    }

    @Test
    void candidatesAndCompleteWritesDoNotDependOnQueriesOrPackedVerticalAliases() throws Exception {
        try (var fixture = new Fixture(directory.resolve("ordering"), Biomes.DESERT)) {
            var first = fixture.candidate("amethyst_geode", -32000);
            var complete = fixture.generate(first);
            var expected = fixture.blocks(complete);
            for (var section : complete.sections()) {
                var query = new BoundingBox(section.minBlockX(), section.minBlockY(), section.minBlockZ(), section.maxBlockX(),
                        section.maxBlockY(), section.maxBlockZ());
                var containing = fixture.features.intersecting(query, bounds -> !bounds.equals(fixture.features.bounds(first)));
                assertEquals(List.of(complete), containing, "Every intersecting section must see the same complete feature");
            }
            var far = fixture.candidate("amethyst_geode", -32000 - (1 << 24));
            assertNotEquals(first.seed(), far.seed());
            assertEquals(expected, fixture.blocks(fixture.generate(first)));
            assertTrue(fixture.blocks(fixture.generate(far)).keySet().stream().allMatch(pos -> pos.getY() < -10000000));
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
        private final MultiPackResourceManager resources;
        private final DeepStructureFeatures features;
        private final AtomicInteger terrainReads = new AtomicInteger();
        private Function<BlockPos, BlockState> blocks = pos -> Blocks.DEEPSLATE.defaultBlockState();

        private Fixture(Path directory, ResourceKey<Biome> biomeKey) {
            Misc.setupLevelHeight(level, -64, 384);
            when(level.getSeed()).thenReturn(37L);
            when(level.getRandom()).thenReturn(RandomSource.create(37));
            var packs = ServerPacksSource.createVanillaTrustedRepository();
            packs.reload();
            packs.setSelected(List.of("vanilla"));
            resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs.openAllSelected());
            LevelStorageAccess storage = mock();
            when(storage.getLevelPath(LevelResource.GENERATED_DIR)).thenReturn(directory);
            var templates = new StructureTemplateManager(resources, storage, DataFixers.getDataFixer(), BuiltInRegistries.BLOCK);
            when(level.getStructureTemplateManager()).thenReturn(templates);
            when(level.getServer().getStructureTemplateManager()).thenReturn(templates);
            var registries = level.registryAccess().freeze();
            when(level.getServer().registryAccess()).thenReturn(registries);
            when(level.dimensionType()).thenReturn(registries.lookupOrThrow(Registries.DIMENSION_TYPE)
                    .getOrThrow(BuiltinDimensionTypes.OVERWORLD).value());
            var settings = level.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
            when(level.getChunkSource().randomState()).thenReturn(RandomState.create(registries.lookupOrThrow(Registries.NOISE), 37, settings));
            when(level.getChunkSource().getGenerator().getBiomeGenerationSettings(any())).thenAnswer(call ->
                    call.getArgument(0, Holder.class).value() instanceof Biome biome ? biome.getGenerationSettings() : BiomeGenerationSettings.EMPTY);
            var biome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(biomeKey);
            when(level.uncachedBiomeResolver()).thenReturn((x, y, z) -> biome);
            features = new DeepStructureFeatures(level, (x, y, z) -> biome, this::section, -64);
        }

        private LevelChunkSection section(int sx, int sy, int sz) {
            terrainReads.incrementAndGet();
            var section = new LevelChunkSection(level.palettedContainerFactory());
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        section.setBlockState(x, y, z, blocks.apply(new BlockPos(sx * 16 + x, sy * 16 + y, sz * 16 + z)), false);
                    }
                }
            }
            return section;
        }

        private DeepStructureFeatures.Candidate candidate(String type, int y) {
            return features.candidates(new BoundingBox(-256, y - 160, -256, 256, y + 160, 256)).stream()
                    .filter(value -> value.feature().equals(type))
                    .filter(value -> type.startsWith("fossil_") || Math.floorMod(value.origin().getX(), 32) < 3
                            || Math.floorMod(value.origin().getX(), 32) > 29)
                    .findFirst().orElseThrow(() -> new AssertionError("No deterministic candidate for " + type + " at " + y));
        }

        private List<DeepStructureResult> generateAll(DeepStructureFeatures.Candidate candidate) {
            var wanted = features.bounds(candidate);
            return features.intersecting(wanted, bounds -> !bounds.equals(wanted));
        }

        private DeepStructureResult generate(DeepStructureFeatures.Candidate candidate) {
            var generated = generateAll(candidate);
            assertEquals(1, generated.size(), "Native feature rejected candidate " + candidate);
            return generated.getFirst();
        }

        private Map<BlockPos, BlockState> blocks(DeepStructureResult result) {
            Map<BlockPos, BlockState> states = new HashMap<>();
            for (SectionPos pos : result.sections()) {
                var section = new LevelChunkSection(level.palettedContainerFactory());
                result.apply(section, pos.x(), pos.y(), pos.z());
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            states.put(new BlockPos(pos.minBlockX() + x, pos.minBlockY() + y, pos.minBlockZ() + z),
                                    section.getBlockState(x, y, z));
                        }
                    }
                }
            }
            return states;
        }

        private List<CompoundTag> blockEntities(DeepStructureResult result) {
            List<CompoundTag> tags = new ArrayList<>();
            var cubes = result.sections().stream().map(pos -> CubePos.from(new BlockPos(pos.minBlockX(), pos.minBlockY(), pos.minBlockZ())))
                    .distinct().toList();
            for (var pos : cubes) {
                var cube = new io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube(pos, UpgradeData.EMPTY, level,
                        level.palettedContainerFactory(), null);
                for (int i = 0; i < CubicConstants.SECTION_COUNT; i++) {
                    result.apply(cube.getSections()[i], Coords.cubeToSection(pos.getX(), Coords.indexToX(i)),
                            Coords.cubeToSection(pos.getY(), Coords.indexToY(i)), Coords.cubeToSection(pos.getZ(), Coords.indexToZ(i)));
                    result.copyExtras(cube, i);
                }
                tags.addAll(cube.getBlockEntityNbts().values());
            }
            return tags;
        }

        @Override public void close() {
            resources.close();
        }
    }
}
