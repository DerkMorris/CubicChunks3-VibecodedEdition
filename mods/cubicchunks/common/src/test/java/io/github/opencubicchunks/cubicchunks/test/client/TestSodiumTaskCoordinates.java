package io.github.opencubicchunks.cubicchunks.test.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.TaskCollectingTree;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.CullType;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Opt-in actual Sodium priority-queue encoding/decoding, including snapshots retained across camera movement. */
class TestSodiumTaskCoordinates extends BaseTest {
    @Test
    void cubicMeshTasksRetainCoordinatesAtPositiveAndNegativeHighAltitudes() {
        for (int blockY : new int[] {-320000, -32000, -2048, -1, 96, 2048, 32000, 320000}) {
            for (CullType cullType : CullType.values()) {
                assertRoundTrip(blockY, true, cullType);
            }
        }
    }

    @Test
    void ordinaryWorldsRetainSodiumTaskCoordinates() {
        for (int blockY : new int[] {-64, 0, 96, 319}) {
            for (CullType cullType : CullType.values()) {
                assertRoundTrip(blockY, false, cullType);
            }
        }
    }

    private static void assertRoundTrip(int blockY, boolean cubic, CullType cullType) {
        ClientLevel level = mock(ClientLevel.class);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(cubic);
        Misc.setupLevelHeight(level, -64, 384);
        Viewport viewport = new Viewport(mock(Frustum.class), new Vector3d(-55.5, blockY + 0.25, 279.5));
        TaskCollectingTree tree = new TaskCollectingTree(viewport, 128, 1, cullType, level);
        SectionPos center = viewport.getChunkCoord();
        Set<Long> expected = new HashSet<>();
        for (int offset = -3; offset <= 3; offset++) {
            SectionPos pos = center.offset(offset, offset, -offset);
            var section = new RenderSection(mock(RenderRegion.class), pos.x(), pos.y(), pos.z());
            section.setPendingUpdate(ChunkUpdateTypes.INITIAL_BUILD, System.nanoTime());
            tree.visit(section, true);
            expected.add(pos.asLong());
        }
        // The heap is a separate result that may be consumed after another camera's cull has already started.
        var heap = tree.getPendingTaskLists();
        var unrelatedViewport = new Viewport(mock(Frustum.class), new Vector3d(100, blockY + 16000, -100));
        new TaskCollectingTree(unrelatedViewport, 128, 2, cullType, level).getPendingTaskLists();
        assertEquals(expected.size(), heap.size());
        while (!heap.isEmpty()) {
            long actual = heap.dequeueNextSectionPos();
            assertTrue(expected.remove(actual), () -> "Unexpected or duplicated mesh coordinate " + SectionPos.of(actual)
                    + " at camera Y=" + blockY + " cubic=" + cubic + " cull=" + cullType);
        }
        assertTrue(expected.isEmpty());
    }
}
