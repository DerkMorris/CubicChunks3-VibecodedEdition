package io.github.opencubicchunks.cubicchunks.server.level.progress;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.utils.Coords;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** A frame-owned copy; the renderer must not read mutable server state after extraction. */
public final class CubeLoadingSnapshot implements StoringCloProgressListener {
    private final int columnDiameter;
    private final int cubeDiameter;
    private final ChunkStatus[] columns;
    private final ChunkStatus[] cubes;

    private CubeLoadingSnapshot(int columnDiameter, int cubeDiameter, ChunkStatus[] columns, ChunkStatus[] cubes) {
        this.columnDiameter = columnDiameter;
        this.cubeDiameter = cubeDiameter;
        this.columns = columns;
        this.cubes = cubes;
    }

    public static CubeLoadingSnapshot capture(ChunkLoadStatusView view) {
        int columnDiameter = 2 * view.radius() + 1;
        int cubeDiameter = 2 * Coords.sectionToCubeCeil(view.radius()) + 1;
        int paddedDiameter = cubeDiameter + 2;
        ChunkStatus[] columns = new ChunkStatus[columnDiameter * columnDiameter];
        ChunkStatus[] cubes = new ChunkStatus[paddedDiameter * paddedDiameter * paddedDiameter];
        StoringCloProgressListener cubicView = (StoringCloProgressListener) view;
        for (int x = 0; x < columnDiameter; x++) {
            for (int z = 0; z < columnDiameter; z++) {
                columns[x * columnDiameter + z] = view.get(x, z);
            }
        }
        // Keep one neighbour outside each face for occlusion decisions at the visible boundary.
        for (int x = -1; x <= cubeDiameter; x++) {
            for (int y = -1; y <= cubeDiameter; y++) {
                for (int z = -1; z <= cubeDiameter; z++) {
                    cubes[((x + 1) * paddedDiameter + y + 1) * paddedDiameter + z + 1] = cubicView.cc_getStatus(x, y, z);
                }
            }
        }
        return new CubeLoadingSnapshot(columnDiameter, cubeDiameter, columns, cubes);
    }

    public int columnDiameter() {
        return columnDiameter;
    }

    public int cubeDiameter() {
        return cubeDiameter;
    }

    public @Nullable ChunkStatus get(int x, int z) {
        return columns[x * columnDiameter + z];
    }

    @Override public @Nullable ChunkStatus cc_getStatus(int x, int y, int z) {
        int paddedDiameter = cubeDiameter + 2;
        return cubes[((x + 1) * paddedDiameter + y + 1) * paddedDiameter + z + 1];
    }
}
