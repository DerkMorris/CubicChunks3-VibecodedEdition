package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/** Smooth local cave scale and openness reductions around strongholds and trial chambers. */
final class StructureCaveInfluence {
    static final int CORE = 8;
    static final int FADE = 128;
    static final double TARGET_SCALE = 32;
    static final double TARGET_OPENNESS = 35;
    private static final int CACHED_STARTS = 128;
    private final ServerLevel level;
    private final DeepStructureLayout layout;
    private final LoadingCache<Snapshot, List<Box>> pieces = CacheBuilder.newBuilder().maximumSize(CACHED_STARTS)
            .build(CacheLoader.from(this::extract));

    StructureCaveInfluence(ServerLevel level, DeepStructureLayout layout) {
        this.level = level;
        this.layout = layout;
    }

    /** Includes the far lattice endpoint, so a size-16 section also gathers influence for its x/y/z + 16 samples. */
    Field forArea(int x0, int y0, int z0, int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Influence area size must be nonnegative");
        }
        var area = new BoundingBox(clamp(x0), clamp(y0), clamp(z0), clamp((long) x0 + size), clamp((long) y0 + size),
                clamp((long) z0 + size));
        var gathered = new ArrayList<Box>();
        try (var ignored = layout.ownerReadScope()) {
            for (var start : layout.caveStructuresNear(area, CORE + FADE)) {
                // Start.tag() is a defensive copy. Retaining it as part of the key prevents stale saved geometry.
                for (var box : pieces.getUnchecked(new Snapshot(start.owner(), start.tag()))) {
                    if (box.near(area)) {
                        gathered.add(box);
                    }
                }
            }
        }
        return gathered.isEmpty() ? Field.EMPTY : new Field(gathered.toArray(Box[]::new));
    }

    static double scale(double original, double weight) {
        return reduce(original, TARGET_SCALE, weight);
    }

    /** Both the original value and target use the world's percentage scale, before density normalization. */
    static double openness(double original, double weight) {
        return reduce(original, TARGET_OPENNESS, weight);
    }

    static double reduce(double original, double target, double weight) {
        if (original <= target || weight <= 0) {
            return original;
        }
        return weight >= 1 ? target : original + weight * (target - original);
    }

    private List<Box> extract(Snapshot snapshot) {
        var start = Objects.requireNonNull(StructureStart.loadStaticStart(StructurePieceSerializationContext.fromLevel(level),
                snapshot.tag.copy(), level.getSeed()));
        return start.getPieces().stream().map(piece -> Box.around(piece.getBoundingBox())).toList();
    }

    private static int clamp(long coordinate) {
        return (int) Math.clamp(coordinate, CubicBlockBounds.MIN, CubicBlockBounds.MAX);
    }

    private record Snapshot(CubePos owner, CompoundTag tag) {}

    private record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        private static Box around(BoundingBox piece) {
            return new Box((double) piece.minX() - CORE, (double) piece.minY() - CORE, (double) piece.minZ() - CORE,
                    (double) piece.maxX() + CORE, (double) piece.maxY() + CORE, (double) piece.maxZ() + CORE);
        }

        private boolean near(BoundingBox area) {
            return minX - FADE <= area.maxX() && maxX + FADE >= area.minX()
                    && minY - FADE <= area.maxY() && maxY + FADE >= area.minY()
                    && minZ - FADE <= area.maxZ() && maxZ + FADE >= area.minZ();
        }

        private double distanceSquared(int x, int y, int z) {
            double dx = Math.max(0, Math.max(minX - x, x - maxX));
            double dy = Math.max(0, Math.max(minY - y, y - maxY));
            double dz = Math.max(0, Math.max(minZ - z, z - maxZ));
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** Immutable union of piece influence boxes; overlapping structures never accumulate extra influence. */
    static final class Field {
        static final Field EMPTY = new Field(new Box[0]);
        private final Box[] cores;

        private Field(Box[] cores) {
            this.cores = cores;
        }

        static Field of(List<BoundingBox> pieces) {
            return pieces.isEmpty() ? EMPTY : new Field(pieces.stream().map(Box::around).toArray(Box[]::new));
        }

        double weight(int x, int y, int z) {
            double nearest = (double) FADE * FADE;
            for (var core : cores) {
                nearest = Math.min(nearest, core.distanceSquared(x, y, z));
                if (nearest == 0) {
                    return 1;
                }
            }
            if (nearest >= (double) FADE * FADE) {
                return 0;
            }
            double t = 1 - Math.sqrt(nearest) / FADE;
            return t * t * (1 + 2 * (1 - t));
        }
    }
}
