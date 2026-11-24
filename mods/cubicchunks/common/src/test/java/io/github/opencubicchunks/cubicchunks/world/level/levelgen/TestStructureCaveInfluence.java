package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import org.junit.jupiter.api.Test;

class TestStructureCaveInfluence extends BaseTest {
    @Test
    void gatheredFieldsRetainTheirSnapshotAndChangedSavedGeometryCannotReuseTheOldOwnerCache() {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        var registries = level.registryAccess().freeze();
        when(level.getServer().registryAccess()).thenReturn(registries);
        var layout = mock(DeepStructureLayout.class);
        var returned = new AtomicReference<List<DeepStructureLayout.Start>>(List.of());
        when(layout.caveStructuresNear(any(), anyInt())).thenAnswer(ignored -> returned.get());
        var influence = new StructureCaveInfluence(level, layout);
        var originalBox = new BoundingBox(0, -32000, 0, 10, -31993, 15);
        returned.set(List.of(snapshot(level, originalBox)));
        var original = influence.forArea(-8, -31999, 1, 0);
        assertEquals(1, original.weight(-8, -31999, 1));

        var movedBox = originalBox.moved(16, 0, 0);
        returned.set(List.of(snapshot(level, movedBox)));
        var moved = influence.forArea(-8, -31999, 1, 0);
        assertEquals(StructureCaveInfluence.Field.of(List.of(movedBox)).weight(-8, -31999, 1), moved.weight(-8, -31999, 1));
        assertTrue(moved.weight(-8, -31999, 1) < 1);
        assertEquals(1, original.weight(-8, -31999, 1), "Previously gathered fields must remain immutable");
        returned.set(List.of());
        assertSame(StructureCaveInfluence.Field.EMPTY, influence.forArea(-8, -31999, 1, 0),
                "An authoritative empty lookup must not reuse a cached owner's influence");
    }

    private static DeepStructureLayout.Start snapshot(ServerLevel level, BoundingBox box) {
        var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(BuiltinStructures.STRONGHOLD);
        var room = new StrongholdPieces.PortalRoom(0, box, Direction.SOUTH);
        var start = new StructureStart(structure, new ChunkPos(0, 0), 0, new PiecesContainer(List.of(room)));
        return new DeepStructureLayout.Start(CubePos.of(0, -1000, 0), structure,
                start.createTag(StructurePieceSerializationContext.fromLevel(level), start.getChunkPos()), start.getBoundingBox(), 42);
    }

    @Test
    void coreHasFullInfluenceAndDistantParametersRemainExact() {
        var field = StructureCaveInfluence.Field.of(List.of(new BoundingBox(0, 0, 0, 16, 16, 16)));
        for (int x : new int[] { -8, 0, 16, 24 }) {
            for (int y : new int[] { -8, 0, 16, 24 }) {
                for (int z : new int[] { -8, 0, 16, 24 }) {
                    assertEquals(1, field.weight(x, y, z));
                    assertEquals(32, StructureCaveInfluence.scale(160, field.weight(x, y, z)));
                    assertEquals(35, StructureCaveInfluence.openness(100, field.weight(x, y, z)));
                }
            }
        }
        assertEquals(0, field.weight(152, 8, 8));
        assertEquals(96, StructureCaveInfluence.scale(96, field.weight(152, 8, 8)));
        assertEquals(80, StructureCaveInfluence.openness(80, field.weight(-137, 8, 8)));
        assertSame(StructureCaveInfluence.Field.EMPTY, StructureCaveInfluence.Field.of(List.of()));
        assertEquals(96, StructureCaveInfluence.scale(96, StructureCaveInfluence.Field.EMPTY.weight(0, 0, 0)));
        assertEquals(80, StructureCaveInfluence.openness(80, StructureCaveInfluence.Field.EMPTY.weight(0, 0, 0)));
    }

