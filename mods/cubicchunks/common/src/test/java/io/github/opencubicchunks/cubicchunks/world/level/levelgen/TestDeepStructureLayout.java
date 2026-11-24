package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.SerializableCubeData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestDeepStructureLayout extends BaseTest {
    @TempDir Path directory;

    @Test
    void realVanillaGraphsAndTemplatesGenerateAtDeepAndExtremeHeightsWithoutTruncation() throws Exception {
        var cases = Map.of(BuiltinStructures.MINESHAFT, Biomes.PLAINS, BuiltinStructures.MINESHAFT_MESA, Biomes.BADLANDS,
                BuiltinStructures.STRONGHOLD, Biomes.PLAINS, BuiltinStructures.ANCIENT_CITY, Biomes.DEEP_DARK,
                BuiltinStructures.TRIAL_CHAMBERS, Biomes.PLAINS, BuiltinStructures.RUINED_PORTAL_STANDARD, Biomes.PLAINS);
        for (var entry : cases.entrySet()) {
            try (var fixture = new Fixture(entry.getValue())) {
                var layout = new DeepStructureLayout(fixture.level, fixture.biomes);
                var holder = fixture.level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(entry.getKey());
                for (int y : new int[] { -32000, -32000000 }) {
                    var start = layout.findNearestStart(HolderSet.direct(holder), new BlockPos(-32, y, -48), 256);
                    assertNotNull(start, "No eligible native structure found: " + entry.getKey() + " at " + y);
                    var copied = start.copy(fixture.level);
                    assertEquals(holder.value(), copied.getStructure());
                    assertFalse(copied.getPieces().isEmpty());
                    int bandMin = layout.bandMinY(layout.bandAt(start.bounds().minY()));
                    assertTrue(start.bounds().maxY() < bandMin + DeepStructureLayout.BAND_HEIGHT);
                    assertTrue(start.bounds().maxY() < fixture.level.getMinY());
                    for (var piece : copied.getPieces()) {
                        assertTrue(piece.getBoundingBox().minY() >= bandMin);
                        assertTrue(piece.getBoundingBox().maxY() < bandMin + DeepStructureLayout.BAND_HEIGHT);
                        if (piece instanceof PoolElementStructurePiece pool) {
                            assertTrue(pool.getPosition().getY() >= bandMin && pool.getPosition().getY() < bandMin + DeepStructureLayout.BAND_HEIGHT);
                            for (var junction : pool.getJunctions()) {
                                assertTrue(junction.getSourceGroundY() >= bandMin
                                        && junction.getSourceGroundY() < bandMin + DeepStructureLayout.BAND_HEIGHT,
                                        "Jigsaw junction retained its old native-height position");
                            }
                        }
                    }
                    if (entry.getKey() == BuiltinStructures.STRONGHOLD) {
                        assertTrue(copied.getPieces().stream().anyMatch(piece -> piece.getClass().getSimpleName().equals("PortalRoom")),
                                "A real stronghold must retain its End portal room");
                    } else if (entry.getKey() == BuiltinStructures.RUINED_PORTAL_STANDARD) {
                        for (var piece : copied.getPieces()) {
                            assertEquals("underground", piece.createTag(StructurePieceSerializationContext.fromLevel(fixture.level))
                                    .getString("VerticalPlacement").orElseThrow());
                        }
                    } else {
                        assertTrue(copied.getPieces().size() > 1, "Expected a complete connected vanilla structure graph");
                    }
                }
            }
        }
    }

    @Test
    void snapshotsAreIndependentAndOwnersAndReferencesSurviveCubeSerialization() throws Exception {
        try (var fixture = new Fixture(Biomes.PLAINS)) {
            var level = fixture.level;
            var requested = HolderSet.direct(level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.MINESHAFT));
            var first = new DeepStructureLayout(level, fixture.biomes);
            var second = new DeepStructureLayout(level, fixture.biomes);
            var near = new BlockPos(-128, -32000, -96);
            var start = first.findNearestStart(requested, near, 64);
            assertNotNull(start);
            second.findNearestStart(requested, near.offset(512, -512, 512), 64);
            var reordered = second.findNearestStart(requested, near, 64);
            assertNotNull(reordered);
            assertEquals(start.tag(), reordered.tag(), "Other candidate requests must not change the graph");
            var modified = start.copy(level);
            modified.getPieces().getFirst().move(0, 1024, 0);
            assertEquals(start.tag(), start.copy(level).createTag(StructurePieceSerializationContext.fromLevel(level), modified.getChunkPos()));
            var alteredTag = start.tag();
            alteredTag.putString("id", "invalid");
            assertTrue(start.copy(level).isValid());

            var cube = new ProtoCube(start.owner(), UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
            first.installStarts(cube);
            first.installReferences(cube);
            cube.setPersistedStatus(ChunkStatus.STRUCTURE_REFERENCES);
            assertNotNull(cube.getStartForStructure(start.structure()));
            assertTrue(cube.getReferencesForStructure(start.structure()).contains(start.owner().asLong()));
            var restored = SerializableCubeData.parse(SerializableCubeData.copyOf(level, cube).write(), start.owner()).read(level);
            assertEquals(cube.getAllReferences(), restored.getAllReferences());
            assertEquals(start.bounds(), restored.getStartForStructure(start.structure()).getBoundingBox());
            var below = new BoundingBox(start.bounds().minX(), first.bandMinY(first.bandAt(start.bounds().minY())), start.bounds().minZ(),
                    start.bounds().maxX(), start.bounds().minY() - 1, start.bounds().maxZ());
            assertTrue(first.covering(below).stream().anyMatch(found -> found.owner().equals(start.owner())));
        }
    }

    @Test
    void surfaceStructuresDisabledGenerationMissingSetsAndDisallowedBiomesAreNeverForced() throws Exception {
        var disabled = Misc.mockLevelWithPalettes(ServerLevel.class);
        when(disabled.dimension()).thenReturn(Level.OVERWORLD);
        var ignored = new DeepStructureLayout(disabled, (x, y, z) -> { throw new AssertionError("Disabled generation queried biomes"); });
        assertTrue(ignored.intersecting(new BoundingBox(-16, -1024, -16, 16, -992, 16)).isEmpty());
        verify(disabled.getChunkSource(), never()).getGeneratorState();

        try (var fixture = new Fixture(Biomes.PLAINS)) {
            var registry = fixture.level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            var layout = new DeepStructureLayout(fixture.level, fixture.biomes);
            var center = new BlockPos(0, -1024, 0);
            assertNull(layout.findNearest(HolderSet.direct(registry.getOrThrow(BuiltinStructures.VILLAGE_PLAINS)), center, 64));
            var voidBiome = fixture.level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.THE_VOID);
            var rejected = new DeepStructureLayout(fixture.level, (x, y, z) -> voidBiome);
            assertNull(rejected.findNearest(HolderSet.direct(registry.getOrThrow(BuiltinStructures.MINESHAFT)), center, 32));
            when(fixture.level.getChunkSource().getGeneratorState().possibleStructureSets()).thenReturn(List.of());
            var noSets = new DeepStructureLayout(fixture.level, fixture.biomes);
            assertTrue(noSets.intersecting(new BoundingBox(-256, -1600, -256, 256, -992, 256)).isEmpty());
        }
    }

    @Test
    void savedAndLoadedOwnerMetadataWinsOverSeedPredictionsAndMissingReadsAreNotCached() throws Exception {
        try (var fixture = new Fixture(Biomes.PLAINS)) {
            var level = fixture.level;
            var requested = HolderSet.direct(level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.MINESHAFT));
            Map<CubePos, CompoundTag> saved = new ConcurrentHashMap<>();
            var layout = new DeepStructureLayout(level, fixture.biomes,
                    owner -> CompletableFuture.completedFuture(Optional.ofNullable(saved.get(owner))));
            var candidate = layout.findNearestStart(requested, new BlockPos(0, -32000, 0), 64);
            assertNotNull(candidate);
            var owner = new ProtoCube(candidate.owner(), UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
            owner.setPersistedStatus(ChunkStatus.STRUCTURE_STARTS);
            // The first search saw no owner file. A later authoritative empty snapshot must suppress that same cached graph.
            saved.put(candidate.owner(), SerializableCubeData.copyOf(level, owner).write());
            assertTrue(layout.intersecting(candidate.bounds()).stream().noneMatch(start -> start.owner().equals(candidate.owner())));

            var persisted = candidate.copy(level);
            persisted.addReference();
            owner.setStartForStructure(candidate.structure(), persisted);
            saved.put(candidate.owner(), SerializableCubeData.copyOf(level, owner).write());
            var reopened = new DeepStructureLayout(level, fixture.biomes,
                    pos -> CompletableFuture.completedFuture(Optional.ofNullable(saved.get(pos))));
            var restored = reopened.intersecting(candidate.bounds()).stream().filter(start -> start.owner().equals(candidate.owner()))
                    .findFirst().orElseThrow();
            assertEquals(1, restored.copy(level).getReferences(), "Must retain the persisted start rather than regenerate it");
            owner.setAllStarts(Map.of());
            reopened.rememberLoaded(owner);
            assertTrue(reopened.covering(candidate.bounds()).stream().noneMatch(start -> start.owner().equals(candidate.owner())),
                    "Loaded empty starts override the older disk snapshot");
            assertNull(reopened.findNearestStart(requested, candidate.bounds().getCenter(), 64,
                    start -> start.owner().equals(candidate.owner())));
        }
    }

    @Test
    void ownerMissesAreReadOncePerTerrainRequestAndLaterSavesRemainVisible() throws Exception {
        try (var fixture = new Fixture(Biomes.PLAINS)) {
            var level = fixture.level;
            var requested = HolderSet.direct(level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.MINESHAFT));
            Map<CubePos, CompoundTag> saved = new ConcurrentHashMap<>();
            Map<CubePos, Integer> reads = new ConcurrentHashMap<>();
            var layout = new DeepStructureLayout(level, fixture.biomes, owner -> {
                reads.merge(owner, 1, Integer::sum);
                return CompletableFuture.completedFuture(Optional.ofNullable(saved.get(owner)));
            });
            var candidate = layout.findNearestStart(requested, new BlockPos(0, -32000, 0), 64);
            assertNotNull(candidate);
            reads.clear();
            var owner = new ProtoCube(candidate.owner(), UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
            owner.setPersistedStatus(ChunkStatus.STRUCTURE_STARTS);
            try (var scope = layout.ownerReadScope()) {
                assertTrue(layout.intersecting(candidate.bounds()).stream().anyMatch(start -> start.owner().equals(candidate.owner())));
                assertCoveringWithinNestedScope(layout, candidate);
                saved.put(candidate.owner(), SerializableCubeData.copyOf(level, owner).write());
                assertTrue(layout.intersecting(candidate.bounds()).stream().anyMatch(start -> start.owner().equals(candidate.owner())));
                assertEquals(1, reads.get(candidate.owner()).intValue(), "Repeated conflict queries must share an absent owner read");
            }
            try (var scope = layout.ownerReadScope()) {
                assertTrue(layout.intersecting(candidate.bounds()).stream().noneMatch(start -> start.owner().equals(candidate.owner())));
                assertEquals(2, reads.get(candidate.owner()).intValue(), "The next request must observe the newly saved empty owner");
            }
        }
    }

    @Test
    void metadataOwnerLookupPreservesSavedAuthorityAndExactXYZWithoutLoadingTerrain() throws Exception {
        try (var fixture = new Fixture(Biomes.PLAINS)) {
            var level = fixture.level;
            var holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.MINESHAFT);
            Map<CubePos, CompoundTag> saved = new ConcurrentHashMap<>();
            var layout = new DeepStructureLayout(level, fixture.biomes,
                    owner -> CompletableFuture.completedFuture(Optional.ofNullable(saved.get(owner))));
            var candidate = layout.findNearestStart(HolderSet.direct(holder), new BlockPos(0, -32000, 0), 64);
            assertNotNull(candidate);
            var inferred = layout.startForOwner(candidate.owner(), holder.value());
            assertNotNull(inferred);
            assertEquals(candidate.tag(), inferred.tag());
            var wrongY = CubePos.of(candidate.owner().getX(), candidate.owner().getY() + 1, candidate.owner().getZ());
            assertNull(layout.startForOwner(wrongY, holder.value()), "A different cube in the same band is not this owner");

            var owner = new ProtoCube(candidate.owner(), UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
            owner.setPersistedStatus(ChunkStatus.STRUCTURE_STARTS);
            var persisted = candidate.copy(level);
            persisted.addReference();
            owner.setStartForStructure(candidate.structure(), persisted);
            saved.put(candidate.owner(), SerializableCubeData.copyOf(level, owner).write());
            assertEquals(1, layout.startForOwner(candidate.owner(), holder.value()).copy(level).getReferences());

            when(level.structureManager().shouldGenerateStructures()).thenReturn(false);
            var disabled = new DeepStructureLayout(level, fixture.biomes,
                    key -> CompletableFuture.completedFuture(Optional.ofNullable(saved.get(key))));
            assertEquals(1, disabled.startForOwner(candidate.owner(), holder.value()).copy(level).getReferences(),
                    "Saved metadata survives disabled generation and an empty current kind list");
            owner.setAllStarts(Map.of());
            disabled.rememberLoaded(owner);
            assertNull(disabled.startForOwner(candidate.owner(), holder.value()), "Loaded empty owner metadata overrides disk and seed");
            verify(level, never()).getChunk(anyInt(), anyInt(), any(ChunkStatus.class));
            verify((CubicLevelReader) level, never()).cc_getCube(anyInt(), anyInt(), anyInt(), any(ChunkStatus.class), anyBoolean());
            verify((CubeSource) level.getChunkSource(), never()).cc_getCube(anyInt(), anyInt(), anyInt(), any(ChunkStatus.class), anyBoolean());
            verify((CubeSource) level.getChunkSource(), never()).cc_getCube(anyInt(), anyInt(), anyInt(), anyBoolean());
        }
    }

    @Test
    void failedOrCorruptOwnerReadsFailGenerationInsteadOfReplacingSavedStructures() throws Exception {
        try (var fixture = new Fixture(Biomes.PLAINS)) {
            var level = fixture.level;
            var requested = HolderSet.direct(level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.MINESHAFT));
            var layout = new DeepStructureLayout(level, fixture.biomes);
            var candidate = layout.findNearestStart(requested, new BlockPos(0, -32000, 0), 64);
            assertNotNull(candidate);
            var failure = new DeepStructureLayout(level, fixture.biomes,
                    owner -> CompletableFuture.failedFuture(new java.io.IOException("Owner disk unavailable")));
            assertNull(failure.startForOwner(candidate.owner(), candidate.structure()), "Gameplay queries defer a failed owner read");
            assertThrows(CompletionException.class, () -> failure.intersecting(candidate.bounds()));
            var corrupt = new DeepStructureLayout(level, fixture.biomes,
                    owner -> CompletableFuture.completedFuture(Optional.of(new CompoundTag())));
            assertNull(corrupt.startForOwner(candidate.owner(), candidate.structure()), "Gameplay queries defer malformed metadata");
            assertThrows(RuntimeException.class, () -> corrupt.intersecting(candidate.bounds()));
        }
    }

    @Test
    void nearbyCaveStructureDiscoveryIncludesStrongholdsAndTrialsAcrossBandsAndHonorsEmptyOwners() throws Exception {
        try (var fixture = new Fixture(Biomes.PLAINS)) {
            var level = fixture.level;
            var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            var stronghold = registry.getOrThrow(BuiltinStructures.STRONGHOLD);
            var trial = registry.getOrThrow(BuiltinStructures.TRIAL_CHAMBERS);
            var layout = new DeepStructureLayout(level, fixture.biomes);
            for (var type : List.of(stronghold, trial)) {
                var start = layout.findNearestStart(HolderSet.direct(type), new BlockPos(0, -32000, 0), 256);
                assertNotNull(start);
                int bandFloor = layout.bandMinY(layout.bandAt(start.bounds().minY()));
                var center = start.bounds().getCenter();
                var area = new BoundingBox(center.getX(), bandFloor - 1, center.getZ(), center.getX(), bandFloor - 1, center.getZ());
                int reach = start.bounds().minY() - area.maxY() + 1;
                var found = layout.caveStructuresNear(area, reach);
                assertTrue(found.stream().allMatch(value -> value.structure() == stronghold.value() || value.structure() == trial.value()));
                assertTrue(found.stream().anyMatch(value -> value.owner().equals(start.owner()) && value.structure() == type.value()),
                        "The influence envelope must discover strongholds and trial chambers in an adjacent vertical band");

                var owner = new ProtoCube(start.owner(), UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
                owner.setPersistedStatus(ChunkStatus.STRUCTURE_STARTS);
                layout.rememberLoaded(owner);
                assertTrue(layout.caveStructuresNear(area, reach).stream().noneMatch(value -> value.owner().equals(start.owner())),
                        "Saved empty owner metadata must suppress cave influence as well as the structure itself");
            }
        }
    }

    @Test
    void caveInfluenceExcludesMineshaftsAndAncientCitiesBeforeGeneratingTheirGraphs() throws Exception {
        for (var excluded : List.of(BuiltinStructures.MINESHAFT, BuiltinStructures.ANCIENT_CITY)) {
            try (var fixture = new Fixture(excluded == BuiltinStructures.ANCIENT_CITY ? Biomes.DEEP_DARK : Biomes.PLAINS)) {
                var state = fixture.level.getChunkSource().getGeneratorState();
                var excludedSets = state.possibleStructureSets().stream()
                        .filter(set -> set.value().structures().stream().anyMatch(entry -> entry.structure().is(excluded))).toList();
                assertFalse(excludedSets.isEmpty());
                when(state.possibleStructureSets()).thenReturn(excludedSets);
                var layout = new DeepStructureLayout(fixture.level, (x, y, z) -> {
                    throw new AssertionError("Unrelated structure graph requested biome sampling");
                });
                assertTrue(layout.caveStructuresNear(new BoundingBox(-256, -32512, -256, 256, -32000, 256), 136).isEmpty());
            }
        }
    }

    private static void assertCoveringWithinNestedScope(DeepStructureLayout layout, DeepStructureLayout.Start candidate) {
        try (var nested = layout.ownerReadScope()) {
            assertTrue(layout.covering(candidate.bounds()).stream().anyMatch(start -> start.owner().equals(candidate.owner())));
        }
    }

    private final class Fixture implements AutoCloseable {
        private final MultiPackResourceManager resources;
        private final ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
        private final BiomeResolver biomes;

        private Fixture(ResourceKey<Biome> biome) {
            var registries = VanillaRegistriesExtension.shared();
            var packs = ServerPacksSource.createVanillaTrustedRepository();
            packs.reload();
            packs.setSelected(List.of("vanilla"));
            resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs.openAllSelected());
            var storage = mock(LevelStorageSource.LevelStorageAccess.class);
            when(storage.getLevelPath(LevelResource.GENERATED_DIR)).thenReturn(directory);
            var templates = new StructureTemplateManager(resources, storage, DataFixers.getDataFixer(), BuiltInRegistries.BLOCK);
            var source = new FixedBiomeSource(registries.lookupOrThrow(Registries.BIOME).getOrThrow(biome));
            biomes = source;
            var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
            var random = RandomState.create(registries.lookupOrThrow(Registries.NOISE), 42, settings.value());
            var generator = new NoiseBasedChunkGenerator(source, settings);
            var state = mock(ChunkGeneratorStructureState.class);
            var nativeState = ChunkGeneratorStructureState.createForNormal(random, 42, new ChunkPos(0, 0), source,
                    registries.lookupOrThrow(Registries.STRUCTURE_SET));
            when(state.possibleStructureSets()).thenReturn(nativeState.possibleStructureSets());
            Misc.setupLevelHeight(level, -64, 384);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.getSeed()).thenReturn(42L);
            when(level.structureManager().shouldGenerateStructures()).thenReturn(true);
            when(level.getChunkSource().getGenerator()).thenReturn(generator);
            when(level.getChunkSource().getGeneratorState()).thenReturn(state);
            when(level.getChunkSource().randomState()).thenReturn(random);
            when(level.getStructureTemplateManager()).thenReturn(templates);
            when(level.getServer().getStructureTemplateManager()).thenReturn(templates);
            when(level.getServer().registryAccess()).thenReturn(registries);
        }

        @Override public void close() {
            resources.close();
        }
    }
}
