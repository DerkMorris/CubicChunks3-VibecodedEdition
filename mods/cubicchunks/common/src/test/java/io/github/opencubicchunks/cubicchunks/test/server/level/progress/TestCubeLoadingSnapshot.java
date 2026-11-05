package io.github.opencubicchunks.cubicchunks.test.server.level.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.server.level.progress.CubeLoadingSnapshot;
import io.github.opencubicchunks.cubicchunks.server.level.progress.StoringCloProgressListener;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TestCubeLoadingSnapshot {
    @BeforeAll
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void capturesBothDiametersAndNeighbourBorder() {
        MutableView view = new MutableView(3);
        CubeLoadingSnapshot snapshot = CubeLoadingSnapshot.capture(view);
        int expectedCubeDiameter = 2 * Coords.sectionToCubeCeil(view.radius()) + 1;

        assertEquals(7, snapshot.columnDiameter());
        assertEquals(expectedCubeDiameter, snapshot.cubeDiameter());
        assertSame(ChunkStatus.FULL, snapshot.get(0, 0));
        assertSame(ChunkStatus.FULL, snapshot.get(6, 6));
        assertSame(ChunkStatus.EMPTY, snapshot.cc_getStatus(-1, -1, -1));
        assertSame(ChunkStatus.EMPTY, snapshot.cc_getStatus(expectedCubeDiameter, expectedCubeDiameter, expectedCubeDiameter));
        assertSame(ChunkStatus.BIOMES, snapshot.cc_getStatus(0, 1, 2));
        assertSame(ChunkStatus.STRUCTURE_STARTS, snapshot.cc_getStatus(2, 1, 0));
    }

    @Test
    public void extractionFreezesStatusUntilNextFrame() {
        MutableView view = new MutableView(1);
        CubeLoadingSnapshot before = CubeLoadingSnapshot.capture(view);
        view.columnStatus = ChunkStatus.LIGHT;
        view.cubeStatus = ChunkStatus.FEATURES;
        CubeLoadingSnapshot after = CubeLoadingSnapshot.capture(view);

        assertSame(ChunkStatus.FULL, before.get(1, 1));
        assertSame(ChunkStatus.TERRAIN, before.cc_getStatus(1, 1, 1));
        assertSame(ChunkStatus.LIGHT, after.get(1, 1));
        assertSame(ChunkStatus.FEATURES, after.cc_getStatus(1, 1, 1));
    }

    @Test
    public void zeroRadiusStillCapturesOneCubeAndItsNeighbours() {
        CubeLoadingSnapshot snapshot = CubeLoadingSnapshot.capture(new MutableView(0));
        assertEquals(1, snapshot.columnDiameter());
        assertEquals(1, snapshot.cubeDiameter());
        assertSame(ChunkStatus.TERRAIN, snapshot.cc_getStatus(0, 0, 0));
        assertSame(ChunkStatus.EMPTY, snapshot.cc_getStatus(1, 0, 0));
    }

    private static final class MutableView implements ChunkLoadStatusView, StoringCloProgressListener {
        private final int radius;
        private ChunkStatus columnStatus = ChunkStatus.FULL;
        private ChunkStatus cubeStatus = ChunkStatus.TERRAIN;

        private MutableView(int radius) {
            this.radius = radius;
        }

        @Override public void moveTo(ResourceKey<Level> dimension, ChunkPos center) {
            // Test views already express positions relative to their center.
        }

        @Override public ChunkStatus get(int x, int z) {
            return columnStatus;
        }

        @Override public int radius() {
            return radius;
        }

        @Override public ChunkStatus cc_getStatus(int x, int y, int z) {
            int diameter = 2 * Coords.sectionToCubeCeil(radius) + 1;
            if (x < 0 || y < 0 || z < 0 || x >= diameter || y >= diameter || z >= diameter) {
                return ChunkStatus.EMPTY;
            }
            if (x == 0 && y == 1 && z == 2) {
                return ChunkStatus.BIOMES;
            }
            if (x == 2 && y == 1 && z == 0) {
                return ChunkStatus.STRUCTURE_STARTS;
            }
            return cubeStatus;
        }
    }
}
