package io.github.opencubicchunks.cubicchunks.test.world.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.server.level.ServerCubeCache;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.testutils.PoiTestSupport;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.poi.CubicPoiManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.PortalForcer;
import net.minecraft.world.level.portal.PortalShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TestCubePortals extends BaseTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeFrameDetectionCreationAndBrokenFrameUseFullHeight(int y) {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        Misc.setupLevelHeight(level, -64, 384);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        when(level.getBlockState(any())).thenAnswer(i -> blocks.getOrDefault(i.getArgument(0), Blocks.AIR.defaultBlockState()));
        when(level.setBlock(any(), any(), anyInt())).thenAnswer(i -> {
            blocks.put(((BlockPos) i.getArgument(0)).immutable(), i.getArgument(1));
            return true;
        });
        BlockPos base = new BlockPos(31, y - 1, -1);
        for (int x = -1; x <= 2; x++) {
            for (int h = -1; h <= 3; h++) {
                if (x == -1 || x == 2 || h == -1 || h == 3) {
                    blocks.put(base.offset(x, h, 0), Blocks.OBSIDIAN.defaultBlockState());
                }
            }
        }
        var frame = PortalShape.findEmptyPortalShape(level, base.above(2), Direction.Axis.X).orElseThrow();
        assertTrue(frame.isValid());
        frame.createPortalBlocks(level);
        assertTrue(PortalShape.findAnyShape(level, base.above(2), Direction.Axis.X).isComplete());
        assertEquals(6, blocks.values().stream().filter(state -> state.is(Blocks.NETHER_PORTAL)).count());
        blocks.remove(base.offset(2, 1, 0));
        assertFalse(PortalShape.findAnyShape(level, base.above(2), Direction.Axis.X).isValid());
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativePortalConstructionSearchesAroundDestinationInsteadOfColumnSurface(int y) {
        assertConstructedAt(y, true, y, true);
    }

    @Test
    void fallbackBuildsUsableNegativePortalAndRespectsBothCubicEdges() {
        assertConstructedAt(-32000, true, -32000, false);
        assertConstructedAt(CubicBlockBounds.MIN, true, CubicBlockBounds.MIN + 1, false);
        assertConstructedAt(CubicBlockBounds.MAX, true, CubicBlockBounds.MAX - 9, false);
    }

    @Test
    void ordinaryPortalFallbackKeepsDimensionHeightAndSeaLevelRules() {
        assertConstructedAt(-32000, false, 70, false);
        assertConstructedAt(32000, false, 310, false);
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void portalTicketsRetainDestinationY(int y) throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            var entity = world.pig(y);
            BlockPos destination = new BlockPos(31, y, -1);
            entity.placePortalTicket(destination);
            verify((ServerCubeCache) world.level.getChunkSource()).cc_addTicketWithRadius(TicketType.PORTAL, CloPos.cube(CubePos.from(destination)),
                    3);
            verify(world.level.getChunkSource(), never()).addTicketWithRadius(any(), any(), anyInt());
        }
    }

    @Test
    void nativePortalSelectionFindsSavedUnloadedHeightsAndHonoursLiveRemoval() throws Exception {
        BlockPos positive = new BlockPos(31, 32000, -1);
        BlockPos negative = new BlockPos(31, -32000, -1);
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X);
        try (var world = new CubeMobTestWorld(directory.resolve("entities")); var poi = PoiTestSupport.attach(world.level, directory)) {
            world.block(positive, portal);
            world.block(negative, portal);
            poi.flushAll();
        }
        try (var world = new CubeMobTestWorld(directory.resolve("entities"))) {
            // Restore actual terrain without preloading the POI manager's cube sections.
            world.block(positive, portal);
            world.block(negative, portal);
            try (var poi = PoiTestSupport.attach(world.level, directory)) {
                var forcer = new PortalForcer(world.level);
                assertEquals(positive, forcer.findClosestPortalPosition(new BlockPos(31, 70, -1), false, world.level.getWorldBorder()).orElseThrow());
                assertEquals(negative,
                        forcer.findClosestPortalPosition(new BlockPos(31, -70, -1), false, world.level.getWorldBorder()).orElseThrow());
                poi.remove(positive);
                assertEquals(negative, forcer.findClosestPortalPosition(new BlockPos(31, 70, -1), false, world.level.getWorldBorder()).orElseThrow());
                assertTrue(forcer.findClosestPortalPosition(new BlockPos(200, 70, -1), false, world.level.getWorldBorder()).isEmpty());
                world.level.getWorldBorder().setCenter(1000, 1000);
                world.level.getWorldBorder().setSize(16);
                assertTrue(forcer.findClosestPortalPosition(new BlockPos(31, 70, -1), false, world.level.getWorldBorder()).isEmpty());
            }
        }
    }

    @Test
    void selectingNearestPortalDoesNotLoadTerrainForFartherCandidates() throws Exception {
        BlockPos nearest = new BlockPos(31, 32000, -1);
        BlockPos farther = new BlockPos(31, -32000, -1);
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X);
        try (var world = new CubeMobTestWorld(directory.resolve("entities")); var poi = PoiTestSupport.attach(world.level, directory)) {
            world.block(nearest, portal);
            world.block(farther, portal);
            clearInvocations(world.level);
            assertEquals(nearest, new PortalForcer(world.level)
                    .findClosestPortalPosition(new BlockPos(31, 70, -1), false, world.level.getWorldBorder()).orElseThrow());
            verify(world.level).getBlockState(nearest);
            verify(world.level, never()).getBlockState(farther);
        }
    }

    @Test
    void ordinaryPortalQueryAndTicketUseNativeColumnApis() {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(false);
        var poi = mock(PoiManager.class);
        when(((CubicPoiManager) poi).cc_cubePoi()).thenReturn(null);
        when(level.getPoiManager()).thenReturn(poi);
        when(poi.getInSquare(any(), any(), anyInt(), any())).thenAnswer(i -> Stream.empty());
        assertTrue(new PortalForcer(level).findClosestPortalPosition(BlockPos.ZERO, false, new WorldBorder()).isEmpty());
        verify(poi).ensureLoadedAndValid(level, BlockPos.ZERO, 128);
        var entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        doCallRealMethod().when(entity).placePortalTicket(any());
        entity.placePortalTicket(BlockPos.ZERO);
        verify(level.getChunkSource()).addTicketWithRadius(TicketType.PORTAL, new ChunkPos(0, 0), 3);
    }

    private static void assertConstructedAt(int originY, boolean cubic, int expectedY, boolean ground) {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        Misc.setupLevelHeight(level, -64, 384);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(cubic);
        when(level.getLogicalHeight()).thenReturn(384);
        when(level.getWorldBorder()).thenReturn(new WorldBorder());
        Map<BlockPos, BlockState> placed = new HashMap<>();
        when(level.getBlockState(any())).thenAnswer(i -> {
            BlockPos pos = i.getArgument(0);
            assertTrue(!cubic || CubicBlockBounds.contains(pos), "Search must stay inside cubic coordinates");
            return placed.getOrDefault(pos, ground && pos.getY() < originY ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        });
        when(level.setBlockAndUpdate(any(), any())).thenAnswer(i -> {
            placed.put(((BlockPos) i.getArgument(0)).immutable(), i.getArgument(1));
            return true;
        });
        when(level.setBlock(any(), any(), anyInt())).thenAnswer(i -> {
            placed.put(((BlockPos) i.getArgument(0)).immutable(), i.getArgument(1));
            return true;
        });
        BlockPos origin = new BlockPos(31, originY, -1);
        // One horizontal candidate isolates the native vertical search and frame construction from expensive terrain fixtures.
        try (var positions = mockStatic(BlockPos.class, CALLS_REAL_METHODS)) {
            positions.when(() -> BlockPos.spiralAround(any(), eq(16), eq(Direction.EAST), eq(Direction.SOUTH)))
                    .thenAnswer(i -> List.of(((BlockPos) i.getArgument(0)).mutable()));
            var result = new PortalForcer(level).createPortal(origin, Direction.Axis.X).orElseThrow();
            assertEquals(expectedY, result.minCorner.getY());
            assertEquals(2, result.axis1Size);
            assertEquals(3, result.axis2Size);
        }
        assertEquals(6, placed.values().stream().filter(state -> state.is(Blocks.NETHER_PORTAL)).count());
        assertEquals(-64, level.getMinY());
        assertEquals(319, level.getMaxY());
    }
}