    @Test
    void fadeIsSmoothRadialAndOnlyReducesParameters() {
        var field = StructureCaveInfluence.Field.of(List.of(new BoundingBox(0, 0, 0, 16, 16, 16)));
        assertEquals(0.5, field.weight(88, 8, 8));
        assertEquals(field.weight(74, 8, 24), field.weight(54, 8, 64), 1.0e-12, "Equal Euclidean distances must have equal influence");
        assertTrue(1 - field.weight(25, 8, 8) < 0.001, "The fade must leave the full-influence core with a flat slope");
        assertTrue(field.weight(151, 8, 8) < 0.001, "The fade must meet unchanged terrain with a flat slope");
        double previous = 1;
        for (int distance = 0; distance <= 128; distance++) {
            double weight = field.weight(24 + distance, 8, 8);
            assertTrue(weight <= previous);
            previous = weight;
            for (double original : new double[] { 0, 16, 32, 35, 64, 100, 160 }) {
                assertTrue(StructureCaveInfluence.scale(original, weight) <= original);
                assertTrue(StructureCaveInfluence.openness(original, weight) <= original);
                assertTrue(StructureCaveInfluence.scale(original, weight) >= Math.min(original, StructureCaveInfluence.TARGET_SCALE));
                assertTrue(StructureCaveInfluence.openness(original, weight) >= Math.min(original, StructureCaveInfluence.TARGET_OPENNESS));
            }
        }
    }

    @Test
    void parameterReductionUsesPercentOpennessAndNeverRaisesAlreadySmallerSettings() {
        assertEquals(64, StructureCaveInfluence.scale(96, 0.5));
        assertEquals(60, StructureCaveInfluence.openness(85, 0.5));
        for (double weight : new double[] { -1, 0, 0.25, 0.5, 1, 2 }) {
            assertEquals(16, StructureCaveInfluence.scale(16, weight));
            assertEquals(20, StructureCaveInfluence.openness(20, weight));
        }
        assertEquals(96, StructureCaveInfluence.scale(96, -1));
        assertEquals(32, StructureCaveInfluence.scale(96, 2));
        assertEquals(35, StructureCaveInfluence.openness(100, 2));
    }

    @Test
    void unionPreservesLargeGapsAndIgnoresPieceOrderDuplicatesAndLaterBoxMutation() {
        var first = new BoundingBox(-400, -10, -10, -380, 10, 10);
        var second = new BoundingBox(380, -10, -10, 400, 10, 10);
        var combined = StructureCaveInfluence.Field.of(List.of(first, second));
        var reversed = StructureCaveInfluence.Field.of(List.of(second, first, second));
        assertEquals(0, combined.weight(0, 0, 0), "The whole structure bounding box must not influence gaps between remote branches");
        for (int x = -550; x <= 550; x++) {
            assertEquals(combined.weight(x, 0, 0), reversed.weight(x, 0, 0));
        }
        first.move(1000, 1000, 1000);
        assertEquals(1, combined.weight(-390, 0, 0), "A gathered field must own immutable geometry");
    }

    @Test
    void absoluteCoordinatesAndSharedSectionEndpointsKeepTheSameInfluenceAtExtremeY() {
        var local = StructureCaveInfluence.Field.of(List.of(new BoundingBox(-16, -16, -16, 16, 16, 16)));
        for (int offset : new int[] { -32000000, -32000, 32000000 }) {
            var moved = StructureCaveInfluence.Field.of(List.of(new BoundingBox(offset - 16, offset - 16, offset - 16,
                    offset + 16, offset + 16, offset + 16)));
            for (int coordinate = -160; coordinate <= 160; coordinate++) {
                assertEquals(local.weight(coordinate, coordinate / 2, -coordinate),
                        moved.weight(offset + coordinate, offset + coordinate / 2, offset - coordinate));
            }
            assertEquals(0, moved.weight(offset, offset + (1 << 24), offset), "Packed Y aliases must not inherit influence");
        }
        var left = StructureCaveInfluence.Field.of(List.of(new BoundingBox(-16, -16, -16, 16, 16, 16)));
        var right = StructureCaveInfluence.Field.of(List.of(new BoundingBox(-16, -16, -16, 16, 16, 16)));
        for (int y = -32; y <= 32; y += 4) {
            assertEquals(StructureCaveInfluence.scale(96, left.weight(16, y, 0)), StructureCaveInfluence.scale(96, right.weight(16, y, 0)));
            assertEquals(StructureCaveInfluence.openness(80, left.weight(16, y, 0)), StructureCaveInfluence.openness(80, right.weight(16, y, 0)));
        }
    }
}
