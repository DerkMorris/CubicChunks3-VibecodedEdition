package io.github.opencubicchunks.cubicchunks.world.level.levelgen.structure;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Bounded corner search on the owning level thread. Missing coverage must not produce a partial bounding box. */
public final class CubeStructureCorners {
    public static final int SEARCH_RADIUS = 80;

    private CubeStructureCorners() {}

    public static Optional<BoundingBox> find(Level level, BlockPos origin, @Nullable Identifier name) {
        if (!CubicBlockBounds.contains(origin)) {
            return Optional.empty();
        }
        BoundingBox area = new BoundingBox(lower(origin.getX()), lower(origin.getY()), lower(origin.getZ()), upper(origin.getX()),
                upper(origin.getY()), upper(origin.getZ()));
        // Resolve the entire finite area before promoting any pending entity NBT or scanning palettes.
        List<LevelCube> cubes = readyCubes(level, area);
        Corners corners = new Corners(name == null ? "" : name.toString());
        for (LevelCube cube : cubes) {
            cube.findBlocks(state -> state.is(Blocks.STRUCTURE_BLOCK), (state, pos) -> state.is(Blocks.STRUCTURE_BLOCK) && area.isInside(pos),
                    (pos, state) -> corners.accept(cube, pos));
        }
        return corners.bounds(origin);
    }

    private static List<LevelCube> readyCubes(Level level, BoundingBox area) {
        CubeSource source = (CubeSource) level.getChunkSource();
        List<LevelCube> cubes = new ArrayList<>();
        for (int y = Coords.blockToCube(area.minY()); y <= Coords.blockToCube(area.maxY()); y++) {
            for (int z = Coords.blockToCube(area.minZ()); z <= Coords.blockToCube(area.maxZ()); z++) {
                for (int x = Coords.blockToCube(area.minX()); x <= Coords.blockToCube(area.maxX()); x++) {
                    LevelCube cube = source.cc_getCubeNow(x, y, z);
                    if (!matches(cube, level, x, y, z)) {
                        return List.of();
                    }
                    cubes.add(cube);
                }
            }
        }
        return cubes;
    }

    private static boolean matches(@Nullable LevelCube cube, Level level, int x, int y, int z) {
        return cube != null && cube.getLevel() == level && cube.cc_getCubePos().getX() == x && cube.cc_getCubePos().getY() == y
                && cube.cc_getCubePos().getZ() == z;
    }

    private static int lower(int coordinate) {
        return Math.max(CubicBlockBounds.MIN, coordinate - SEARCH_RADIUS);
    }

    private static int upper(int coordinate) {
        return Math.min(CubicBlockBounds.MAX, coordinate + SEARCH_RADIUS);
    }

    private static final class Corners {
        private final String name;
        private int count;
        private @Nullable BoundingBox box;

        private Corners(String name) {
            this.name = name;
        }

        private void accept(LevelCube cube, BlockPos pos) {
            if (!(cube.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE) instanceof StructureBlockEntity entity)
                    || entity.getMode() != StructureMode.CORNER || !name.equals(entity.getStructureName())) {
                return;
            }
            count++;
            if (box == null) {
                box = new BoundingBox(pos);
            } else {
                box.encapsulate(pos);
            }
        }

        private Optional<BoundingBox> bounds(BlockPos origin) {
            if (box == null) {
                return Optional.empty();
            }
            if (count == 1) {
                box.encapsulate(origin);
            }
            return Optional.of(box);
        }
    }
}
