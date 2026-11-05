package io.github.opencubicchunks.cubicchunks.test.world.level.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubeMobNavigation extends BaseTest {
    @TempDir
    Path directory;

    @Test
    void regionReadsFullCubeCoordinatesFluidsAndBoundariesWithoutColumns() throws Exception {
        try (var f = new CubeMobTestWorld(directory)) {
            BlockPos pos = new BlockPos(31, -32000000, 2);
            f.block(pos, Blocks.STONE.defaultBlockState());
            f.block(pos.east(), Blocks.WATER.defaultBlockState());
            var region = new PathNavigationRegion(f.level, pos.offset(-4, -4, -4), pos.offset(4, 4, 4));
            assertEquals(Blocks.STONE.defaultBlockState(), region.getBlockState(pos));
            assertFalse(region.getFluidState(pos.east()).isEmpty());
            assertEquals(pos.getY() - 4, region.getMinY());
            assertEquals(9, region.getHeight());
            assertTrue(region.getBlockState(pos.above(5)).is(Blocks.BARRIER));
            assertTrue(region.getBlockState(pos.below(1)).is(Blocks.BARRIER));
            verify(f.level.getChunkSource(), never()).getChunkNow(anyInt(), anyInt());
        }
    }

    @Test
    void nativeGroundPathCrossesCubeBoundaryAtBothSignsAndCanStepDown() throws Exception {
        for (int y : new int[] { 32000000, -32000000 }) {
            try (var f = new CubeMobTestWorld(directory.resolve(Integer.toString(y)))) {
                f.floor(y);
                var pig = f.pig(y);
                pig.snapTo(29.5, y, 2.5);
                var path = pig.getNavigation().createPath(new BlockPos(36, y, 2), 0);
                assertNotNull(path);
                assertTrue(path.canReach());
                assertEquals(y, path.getEndNode().y);
                assertTrue(path.getEndNode().x >= 35);
                for (int x = 33; x <= 40; x++) {
                    for (int z = -4; z <= 8; z++) {
                        f.block(new BlockPos(x, y - 1, z), Blocks.AIR.defaultBlockState());
                        f.block(new BlockPos(x, y - 2, z), Blocks.STONE.defaultBlockState());
                    }
                }
                var descending = pig.getNavigation().createPath(new BlockPos(36, y - 1, 2), 0);
                assertNotNull(descending);
                assertTrue(descending.canReach());
                assertEquals(y - 1, descending.getEndNode().y);
            }
        }
    }

    @Test
    void flyingAndSwimmingPathfindersUseTheSameFullHeightRegion() throws Exception {
        for (int y : new int[] { 32000000, -32000000 }) {
            try (var f = new CubeMobTestWorld(directory.resolve("otherNavigation" + y))) {
                f.floor(y);
                var flyer = f.pig(y + 5);
                var navigation = new net.minecraft.world.entity.ai.navigation.FlyingPathNavigation(flyer, f.level);
                var flyingPath = navigation.createPath(new BlockPos(8, y + 6, 2), 0);
                assertNotNull(flyingPath);
                assertTrue(flyingPath.canReach());
                assertEquals(y + 6, flyingPath.getEndNode().y);
                for (int x = 0; x <= 12; x++) {
                    for (int z = 0; z <= 5; z++) {
                        for (int depth = 0; depth < 4; depth++) {
                            f.block(new BlockPos(x, y + depth, z), Blocks.WATER.defaultBlockState());
                        }
                    }
                }
                var swimmer = net.minecraft.world.entity.EntityTypes.DOLPHIN.create(f.level, net.minecraft.world.entity.EntitySpawnReason.LOAD);
                assertNotNull(swimmer);
                swimmer.snapTo(2.5, y + 1, 2.5);
                var swimmingPath = swimmer.getNavigation().createPath(new BlockPos(8, y + 1, 2), 0);
                assertNotNull(swimmingPath);
                assertTrue(swimmingPath.canReach());
                assertEquals(y + 1, swimmingPath.getEndNode().y);
            }
        }
    }

    @Test
    void missingTargetOrGapNeverBecomesWalkableAir() throws Exception {
        try (var f = new CubeMobTestWorld(directory)) {
            int y = 32000000;
            f.floor(y);
            var pig = f.pig(y);
            assertNull(pig.getNavigation().createPath(new BlockPos(80, y, 2), 0));
            f.cubes.remove(CubePos.from(new BlockPos(32, y, 2)));
            var path = pig.getNavigation().createPath(java.util.Set.of(new BlockPos(36, y, 2)), 0);
            assertTrue(path == null || !path.canReach());
            verify(f.level.getChunkSource(), never()).getChunkNow(anyInt(), anyInt());
        }
    }

    @Test
    void pathTypeCacheCannotMixBlocksWhosePackedPositionsAlias() throws Exception {
        try (var f = new CubeMobTestWorld(directory)) {
            BlockPos first = new BlockPos(2, 32000000, 2);
            BlockPos alias = first.below(4096);
            assertEquals(first.asLong(), alias.asLong());
            f.block(first, Blocks.STONE.defaultBlockState());
            f.block(alias, Blocks.AIR.defaultBlockState());
            when(f.level.getPathTypeCache()).thenReturn(new PathTypeCache());
            var pig = f.pig(first.getY());
            var context = new PathfindingContext(f.level, pig);
            assertEquals(PathType.BLOCKED, context.getPathTypeFromState(first.getX(), first.getY(), first.getZ()));
            assertEquals(PathType.OPEN, context.getPathTypeFromState(alias.getX(), alias.getY(), alias.getZ()));
            f.block(first, Blocks.AIR.defaultBlockState());
            assertEquals(PathType.OPEN, context.getPathTypeFromState(first.getX(), first.getY(), first.getZ()));
        }
    }

    @Test
    void nodeIdentityUsesFullPositionAndIsClearedBetweenSearches() throws Exception {
        try (var f = new CubeMobTestWorld(directory)) {
            int y = 32000000;
            var pig = f.pig(y);
            var region = new PathNavigationRegion(f.level, pig.blockPosition().offset(-4, -4, -4), pig.blockPosition().offset(4, 4, 4));
            WalkNodeEvaluator nodes = new WalkNodeEvaluator();
            var get = NodeEvaluator.class.getDeclaredMethod("getNode", int.class, int.class, int.class);
            get.setAccessible(true);
            nodes.prepare(region, pig);
            Node a = (Node) get.invoke(nodes, 2, y, 2);
            Node b = (Node) get.invoke(nodes, 2, y + 256, 2);
            assertEquals(Node.createHash(2, y, 2), Node.createHash(2, y + 256, 2));
            assertNotSame(a, b);
            assertSame(a, get.invoke(nodes, 2, y, 2));
            nodes.done();
            nodes.prepare(region, pig);
            assertNotSame(a, get.invoke(nodes, 2, y, 2));
            nodes.done();
        }
    }

    @Test
    void nativeCollisionIteratorSeesFloorAndStopsAtMissingCube() throws Exception {
        try (var f = new CubeMobTestWorld(directory)) {
            int y = -32000000;
            f.floor(y);
            var pig = f.pig(y);
            assertTrue(f.level.noCollision(pig, pig.getBoundingBox()));
            assertFalse(f.level.noCollision(pig, pig.getBoundingBox().move(0, -0.1, 0)));
            assertFalse(f.level.noCollision(pig, new AABB(70, y, 2, 71, y + 1, 3)));
            verify(f.level.getChunkSource(), never()).getChunkNow(anyInt(), anyInt());
        }
    }

    @Test
    void wanderingSurfaceAndLoadedAreaChecksUseFullHeight() throws Exception {
        try (var f = new CubeMobTestWorld(directory)) {
            int y = 32000000;
            f.floor(y);
            PathfinderMob pig = (PathfinderMob) f.pig(y);
            BlockPos pos = new BlockPos(3, y - 1, 2);
            assertEquals(pos.above(), LandRandomPos.movePosUpOutOfSolid(pig, pos));
            assertNull(LandRandomPos.movePosUpOutOfSolid(pig, pos.above(1000)));
            assertTrue(f.level.hasChunkAt(pos));
            assertFalse(f.level.hasChunkAt(pos.above(4096)));
            assertTrue(f.level.hasChunksAt(2, y, 2, 34, y + 1, 2));
            assertFalse(f.level.hasChunksAt(2, y, 2, 34, y + 1000, 2));
        }
    }

    @Test
    void nativeFluidInteractionUsesCubeSectionsAndResetsAfterLeavingWater() throws Exception {
        for (int y : new int[] { 32000000, -32000000 }) {
            try (var f = new CubeMobTestWorld(directory.resolve("water" + y))) {
                f.floor(y);
                BlockPos pos = new BlockPos(2, y, 2);
                f.block(pos, Blocks.WATER.defaultBlockState());
                f.block(pos.above(), Blocks.WATER.defaultBlockState());
                var pig = f.pig(y);
                var fluids = new net.minecraft.world.entity.EntityFluidInteraction(java.util.Set.of(net.minecraft.tags.FluidTags.WATER));
                assertTrue(fluids.update(pig, false));
                assertTrue(fluids.getFluidHeight(net.minecraft.tags.FluidTags.WATER) > 0);
                pig.snapTo(pig.position().add(0, 4096, 0));
                assertFalse(fluids.update(pig, false));
                assertEquals(0, fluids.getFluidHeight(net.minecraft.tags.FluidTags.WATER));
                verify(f.level, never()).getChunk(anyInt(), anyInt(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
            }
        }
    }

    @Test
    void wanderRejectsUnloadedCandidatesBeforeReadingThem() throws Exception {
        try (var f = new CubeMobTestWorld(directory)) {
            var pig = (PathfinderMob) f.pig(-32000000);
            f.floor(pig.getBlockY());
            assertFalse(net.minecraft.world.entity.ai.util.GoalUtils.isOutsideLimits(pig.blockPosition(), pig));
            assertTrue(net.minecraft.world.entity.ai.util.GoalUtils.isOutsideLimits(pig.blockPosition().above(4096), pig));
        }
    }

    @Test
    void regionClipsSupportedEndpointsAndOrdinaryRegionKeepsNativeHeight() throws Exception {
        try (var f = new CubeMobTestWorld(directory)) {
            for (int y : new int[] { CubicBlockBounds.MIN, CubicBlockBounds.MAX }) {
                BlockPos pos = new BlockPos(2, y, 2);
                f.cube(pos);
                var region = new PathNavigationRegion(f.level, pos.below(8), pos.above(8));
                assertTrue(region.getMinY() >= CubicBlockBounds.MIN);
                assertTrue(region.getMaxY() <= CubicBlockBounds.MAX);
                assertTrue(region.getBlockState(pos).isAir());
            }
            when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
            var ordinary = new PathNavigationRegion(f.level, new BlockPos(0, 0, 0), new BlockPos(4, 4, 4));
            assertEquals(-64, ordinary.getMinY());
            assertEquals(384, ordinary.getHeight());
        }
    }
}
