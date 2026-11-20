package io.github.opencubicchunks.cubicchunks.world.level;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.blockscan.BlockStateConsumer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** The vanilla block matcher's section order and filtering, with three-dimensional cube storage. */
public final class CubicBlockScan {
    private CubicBlockScan() {}

    public static boolean box(CubicLevelReader level, BlockPos from, BlockPos to, Predicate<BlockState> filter, BlockStateConsumer consumer) {
        BlockPos min = new BlockPos(Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()), Math.min(from.getZ(), to.getZ()));
        BlockPos max = new BlockPos(Math.max(from.getX(), to.getX()), Math.max(from.getY(), to.getY()), Math.max(from.getZ(), to.getZ()));
        Cache cache = new Cache(level, filter);
        for (int sx = SectionPos.blockToSectionCoord(min.getX()); sx <= SectionPos.blockToSectionCoord(max.getX()); sx++) {
            for (int sz = SectionPos.blockToSectionCoord(min.getZ()); sz <= SectionPos.blockToSectionCoord(max.getZ()); sz++) {
                for (int sy = SectionPos.blockToSectionCoord(min.getY()); sy <= SectionPos.blockToSectionCoord(max.getY()); sy++) {
                    BlockPos origin = new BlockPos(SectionPos.sectionToBlockCoord(sx), SectionPos.sectionToBlockCoord(sy),
                            SectionPos.sectionToBlockCoord(sz));
                    if (section(cache, min, max, origin, filter, consumer)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean section(
            Cache cache, BlockPos min, BlockPos max, BlockPos origin, Predicate<BlockState> filter, BlockStateConsumer consumer
    ) {
        LevelChunkSection section = cache.section(origin.getX(), origin.getY(), origin.getZ());
        if (section == null ? !cache.matchAir : !section.maybeHas(filter)) {
            return false;
        }
        int x0 = Math.max(min.getX(), origin.getX()) - origin.getX();
        int y0 = Math.max(min.getY(), origin.getY()) - origin.getY();
        int z0 = Math.max(min.getZ(), origin.getZ()) - origin.getZ();
        int x1 = Math.min(max.getX(), origin.getX() + CubicConstants.SECTION_DIAMETER - 1) - origin.getX();
        int y1 = Math.min(max.getY(), origin.getY() + CubicConstants.SECTION_DIAMETER - 1) - origin.getY();
        int z1 = Math.min(max.getZ(), origin.getZ() + CubicConstants.SECTION_DIAMETER - 1) - origin.getZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    BlockState state = section == null ? Blocks.AIR.defaultBlockState() : section.getBlockState(x, y, z);
                    if (filter.test(state)
                            && consumer.apply(cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z), state).shouldAbort()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean ordered(CubicLevelReader level, Iterable<BlockPos> positions, Predicate<BlockState> filter, BlockStateConsumer consumer) {
        Cache cache = new Cache(level, filter);
        for (BlockPos pos : positions) {
            LevelChunkSection section = cache.section(pos.getX(), pos.getY(), pos.getZ());
            BlockState state = section == null ? Blocks.AIR.defaultBlockState()
                    : section.getBlockState(SectionPos.sectionRelative(pos.getX()), SectionPos.sectionRelative(pos.getY()),
                            SectionPos.sectionRelative(pos.getZ()));
            if (filter.test(state) && consumer.apply(pos, state).shouldAbort()) {
                return true;
            }
        }
        return false;
    }

    private static final class Cache {
        private final CubicLevelReader level;
        private final Map<CubePos, CubeAccess> cubes = new HashMap<>();
        private final boolean matchAir;

        private Cache(CubicLevelReader level, Predicate<BlockState> filter) {
            this.level = level;
            this.matchAir = filter.test(Blocks.AIR.defaultBlockState());
        }

        private LevelChunkSection section(int x, int y, int z) {
            if (!CubicBlockBounds.contains(x) || !CubicBlockBounds.contains(y) || !CubicBlockBounds.contains(z)) {
                return null;
            }
            CubePos key = CubePos.of(Coords.blockToCube(x), Coords.blockToCube(y), Coords.blockToCube(z));
            // Like vanilla LevelReader.getChunk, scans acquire only the storage they actually visit.
            // containsKey also caches a client's unavailable cube rather than retrying for every block.
            if (!cubes.containsKey(key)) {
                cubes.put(key, level.cc_getCube(key.getX(), key.getY(), key.getZ(), ChunkStatus.FULL, true));
            }
            CubeAccess cube = cubes.get(key);
            return cube == null ? null : cube.getSection(Coords.blockToIndex(x, y, z));
        }
    }
}
