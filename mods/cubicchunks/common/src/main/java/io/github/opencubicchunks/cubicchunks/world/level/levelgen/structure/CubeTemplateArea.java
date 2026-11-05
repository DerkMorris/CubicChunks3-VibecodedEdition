package io.github.opencubicchunks.cubicchunks.world.level.levelgen.structure;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;

/** Finite, already resident storage for structure block operations on the owning level thread. */
public final class CubeTemplateArea {
    private static final int MAX_SIZE = CubicBlockBounds.MAX - CubicBlockBounds.MIN + 1;
    private final Map<CubePos, LevelCube> cubes;

    private CubeTemplateArea(Map<CubePos, LevelCube> cubes) {
        this.cubes = cubes;
    }

    public static Optional<CubeTemplateArea> capture(Level level, BlockPos origin, Vec3i size) {
        if (!validSize(size) || !CubicBlockBounds.contains(origin)) {
            return Optional.empty();
        }
        return ready(level, new BoundingBox(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + size.getX() - 1,
                origin.getY() + size.getY() - 1, origin.getZ() + size.getZ() - 1));
    }

    public static boolean canPlace(Level level, StructureTemplate template, BlockPos origin, StructurePlaceSettings settings) {
        if (!validSize(template.getSize()) || !CubicBlockBounds.contains(origin) || !CubicBlockBounds.contains(settings.getRotationPivot())) {
            return false;
        }
        BoundingBox area = template.getBoundingBox(settings, origin);
        // Vanilla waterlogging and shape repair read immediate neighbors, even when the template spans just one cube.
        int margin = settings.shouldApplyWaterlogging() || !settings.getKnownShape() ? 1 : 0;
        return ready(level, new BoundingBox(area.minX() - margin, area.minY() - margin, area.minZ() - margin, area.maxX() + margin,
                area.maxY() + margin, area.maxZ() + margin)).isPresent();
    }

    private static boolean validSize(Vec3i size) {
        return size.getX() > 0 && size.getY() > 0 && size.getZ() > 0 && size.getX() <= MAX_SIZE && size.getY() <= MAX_SIZE && size.getZ() <= MAX_SIZE;
    }

    public static boolean isEntityAreaReady(Level level, AABB box) {
        return ready(level, BoundingBox.fromCorners(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(Math.nextDown(box.maxX), Math.nextDown(box.maxY), Math.nextDown(box.maxZ)))).isPresent();
    }

    private static Optional<CubeTemplateArea> ready(Level level, BoundingBox area) {
        if (!CubicBlockBounds.contains(new BlockPos(area.minX(), area.minY(), area.minZ()))
                || !CubicBlockBounds.contains(new BlockPos(area.maxX(), area.maxY(), area.maxZ()))) {
            return Optional.empty();
        }
        CubeSource source = (CubeSource) level.getChunkSource();
        Map<CubePos, LevelCube> cubes = new HashMap<>();
        for (int y = Coords.blockToCube(area.minY()); y <= Coords.blockToCube(area.maxY()); y++) {
            for (int z = Coords.blockToCube(area.minZ()); z <= Coords.blockToCube(area.maxZ()); z++) {
                for (int x = Coords.blockToCube(area.minX()); x <= Coords.blockToCube(area.maxX()); x++) {
                    CubePos pos = CubePos.of(x, y, z);
                    LevelCube cube = source.cc_getCubeNow(x, y, z);
                    if (cube == null || cube.getLevel() != level || !pos.equals(cube.cc_getCubePos())) {
                        return Optional.empty();
                    }
                    cubes.put(pos, cube);
                }
            }
        }
        return Optional.of(new CubeTemplateArea(cubes));
    }

    public BlockState getBlockState(BlockPos pos) {
        return cubes.get(CubePos.from(pos)).getBlockState(pos);
    }
}
