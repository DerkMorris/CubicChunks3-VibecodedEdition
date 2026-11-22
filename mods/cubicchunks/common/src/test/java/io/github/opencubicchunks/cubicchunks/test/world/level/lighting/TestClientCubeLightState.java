package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.ClientCubeLightState;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightManager;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightSolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;

class TestClientCubeLightState extends BaseTest {
    private static final CubePos POSITION = CubePos.of(-2, -800000, 3);
    private static final CubePos NEIGHBOR = CubePos.of(-1, -800000, 3);

    @Test
    void completedCellRevokesOnlySolverInputsAtNegativeCubeAndSectionBoundaries() {
        CubePos center = CubePos.of(-2, -800000, -3);
        for (int local : new int[] { 14, 15, 16, 17, 31 }) {
            for (boolean queued : new boolean[] { false, true }) {
                Fixture fixture = new Fixture();
                Map<CubePos, CubeLightView> publications = new HashMap<>();
                for (CubePos pos : CubeWorldLightManager.halo(center).positions()) {
                    fixture.add(pos);
                    publications.put(pos, exact(pos));
                }
                CubeAccess edited = fixture.cubes.get(center);
                BlockPos block = center.asBlockPos(local, local, local);
                edited.setBlockState(block, Blocks.REDSTONE_LAMP.defaultBlockState());
                var state = fixture.state;
                publications.forEach((pos, view) -> {
                    CubeAccess cube = fixture.cubes.get(pos);
                    if (queued) {
                        state.expectScoped(cube, 7);
                    }
                    else {
                        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 7, view, state.generation()));
                    }
                });
                var previous = edited.setBlockState(block, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true));
                state.invalidateCell(block, previous);
                int low = local < 15 ? -1 : 0;
                int high = local > 16 ? 1 : 0;
                int affectedCount = 0;
                for (var entry : publications.entrySet()) {
                    CubePos pos = entry.getKey();
                    int dx = pos.getX() - center.getX();
                    int dy = pos.getY() - center.getY();
                    int dz = pos.getZ() - center.getZ();
                    boolean affected = dx >= low && dx <= high && dy >= low && dy <= high && dz >= low && dz <= high;
                    String context = "local=" + local + " queued=" + queued + " target=" + pos;
                    if (affected) {
                        affectedCount++;
                        assertNull(state.view(pos), context);
                    } else if (!queued) {
                        assertSame(entry.getValue(), state.view(pos), context);
                    }
                    CubeAccess cube = fixture.cubes.get(pos);
                    assertEquals(!affected, state.applyScoped(cube, cube.cc_getLightRevision(), 7, entry.getValue(), state.generation()), context);
                }
                assertEquals(local == 15 || local == 16 ? 1 : 8, affectedCount);
            }
        }
    }

    @Test
    void clippedCellPreservationMatchesTheFullSolverAndRetainsActualEmitterInvalidation() {
        for (int x : new int[] { 16, 31 }) {
            Fixture fixture = new Fixture();
            LevelCube edited = fixture.add(POSITION);
            LevelCube neighbor = fixture.add(NEIGHBOR);
            BlockPos block = POSITION.asBlockPos(x, 16, 16);
            edited.setBlockState(block, Blocks.REDSTONE_LAMP.defaultBlockState());
            var state = fixture.state;
            CubeLightView before = solved(fixture, NEIGHBOR, Integer.MIN_VALUE);
            state.applyScoped(neighbor, neighbor.cc_getLightRevision(), 7, before, state.generation());
            var previous = edited.setBlockState(block, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true));
            state.invalidateCell(block, previous);
            CubeLightView after = solved(fixture, NEIGHBOR, Integer.MIN_VALUE);
            if (x == 16) {
                assertSame(before, state.view(NEIGHBOR));
                assertSameLight(before, after);
            } else {
                assertNull(state.view(NEIGHBOR));
                assertEquals(0, before.sample(LightLayer.BLOCK, NEIGHBOR.asBlockPos(0, 16, 16)).orElseThrow().upper());
                assertEquals(14, after.sample(LightLayer.BLOCK, NEIGHBOR.asBlockPos(0, 16, 16)).orElseThrow().upper());
            }
        }
    }

    @Test
    void authoritativePreEditSkyProvesAnUnloadedRoofAndMatchesTheFullSolver() {
        for (int y : new int[] { 16, 31 }) {
            Fixture fixture = new Fixture();
            LevelCube edited = fixture.add(POSITION);
            LevelCube neighbor = fixture.add(NEIGHBOR);
            BlockPos block = POSITION.asBlockPos(24, y, 8);
            int roofEdge = POSITION.maxCubeY() + 160;
            CubePos deep = CubePos.of(POSITION.getX(), POSITION.getY() - 4, POSITION.getZ());
            LevelCube distant = fixture.add(deep);
            CubePos queued = CubePos.of(POSITION.getX(), POSITION.getY() - 5, POSITION.getZ());
            LevelCube waiting = fixture.add(queued);
            var state = fixture.state;
            CubeLightView preserved = solved(fixture, deep, roofEdge);
            state.applyScoped(edited, edited.cc_getLightRevision(), 7, solved(fixture, POSITION, roofEdge), state.generation());
            state.applyScoped(neighbor, neighbor.cc_getLightRevision(), 7, solved(fixture, NEIGHBOR, roofEdge), state.generation());
            state.applyScoped(distant, distant.cc_getLightRevision(), 7, preserved, state.generation());
            state.expectScoped(waiting, 7);
            if (y == 31) {
                LevelCube upper = fixture.add(CubePos.from(block.above()));
                state.applyScoped(upper, upper.cc_getLightRevision(), 7, solved(fixture, upper.cc_getCubePos(), roofEdge), state.generation());
            }
            assertTrue(state.view(CubePos.from(block.above())).sample(LightLayer.SKY, block.above()).orElseThrow().upper() < 15);
            var previous = edited.setBlockState(block, Blocks.WATER.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_SKIP_ON_PLACE);
            state.invalidateCell(block, previous);
            assertNull(state.view(POSITION));
            assertNull(state.view(NEIGHBOR));
            assertSame(preserved, state.view(deep));
            assertTrue(state.applyScoped(waiting, waiting.cc_getLightRevision(), 7, solved(fixture, queued, roofEdge), state.generation()));
            assertSameLight(preserved, solved(fixture, deep, roofEdge));
        }
    }

    @Test
    void acceptedHigherCubeKeepsProvingRepeatedEditsWithoutRetainingASeparateCertificate() {
        Fixture fixture = new Fixture();
        LevelCube edited = fixture.add(POSITION);
        CubePos higher = CubePos.of(POSITION.getX(), POSITION.getY() + 3, POSITION.getZ());
        LevelCube upper = fixture.add(higher);
        CubePos deep = CubePos.of(POSITION.getX(), POSITION.getY() - 3, POSITION.getZ());
        LevelCube distant = fixture.add(deep);
        int roofEdge = higher.maxCubeY() + 160;
        var state = fixture.state;
        CubeLightView preserved = solved(fixture, deep, roofEdge);
        state.applyScoped(upper, upper.cc_getLightRevision(), 7, solved(fixture, higher, roofEdge), state.generation());
        state.applyScoped(distant, distant.cc_getLightRevision(), 7, preserved, state.generation());
        BlockPos block = POSITION.asBlockPos(8, 16, 8);
        for (var fluid : List.of(Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.AIR.defaultBlockState())) {
            var previous = edited.setBlockState(block, fluid, net.minecraft.world.level.block.Block.UPDATE_SKIP_ON_PLACE);
            state.invalidateCell(block, previous);
            assertNull(state.view(POSITION), "The edited cube has no accepted or display field to use as a proof");
            assertSame(preserved, state.view(deep));
            assertSameLight(preserved, solved(fixture, deep, roofEdge));
        }
        state.invalidateScoped(higher, 8);
        assertNotNull(state.displayView(higher));
        var previous = edited.setBlockState(block, Blocks.WATER.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_SKIP_ON_PLACE);
        state.invalidateCell(block, previous);
        assertNull(state.view(deep), "A revoked higher field retained only for display cannot certify another edit");
    }

    @Test
    void unloadedHigherEntryCannotProveSkyEvenIfItsOldAcceptedFieldWasNotExplicitlyForgotten() {
        Fixture fixture = new Fixture();
        LevelCube edited = fixture.add(POSITION);
        CubePos higher = CubePos.of(POSITION.getX(), POSITION.getY() + 3, POSITION.getZ());
        LevelCube upper = fixture.add(higher);
        CubePos deep = CubePos.of(POSITION.getX(), POSITION.getY() - 3, POSITION.getZ());
        LevelCube distant = fixture.add(deep);
        int roofEdge = higher.maxCubeY() + 160;
        var state = fixture.state;
        state.applyScoped(upper, upper.cc_getLightRevision(), 7, solved(fixture, higher, roofEdge), state.generation());
        state.applyScoped(distant, distant.cc_getLightRevision(), 7, solved(fixture, deep, roofEdge), state.generation());
        fixture.cubes.remove(higher);
        BlockPos block = POSITION.asBlockPos(8, 16, 8);
        var previous = edited.setBlockState(block, Blocks.WATER.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_SKIP_ON_PLACE);
        state.invalidateCell(block, previous);
        assertNull(state.view(deep));
    }

    @Test
    void staleForgottenRevokedAndReplacedDarkEntriesNeverProveCurrentSky() {
        for (String invalidity : List.of("stale", "forgotten", "revoked", "replaced")) {
            Fixture fixture = new Fixture();
            LevelCube edited = fixture.add(POSITION);
            CubePos deep = CubePos.of(POSITION.getX(), POSITION.getY() - 3, POSITION.getZ());
            LevelCube distant = fixture.add(deep);
            int roofEdge = POSITION.maxCubeY() + 160;
            var state = fixture.state;
            state.applyScoped(edited, edited.cc_getLightRevision(), 7, solved(fixture, POSITION, roofEdge), state.generation());
            state.applyScoped(distant, distant.cc_getLightRevision(), 7, solved(fixture, deep, roofEdge), state.generation());
            BlockPos block = POSITION.asBlockPos(8, 16, 8);
            if (invalidity.equals("forgotten")) {
                state.forget(POSITION);
            }
            if (invalidity.equals("revoked")) {
                state.invalidateScoped(POSITION, 8);
            }
            if (invalidity.equals("replaced")) {
                edited = fixture.add(POSITION);
            }
            if (invalidity.equals("stale")) {
                edited.setBlockState(block, Blocks.STONE.defaultBlockState(), 0);
            }
            var next = invalidity.equals("stale") ? Blocks.AIR.defaultBlockState() : Blocks.WATER.defaultBlockState();
            var previous = edited.setBlockState(block, next, net.minecraft.world.level.block.Block.UPDATE_SKIP_ON_PLACE);
            state.invalidateCell(block, previous);
            assertNull(state.view(deep), invalidity + " accepted data must not be treated as the exact pre-edit state");
        }
    }

    @Test
    void exactSkyFifteenCannotProveThatANewFluidEdgeWasAlreadyShadowed() {
        Fixture fixture = new Fixture();
        LevelCube edited = fixture.add(POSITION);
        CubePos deep = CubePos.of(POSITION.getX(), POSITION.getY() - 3, POSITION.getZ());
        LevelCube distant = fixture.add(deep);
        var state = fixture.state;
        state.applyScoped(edited, edited.cc_getLightRevision(), 7, solved(fixture, POSITION, Integer.MIN_VALUE), state.generation());
        state.applyScoped(distant, distant.cc_getLightRevision(), 7, solved(fixture, deep, Integer.MIN_VALUE), state.generation());
        BlockPos block = POSITION.asBlockPos(8, 16, 8);
        assertEquals(15, state.view(POSITION).sample(LightLayer.SKY, block.above()).orElseThrow().upper());
        var previous = edited.setBlockState(block, Blocks.WATER.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_SKIP_ON_PLACE);
        state.invalidateCell(block, previous);
        assertNull(state.view(deep));
    }

    @Test
    void equalSkyEdgesKeepDistantAcceptedAndQueuedLightWhileRevokingTheBlockHalo() {
        Fixture fixture = new Fixture();
        LevelCube edited = fixture.add(POSITION);
        BlockPos block = POSITION.asBlockPos(24, 8, 8);
        edited.setBlockState(block, Blocks.REDSTONE_LAMP.defaultBlockState());
        LevelCube neighbor = fixture.add(NEIGHBOR);
        CubePos deep = CubePos.of(POSITION.getX(), POSITION.getY() - 10, POSITION.getZ());
        LevelCube distant = fixture.add(deep);
        CubePos queued = CubePos.of(POSITION.getX(), POSITION.getY() + 10, POSITION.getZ());
        LevelCube waiting = fixture.add(queued);
        CubeLightView preserved = exact(deep);
        var state = fixture.state;
        assertTrue(state.applyScoped(edited, edited.cc_getLightRevision(), 7, exact(POSITION), state.generation()));
        assertTrue(state.applyScoped(neighbor, neighbor.cc_getLightRevision(), 7, exact(NEIGHBOR), state.generation()));
        assertTrue(state.applyScoped(distant, distant.cc_getLightRevision(), 7, preserved, state.generation()));
        state.expectScoped(waiting, 7);
        var previous = edited.setBlockState(block, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true));
        state.invalidateCell(block, previous);
        assertNull(state.view(POSITION));
        assertNull(state.view(NEIGHBOR));
        assertSame(preserved, state.view(deep), "An equal-opacity emitter edit cannot alter sky at a remote height");
        assertTrue(state.applyScoped(waiting, waiting.cc_getLightRevision(), 7, exact(queued), state.generation()));
        assertFalse(state.applyScoped(edited, edited.cc_getLightRevision(), 7, exact(POSITION), state.generation()));
        assertTrue(state.applyScoped(edited, edited.cc_getLightRevision(), 8, exact(POSITION), state.generation()));
    }

    @Test
    void loadedRoofOnEditedRayPreservesDistantLightDespiteOffRayHolesAndFluidEmission() {
        for (var fluid : List.of(Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState())) {
            Fixture fixture = new Fixture();
            LevelCube edited = fixture.add(POSITION);
            LevelCube neighbor = fixture.add(NEIGHBOR);
            CubePos roofPos = CubePos.of(POSITION.getX(), POSITION.getY() + 4, POSITION.getZ());
            LevelCube roof = fixture.add(roofPos);
            roof.setBlockState(roofPos.asBlockPos(24, 4, 8), Blocks.STONE.defaultBlockState());
            assertTrue(roof.getBlockState(roofPos.asBlockPos(25, 4, 8)).isAir(), "Holes on other rays do not affect this cell edit");
            CubePos deep = CubePos.of(POSITION.getX(), POSITION.getY() - 10, POSITION.getZ());
            LevelCube distant = fixture.add(deep);
            CubePos queued = CubePos.of(POSITION.getX(), POSITION.getY() + 10, POSITION.getZ());
            LevelCube waiting = fixture.add(queued);
            var state = fixture.state;
            CubeLightView preserved = exact(deep);
            state.applyScoped(edited, edited.cc_getLightRevision(), 7, openSky(POSITION), state.generation());
            state.applyScoped(neighbor, neighbor.cc_getLightRevision(), 7, exact(NEIGHBOR), state.generation());
            state.applyScoped(distant, distant.cc_getLightRevision(), 7, preserved, state.generation());
            state.expectScoped(roof, 7); // A loaded roof is sufficient even while its own light publication is queued.
            state.expectScoped(waiting, 7);
            BlockPos block = POSITION.asBlockPos(24, 31, 8);
            var previous = edited.setBlockState(block, fluid, net.minecraft.world.level.block.Block.UPDATE_SKIP_ON_PLACE);
            state.invalidateCell(block, previous);
            assertNull(state.view(POSITION));
            assertNull(state.view(NEIGHBOR), "Local block-light changes still revoke all affected neighbors");
            assertSame(preserved, state.view(deep));
            assertTrue(state.applyScoped(waiting, waiting.cc_getLightRevision(), 7, exact(queued), state.generation()));
        }
    }

    @Test
    void roofInCurrentCubePreservesDistantLightUntilThatRoofIsRemoved() {
        Fixture fixture = new Fixture();
        LevelCube edited = fixture.add(POSITION);
        BlockPos roof = POSITION.asBlockPos(8, 20, 8);
        edited.setBlockState(roof, Blocks.STONE.defaultBlockState());
        CubePos deep = CubePos.of(POSITION.getX(), POSITION.getY() - 10, POSITION.getZ());
        LevelCube distant = fixture.add(deep);
        var state = fixture.state;
        CubeLightView preserved = exact(deep);
        state.applyScoped(distant, distant.cc_getLightRevision(), 7, preserved, state.generation());
        BlockPos water = POSITION.asBlockPos(8, 8, 8);
        var previous = edited.setBlockState(water, Blocks.WATER.defaultBlockState());
        state.invalidateCell(water, previous);
        assertSame(preserved, state.view(deep));
        previous = edited.setBlockState(roof, Blocks.AIR.defaultBlockState());
        state.invalidateCell(roof, previous);
        assertNull(state.view(deep), "The removed roof and a lower fluid edge cannot certify an unchanged topmost sky edge");
        assertFalse(state.applyScoped(distant, distant.cc_getLightRevision(), 7, preserved, state.generation()));
    }

    @Test
    void openRayAndNewFluidEdgeCannotCertifyUnchangedSkyAtEitherCubeBoundary() {
        for (int y : new int[] { 0, 16, 30, 31 }) {
            Fixture fixture = new Fixture();
            LevelCube edited = fixture.add(POSITION);
            CubePos roofPos = CubePos.of(POSITION.getX(), POSITION.getY() + 4, POSITION.getZ());
            LevelCube roof = fixture.add(roofPos);
            roof.setBlockState(roofPos.asBlockPos(9, 4, 8), Blocks.STONE.defaultBlockState());
            CubePos deep = CubePos.of(POSITION.getX(), POSITION.getY() - 10, POSITION.getZ());
            LevelCube distant = fixture.add(deep);
            var state = fixture.state;
            CubeLightView old = exact(deep);
            state.applyScoped(distant, distant.cc_getLightRevision(), 7, old, state.generation());
            state.applyScoped(roof, roof.cc_getLightRevision(), 7, openSky(roofPos), state.generation());
            BlockPos block = POSITION.asBlockPos(8, y, 8);
            var previous = edited.setBlockState(block, Blocks.WATER.defaultBlockState());
            state.invalidateCell(block, previous);
            assertNull(state.view(deep), "A hole on the edited ray must retain conservative revocation at local Y=" + y);
            assertFalse(state.applyScoped(distant, distant.cc_getLightRevision(), 7, old, state.generation()));
        }
    }

    @Test
    void equivalentGrassEditKeepsLocalNeighborAndQueuedLightWithoutRefresh() {
        Fixture fixture = new Fixture();
        LevelCube edited = fixture.add(POSITION);
        LevelCube neighbor = fixture.add(NEIGHBOR);
        BlockPos block = POSITION.asBlockPos(8, 31, 8);
        edited.setBlockState(block, Blocks.GRASS_BLOCK.defaultBlockState());
        var state = fixture.state;
        CubeLightView own = exact(POSITION);
        CubeLightView adjacent = exact(NEIGHBOR);
        long revision = edited.cc_getLightRevision();
        state.applyScoped(edited, revision, 7, own, state.generation());
        state.applyScoped(neighbor, neighbor.cc_getLightRevision(), 7, adjacent, state.generation());
        state.expectScoped(edited, 8);
        var previous = edited.setBlockState(block, Blocks.DIRT.defaultBlockState());
        state.invalidateCell(block, previous);
        assertEquals(revision, edited.cc_getLightRevision());
        assertSame(own, state.view(POSITION));
        assertSame(adjacent, state.view(NEIGHBOR));
        assertTrue(state.applyScoped(edited, revision, 8, own, state.generation()), "An in-flight publication retains its equivalent block input");
        for (int tick = 0; tick < 20; tick++) {
            assertNull(state.pollRefresh());
        }
    }

    @Test
    void roofRemovalRevokesDistantLightAtInternalAndBothExternalEdges() {
        for (int y : new int[] { 0, 16, 31 }) {
            Fixture fixture = new Fixture();
            LevelCube edited = fixture.add(POSITION);
            fixture.add(CubePos.of(POSITION.getX(), POSITION.getY() - 1, POSITION.getZ()));
            fixture.add(CubePos.of(POSITION.getX(), POSITION.getY() + 1, POSITION.getZ()));
            CubePos deep = CubePos.of(POSITION.getX(), POSITION.getY() - 10, POSITION.getZ());
            LevelCube distant = fixture.add(deep);
            BlockPos block = POSITION.asBlockPos(8, y, 8);
            edited.setBlockState(block, Blocks.STONE.defaultBlockState());
            var state = fixture.state;
            state.applyScoped(edited, edited.cc_getLightRevision(), 7, openSky(POSITION), state.generation());
            state.applyScoped(distant, distant.cc_getLightRevision(), 7, exact(deep), state.generation());
            var previous = edited.setBlockState(block, Blocks.AIR.defaultBlockState());
            state.invalidateCell(block, previous);
            assertNull(state.view(deep), "Changed sky edge at local Y=" + y + " must revoke remote dependent light");
            assertFalse(state.applyScoped(distant, distant.cc_getLightRevision(), 7, exact(deep), state.generation()));
        }
    }

    @Test
    void unknownBoundaryNeighborKeepsConservativeSkyRevocation() {
        Fixture fixture = new Fixture();
        LevelCube edited = fixture.add(POSITION);
        BlockPos block = POSITION.asBlockPos(8, 31, 8);
        edited.setBlockState(block, Blocks.STONE.defaultBlockState());
        CubePos deep = CubePos.of(POSITION.getX(), POSITION.getY() - 10, POSITION.getZ());
        LevelCube distant = fixture.add(deep);
        var state = fixture.state;
        state.applyScoped(distant, distant.cc_getLightRevision(), 7, exact(deep), state.generation());
        var previous = edited.setBlockState(block, Blocks.AIR.defaultBlockState());
        state.invalidateCell(block, previous);
        assertNull(state.view(deep), "Missing upper cube cannot be assumed to have an unchanged shared face");
    }

    @Test
    void repeatedScopedEditsDoNotPostponeRefreshForever() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        var state = fixture.state;
        state.applyScoped(cube, cube.cc_getLightRevision(), 7, exact(POSITION), state.generation());
        for (int tick = 1; tick <= 20; tick++) {
            state.invalidateLocalAt(POSITION);
            if (tick < 20) {
                assertNull(state.pollRefresh());
            }
            else {
                assertEquals(new ClientCubeLightState.RefreshRequest(POSITION, 7), state.pollRefresh());
            }
        }
    }

    @Test
    void independentColumnsGetFreshRetryBudgetsWithoutPostponingRequestsOrBursting() {
        Fixture fixture = new Fixture();
        LevelCube first = fixture.add(POSITION);
        CubePos other = CubePos.of(POSITION.getX() + 8, POSITION.getY(), POSITION.getZ());
        LevelCube second = fixture.add(other);
        CubePos thirdPos = CubePos.of(POSITION.getX() + 16, POSITION.getY(), POSITION.getZ());
        LevelCube third = fixture.add(thirdPos);
        var state = fixture.state;
        state.applyScoped(first, first.cc_getLightRevision(), 7, exact(POSITION), state.generation());
        state.applyScoped(second, second.cc_getLightRevision(), 7, exact(other), state.generation());
        state.applyScoped(third, third.cc_getLightRevision(), 7, exact(thirdPos), state.generation());
        List<CubePos> columns = List.of(POSITION, other, thirdPos);
        List<Integer> requestTicks = new ArrayList<>();
        Set<CubePos> served = new HashSet<>();
        for (int tick = 1; tick <= 200; tick++) {
            CubePos edited = columns.get(tick % columns.size());
            state.invalidateLocalAt(edited);
            var request = state.pollRefresh();
            if (request != null) {
                requestTicks.add(tick);
                assertTrue(columns.contains(request.pos()));
                served.add(request.pos());
            }
        }
        assertEquals(Set.copyOf(columns), served, "Every independent column must make progress under continuous edits");
        assertRefreshSpacing(requestTicks);
    }

    @Test
    void twoAlternatingColumnsCannotPhaseLockToTheGlobalCooldown() {
        Fixture fixture = new Fixture();
        CubePos other = CubePos.of(POSITION.getX() + 8, POSITION.getY(), POSITION.getZ());
        addScoped(fixture, POSITION);
        addScoped(fixture, other);
        List<CubePos> columns = List.of(POSITION, other);
        Map<CubePos, Integer> served = new HashMap<>();
        List<Integer> ticks = new ArrayList<>();
        for (int tick = 1; tick <= 200; tick++) {
            fixture.state.invalidateLocalAt(columns.get(tick % 2));
            var request = fixture.state.pollRefresh();
            if (request != null) {
                ticks.add(tick);
                served.merge(request.pos(), 1, Integer::sum);
            }
        }
        assertEquals(Set.copyOf(columns), served.keySet());
        assertTrue(served.values().stream().allMatch(count -> count >= 2 && count <= 5));
        assertRefreshSpacing(ticks);
    }

    @Test
    void unchangedIndependentFailuresEachStopAfterFiveAndExplicitEditsCanRestartOne() {
        Fixture fixture = new Fixture();
        CubePos other = CubePos.of(POSITION.getX() + 8, POSITION.getY(), POSITION.getZ());
        addScoped(fixture, POSITION);
        addScoped(fixture, other);
        fixture.state.invalidateLocalAt(POSITION);
        fixture.state.invalidateLocalAt(other);
        Map<CubePos, Integer> counts = new HashMap<>();
        List<Integer> ticks = new ArrayList<>();
        for (int tick = 1; tick <= 1000; tick++) {
            var request = fixture.state.pollRefresh();
            if (request != null) {
                ticks.add(tick);
                counts.merge(request.pos(), 1, Integer::sum);
            }
        }
        assertEquals(Map.of(POSITION, 5, other, 5), counts);
        assertRefreshSpacing(ticks);
        for (int tick = 0; tick < 200; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
        fixture.state.invalidateLocalAt(POSITION);
        assertEquals(POSITION, fixture.state.pollRefresh().pos());
        for (int tick = 1; tick < 20; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
    }

    @Test
    void successOrUnloadOfOneAnchorPreservesAnotherIndependentColumn() {
        for (boolean unload : new boolean[] { false, true }) {
            Fixture fixture = new Fixture();
            LevelCube first = addScoped(fixture, POSITION);
            CubePos other = CubePos.of(POSITION.getX() + 8, POSITION.getY(), POSITION.getZ());
            addScoped(fixture, other);
            fixture.state.invalidateLocalAt(POSITION);
            fixture.state.invalidateLocalAt(other);
            if (unload) {
                fixture.cubes.remove(POSITION);
                fixture.state.remove(POSITION);
            } else {
                assertTrue(fixture.state.applyScoped(first, first.cc_getLightRevision(), 8, exact(POSITION), fixture.state.generation()));
            }
            int count = 0;
            for (int tick = 0; tick < 1000; tick++) {
                var request = fixture.state.pollRefresh();
                if (request != null) {
                    assertEquals(other, request.pos());
                    count++;
                }
            }
            assertEquals(5, count);
        }
    }

    @Test
    void resolvingCenterAndLeftDoesNotLoseTheOppositeRevokedColumn() {
        Fixture fixture = new Fixture();
        CubePos left = CubePos.of(POSITION.getX() - 1, POSITION.getY(), POSITION.getZ());
        LevelCube center = addScoped(fixture, POSITION);
        LevelCube leftCube = addScoped(fixture, left);
        addScoped(fixture, NEIGHBOR);
        fixture.state.invalidateLocalAt(POSITION);
        assertTrue(fixture.state.applyScoped(center, center.cc_getLightRevision(), 8, exact(POSITION), fixture.state.generation()));
        assertTrue(fixture.state.applyScoped(leftCube, leftCube.cc_getLightRevision(), 8, exact(left), fixture.state.generation()));
        for (int tick = 1; tick < 20; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
        assertEquals(NEIGHBOR, fixture.state.pollRefresh().pos(), "The two outer columns are outside one another's refresh radius");
    }

    @Test
    void differentHeightsInOneColumnRetargetWithoutResettingTheRetryBudget() {
        Fixture fixture = new Fixture();
        CubePos high = CubePos.of(POSITION.getX(), POSITION.getY() + 10, POSITION.getZ());
        addScoped(fixture, POSITION);
        addScoped(fixture, high);
        List<Integer> ticks = new ArrayList<>();
        for (int tick = 1; tick <= 500; tick++) {
            CubePos edited = tick % 2 == 0 ? high : POSITION;
            fixture.state.invalidateLocalAt(edited);
            var request = fixture.state.pollRefresh();
            if (request != null) {
                ticks.add(tick);
                assertEquals(edited, request.pos());
            }
        }
        assertEquals(List.of(20, 60, 140, 300, 500), ticks);
        for (int tick = 0; tick < 200; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
    }

    @Test
    void saturatedQueueKeepsActiveColumnsAndReclaimsOnlyAnExhaustedSlotForANewEdit() {
        Fixture fixture = new Fixture();
        List<CubePos> positions = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            CubePos pos = CubePos.of(POSITION.getX() + index * 4, POSITION.getY(), POSITION.getZ());
            positions.add(pos);
            addScoped(fixture, pos);
        }
        positions.forEach(fixture.state::invalidateLocalAt);
        Map<CubePos, Integer> counts = new HashMap<>();
        List<Integer> ticks = new ArrayList<>();
        for (int tick = 1; tick <= 7000; tick++) {
            var request = fixture.state.pollRefresh();
            if (request != null) {
                ticks.add(tick);
                counts.merge(request.pos(), 1, Integer::sum);
            }
        }
        assertEquals(Set.copyOf(positions.subList(0, 64)), counts.keySet(), "A 65th new region cannot displace active retries");
        assertTrue(counts.values().stream().allMatch(count -> count == 5));
        assertRefreshSpacing(ticks);
        CubePos extra = positions.getLast();
        fixture.state.invalidateLocalAt(extra);
        for (int tick = 1; tick < 20; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
        assertEquals(extra, fixture.state.pollRefresh().pos(), "A new explicit edit can reclaim an exhausted slot");
    }

    @Test
    void cacheInvalidationPreservesExhaustedResidentRetriesAndRejectsOldGenerationCallbacks() {
        Fixture fixture = new Fixture();
        LevelCube cube = addScoped(fixture, POSITION);
        long generation = fixture.state.generation();
        fixture.state.invalidateLocalAt(POSITION);
        int count = 0;
        for (int tick = 0; tick < 1000; tick++) {
            if (fixture.state.pollRefresh() != null) {
                count++;
            }
        }
        assertEquals(5, count);
        fixture.state.invalidateCache();
        for (int tick = 0; tick < 1000; tick++) {
            assertNull(fixture.state.pollRefresh(), "Cache invalidation cannot replenish a resident anchor's exhausted budget");
        }
        assertFalse(fixture.state.applyScoped(cube, cube.cc_getLightRevision(), 8, exact(POSITION), generation));
        assertTrue(fixture.state.applyScoped(cube, cube.cc_getLightRevision(), 8, exact(POSITION), fixture.state.generation()));
    }

    @Test
    void independentAnchorAndSuccessfulRecoveryDoNotInheritAnotherColumnsLongBackoff() {
        Fixture fixture = new Fixture();
        LevelCube first = fixture.add(POSITION);
        CubePos other = CubePos.of(POSITION.getX() + 8, POSITION.getY(), POSITION.getZ());
        LevelCube second = fixture.add(other);
        var state = fixture.state;
        state.applyScoped(first, first.cc_getLightRevision(), 7, exact(POSITION), state.generation());
        state.applyScoped(second, second.cc_getLightRevision(), 7, exact(other), state.generation());
        state.invalidateLocalAt(POSITION);
        for (int tick = 1; tick <= 140; tick++) {
            state.pollRefresh();
        }
        state.invalidateLocalAt(other);
        for (int tick = 1; tick < 20; tick++) {
            assertNull(state.pollRefresh());
        }
        assertEquals(new ClientCubeLightState.RefreshRequest(other, 7), state.pollRefresh());
        assertTrue(state.applyScoped(second, second.cc_getLightRevision(), 8, exact(other), state.generation()));
        for (int tick = 1; tick < 20; tick++) {
            assertNull(state.pollRefresh());
        }
        assertEquals(new ClientCubeLightState.RefreshRequest(POSITION, 8), state.pollRefresh());
    }

    @Test
    void unchangedFailureStopsAfterFiveRequestsButNewEditsCanRestartRecoveryWithinTheRateLimit() {
        Fixture fixture = new Fixture();
        LevelCube first = fixture.add(POSITION);
        CubePos other = CubePos.of(POSITION.getX() + 8, POSITION.getY(), POSITION.getZ());
        LevelCube second = fixture.add(other);
        var state = fixture.state;
        state.applyScoped(first, first.cc_getLightRevision(), 7, exact(POSITION), state.generation());
        state.applyScoped(second, second.cc_getLightRevision(), 7, exact(other), state.generation());
        List<Integer> requestTicks = new ArrayList<>();
        for (int tick = 1; tick <= 500; tick++) {
            state.invalidateLocalAt(POSITION);
            if (state.pollRefresh() != null) {
                requestTicks.add(tick);
            }
        }
        assertEquals(List.of(20, 60, 140, 300, 500), requestTicks);
        state.invalidateLocalAt(other);
        for (int tick = 1; tick < 20; tick++) {
            assertNull(state.pollRefresh());
        }
        assertEquals(new ClientCubeLightState.RefreshRequest(other, 7), state.pollRefresh());
        int remainingRequests = 0;
        for (int tick = 1; tick <= 600; tick++) {
            if (state.pollRefresh() != null) {
                remainingRequests++;
            }
        }
        assertEquals(4, remainingRequests);
        for (int tick = 0; tick < 200; tick++) {
            assertNull(state.pollRefresh(), "An unchanged failed anchor must not restart its five-attempt budget");
        }
        state.invalidateLocalAt(other);
        assertEquals(new ClientCubeLightState.RefreshRequest(other, 7), state.pollRefresh(), "A later explicit edit can restart recovery");
        for (int tick = 1; tick < 20; tick++) {
            assertNull(state.pollRefresh());
        }
    }

    @Test
    void editedCubeRelightingDoesNotCancelRecoveryOfConservativelyRevokedDistantLight() {
        Fixture fixture = new Fixture();
        LevelCube edited = fixture.add(POSITION);
        CubePos deep = CubePos.of(POSITION.getX(), POSITION.getY() - 10, POSITION.getZ());
        LevelCube distant = fixture.add(deep);
        var state = fixture.state;
        state.applyScoped(edited, edited.cc_getLightRevision(), 7, exact(POSITION), state.generation());
        state.applyScoped(distant, distant.cc_getLightRevision(), 7, exact(deep), state.generation());
        state.invalidateLocalAt(POSITION);
        assertTrue(state.applyScoped(edited, edited.cc_getLightRevision(), 8, exact(POSITION), state.generation()));
        for (int tick = 1; tick < 20; tick++) {
            assertNull(state.pollRefresh());
        }
        assertEquals(new ClientCubeLightState.RefreshRequest(deep, 8), state.pollRefresh());
        assertTrue(state.applyScoped(distant, distant.cc_getLightRevision(), 9, exact(deep), state.generation()));
        for (int tick = 0; tick < 200; tick++) {
            assertNull(state.pollRefresh());
        }
    }

    @Test
    void relightingRetainsDisplayButRejectsStalePublicationsAndStillAcceptsActualDarkness() throws Exception {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        var state = fixture.state;
        CubeLightView lit = CubeLightView.fromExact(POSITION, TestCubeLightView.filled(7), TestCubeLightView.filled(15));
        assertNull(state.displayView(POSITION), "An initial cube has no invented light");
        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 7, lit, state.generation()));
        cube.setBlockState(POSITION.asBlockPos(), Blocks.STONE.defaultBlockState());
        state.invalidateLocalAt(POSITION);
        state.invalidateScoped(POSITION, 8);
        for (int tick = 0; tick < 40; tick++) {
            assertNull(state.view(POSITION));
            assertSame(lit, state.displayView(POSITION));
            state.pollRefresh();
        }
        assertSame(lit, CompletableFuture.supplyAsync(() -> state.displayView(POSITION)).get(5, TimeUnit.SECONDS));
        assertFalse(state.applyScoped(cube, cube.cc_getLightRevision(), 7, exact(POSITION), state.generation()));
        state.forget(POSITION); // Full block replacement happens before its queued lighting callback.
        assertFalse(state.applyScoped(cube, cube.cc_getLightRevision(), 8, null, state.generation()));
        assertNull(state.view(POSITION));
        assertSame(lit, state.displayView(POSITION));
        CubeLightView dark = exact(POSITION);
        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 9, dark, state.generation()));
        assertSame(dark, state.view(POSITION));
        assertSame(dark, state.displayView(POSITION), "A real zero-light result must replace the old bright field");
    }

    @Test
    void displayCannotLeakAcrossEvictionReplacementOrAnotherClientWorld() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        var state = fixture.state;
        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 3, exact(POSITION), state.generation()));
        fixture.cubes.remove(POSITION);
        state.remove(POSITION);
        assertNull(state.displayView(POSITION));
        LevelCube replacement = fixture.add(POSITION);
        assertNull(state.displayView(POSITION));
        assertFalse(state.applyScoped(cube, cube.cc_getLightRevision(), 3, exact(POSITION), state.generation()));
        assertTrue(state.applyScoped(replacement, replacement.cc_getLightRevision(), 4, exact(POSITION), state.generation()));
        fixture.add(POSITION);
        assertNull(state.displayView(POSITION), "Even a missed eviction callback must not expose another cube instance's light");
        assertNull(new ClientCubeLightState(fixture.cubes::get, pos -> {}).displayView(POSITION));
    }

    @Test
    void readinessRevocationDoesNotRebuildUnchangedDisplayedGeometry() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        CubeLightView view = exact(POSITION);
        assertTrue(fixture.state.applyScoped(cube, cube.cc_getLightRevision(), 1, view, fixture.state.generation()));
        fixture.changed.clear();
        fixture.state.invalidateLocalAt(POSITION);
        fixture.state.invalidateScoped(POSITION, 2);
        fixture.state.forget(POSITION);
        assertNull(fixture.state.view(POSITION));
        assertSame(view, fixture.state.displayView(POSITION));
        assertTrue(fixture.changed.isEmpty());
        assertTrue(fixture.state.applyScoped(cube, cube.cc_getLightRevision(), 3, view, fixture.state.generation()));
        assertEquals(List.of(POSITION), fixture.changed);
    }

    @Test
    void legacyInvalidationAlsoRetainsDisplayUntilCubeRemoval() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        CubeLightView light = exact(POSITION);
        assertTrue(fixture.apply(cube, 3, light));
        fixture.state.invalidate(4);
        fixture.state.invalidateLocal();
        fixture.state.invalidateCache();
        assertNull(fixture.state.view(POSITION));
        assertSame(light, fixture.state.displayView(POSITION));
        fixture.state.remove(POSITION);
        assertNull(fixture.state.displayView(POSITION));
    }

    @Test
    void scopedPredictionRecoveryUsesEditedColumnRatherThanAnEdgeNeighbor() {
        Fixture fixture = new Fixture();
        CubePos edited = CubePos.of(0, 1000000, 0);
        fixture.add(edited);
        CubePos left = CubePos.of(-1, 1000000, 0);
        LevelCube cube = fixture.add(left);
        var state = fixture.state;
        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 3, exact(left), state.generation()));
        state.invalidateLocalAt(edited);
        for (int tick = 1; tick < 20; tick++) {
            assertNull(state.pollRefresh());
        }
        assertEquals(new ClientCubeLightState.RefreshRequest(edited, 3), state.pollRefresh());
    }

    @Test
    void queuedScopedLightIsRevokedByRemoteRoofEditAndEvictionDoesNotClearOthers() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        CubePos far = CubePos.of(20, 1000000, 30);
        LevelCube distant = fixture.add(far);
        var state = fixture.state;
        state.expectScoped(cube, 7);
        state.expectScoped(distant, 8);
        long generation = state.generation();
        state.invalidateLocalAt(CubePos.of(POSITION.getX(), 1000000, POSITION.getZ()));
        assertFalse(state.applyScoped(cube, cube.cc_getLightRevision(), 7, exact(POSITION), generation));
        assertTrue(state.applyScoped(distant, distant.cc_getLightRevision(), 8, exact(far), generation));
        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 9, exact(POSITION), generation));
        fixture.cubes.remove(POSITION);
        state.remove(POSITION);
        assertNotNull(state.view(far));
        assertFalse(state.applyScoped(cube, cube.cc_getLightRevision(), 9, exact(POSITION), generation));
    }

    @Test
    void scopedEpochsKeepUnrelatedCubesAndRejectLateCallbacksAfterRoofChange() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        CubePos far = CubePos.of(10, 1000000, 10);
        LevelCube distant = fixture.add(far);
        var state = fixture.state;
        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 2, exact(POSITION), state.generation()));
        assertTrue(state.applyScoped(distant, distant.cc_getLightRevision(), 9, exact(far), state.generation()));
        assertNotNull(state.view(POSITION));
        state.invalidateScoped(POSITION, 10);
        assertNull(state.view(POSITION));
        assertNotNull(state.view(far));
        assertFalse(state.applyScoped(cube, cube.cc_getLightRevision(), 2, exact(POSITION), state.generation()));
        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 10, exact(POSITION), state.generation()));
        state.invalidateLocalAt(POSITION);
        assertFalse(state.applyScoped(cube, cube.cc_getLightRevision(), 10, exact(POSITION), state.generation()));
        assertNotNull(state.view(far));
        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 11, exact(POSITION), state.generation()));
    }

    @Test
    void sameEpochSnapshotsKeepBothCubesAndAreReadableByAWorker() throws Exception {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        LevelCube neighbor = fixture.add(NEIGHBOR);
        fixture.state.invalidate(4);
        CubeLightView light = exact(POSITION);
        assertTrue(fixture.apply(cube, 4, light));
        assertTrue(fixture.apply(neighbor, 4, exact(NEIGHBOR)));
        assertSame(light, CompletableFuture.supplyAsync(() -> fixture.state.view(POSITION)).get(5, TimeUnit.SECONDS));
        assertTrue(fixture.state.view(NEIGHBOR).isReady());
        assertFalse(cube.isLightCorrect());
    }

    @Test
    void oldAndDuplicateRevocationsCannotRemoveANewerPublication() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        fixture.state.invalidate(4);
        assertTrue(fixture.apply(cube, 4, exact(POSITION)));
        long generation = fixture.state.generation();
        fixture.state.invalidate(3);
        fixture.state.invalidate(4);
        assertEquals(generation, fixture.state.generation());
        assertTrue(fixture.state.view(POSITION).isReady());
        fixture.state.invalidate(5);
        assertNull(fixture.state.view(POSITION));
        assertFalse(fixture.apply(cube, 4, exact(POSITION)));
        assertTrue(fixture.apply(cube, 5, exact(POSITION)));
    }

    @Test
    void queuedCallbacksCannotCrossRevocationOrLocalBlockChanges() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        long generation = fixture.state.generation();
        fixture.state.invalidate(4);
        assertFalse(fixture.state.apply(cube, cube.cc_getLightRevision(), 4, exact(POSITION), generation));
        assertTrue(fixture.apply(cube, 4, exact(POSITION)));
        fixture.state.invalidateLocal();
        assertNull(fixture.state.view(POSITION));
        assertFalse(fixture.apply(cube, 4, exact(POSITION)));
        assertTrue(fixture.apply(cube, 5, exact(POSITION)));
    }

    @Test
    void sameObjectReplacementAndDifferentInstanceRejectQueuedLight() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        long revision = cube.cc_getLightRevision();
        cube.cc_invalidateHeightmaps();
        assertFalse(fixture.state.apply(cube, revision, 2, exact(POSITION), fixture.state.generation()));
        assertTrue(fixture.apply(cube, 2, exact(POSITION)));
        fixture.add(POSITION);
        assertNull(fixture.state.view(POSITION));
        assertFalse(fixture.apply(cube, 3, exact(POSITION)));
    }

    @Test
    void unloadingOrMutatingAnInstalledCubeNeverReturnsItsOldView() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        assertTrue(fixture.apply(cube, 1, exact(POSITION)));
        cube.setBlockState(POSITION.asBlockPos(16, 16, 16), Blocks.STONE.defaultBlockState());
        assertNull(fixture.state.view(POSITION));
        assertTrue(fixture.apply(cube, 2, exact(POSITION)));
        fixture.cubes.remove(POSITION);
        assertNull(fixture.state.view(POSITION));
        assertFalse(fixture.apply(cube, 2, exact(POSITION)));
    }

    @Test
    void fullReplacementClearsOwnLightWithoutDroppingOtherSameBatchCubes() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        LevelCube neighbor = fixture.add(NEIGHBOR);
        assertTrue(fixture.apply(cube, 2, exact(POSITION)));
        assertTrue(fixture.apply(neighbor, 2, exact(NEIGHBOR)));
        fixture.state.forget(POSITION);
        cube.cc_invalidateHeightmaps();
        assertNull(fixture.state.view(POSITION));
        assertTrue(fixture.state.view(NEIGHBOR).isReady());
        assertTrue(fixture.apply(cube, 2, exact(POSITION)));
        assertFalse(fixture.apply(cube, 2, null));
        assertNull(fixture.state.view(POSITION));
        fixture.state.invalidateLocal();
        assertNull(fixture.state.view(NEIGHBOR));
        assertTrue(fixture.changed.containsAll(List.of(POSITION, NEIGHBOR)));
    }

    @Test
    void higherEpochReplacesAllOldEntriesAndMalformedInputIsRejected() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        LevelCube neighbor = fixture.add(NEIGHBOR);
        assertTrue(fixture.apply(cube, 2, exact(POSITION)));
        assertTrue(fixture.apply(neighbor, 3, exact(NEIGHBOR)));
        assertNull(fixture.state.view(POSITION));
        assertThrows(IllegalArgumentException.class, () -> fixture.state.invalidate(-1));
        assertThrows(IllegalArgumentException.class, () -> fixture.apply(cube, -1, exact(POSITION)));
        assertThrows(IllegalArgumentException.class, () -> fixture.apply(cube, 3, exact(NEIGHBOR)));
    }

    @Test
    void refreshBackoffIsBoundedAndRepeatedInvalidationsDoNotRestartIt() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        assertTrue(fixture.apply(cube, 4, exact(POSITION)));
        fixture.state.invalidateLocal();
        List<Integer> requestedAt = new ArrayList<>();
        for (int tick = 1; tick <= 1000; tick++) {
            fixture.state.invalidateLocal();
            ClientCubeLightState.RefreshRequest request = fixture.state.pollRefresh();
            if (request != null) {
                requestedAt.add(tick);
                assertEquals(POSITION, request.pos());
                assertEquals(4, request.observedEpoch());
            }
        }
        assertEquals(List.of(20, 60, 140, 300, 500), requestedAt);
        assertNull(fixture.state.view(POSITION));
    }

    @Test
    void serverRevocationAndUnavailableSnapshotsPreserveRecoveryUntilCompleteAnchorArrives() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        assertTrue(fixture.apply(cube, 4, exact(POSITION)));
        fixture.state.invalidateLocal();
        fixture.state.invalidate(5);
        assertFalse(fixture.apply(cube, 5, null));
        for (int tick = 1; tick < 20; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
        assertEquals(new ClientCubeLightState.RefreshRequest(POSITION, 5), fixture.state.pollRefresh());
        assertTrue(fixture.apply(cube, 5, exact(POSITION)));
        for (int tick = 0; tick < 1000; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
    }

    @Test
    void evictionRecoversAStillCachedAnchorAndSameEpochSnapshotCancelsRetries() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        LevelCube neighbor = fixture.add(NEIGHBOR);
        assertTrue(fixture.apply(cube, 4, exact(POSITION)));
        assertTrue(fixture.apply(neighbor, 4, exact(NEIGHBOR)));
        fixture.cubes.remove(POSITION);
        fixture.state.invalidateCache();
        for (int tick = 1; tick < 20; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
        assertEquals(new ClientCubeLightState.RefreshRequest(NEIGHBOR, 4), fixture.state.pollRefresh());
        assertTrue(fixture.apply(neighbor, 4, exact(NEIGHBOR)));
        for (int tick = 0; tick < 1000; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
    }

    @Test
    void absentPublicationAndLostAnchorCannotGenerateRequests() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        fixture.state.invalidateLocal();
        for (int tick = 0; tick < 1000; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
        assertTrue(fixture.apply(cube, 4, exact(POSITION)));
        fixture.state.invalidateLocal();
        fixture.cubes.remove(POSITION);
        fixture.state.invalidateCache();
        for (int tick = 0; tick < 1000; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
        LevelCube replacement = fixture.add(POSITION);
        assertTrue(fixture.apply(replacement, 5, exact(POSITION)));
        fixture.state.invalidateLocal();
        for (int tick = 1; tick < 20; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
        assertEquals(new ClientCubeLightState.RefreshRequest(POSITION, 5), fixture.state.pollRefresh());
    }

    @Test
    void firstQueuedPublicationRetainsRecoveryAfterLocalChangeWithoutExposingLight() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        fixture.state.invalidate(4);
        long generation = fixture.state.generation();
        fixture.state.expect(cube, 4);
        assertEquals(generation, fixture.state.generation());
        assertNull(fixture.state.view(POSITION));
        for (int tick = 1; tick < 20; tick++) {
            fixture.state.expect(cube, 4);
            assertNull(fixture.state.pollRefresh());
        }
        fixture.state.invalidateLocal();
        assertFalse(fixture.state.apply(cube, cube.cc_getLightRevision(), 4, exact(POSITION), generation));
        assertEquals(new ClientCubeLightState.RefreshRequest(POSITION, 4), fixture.state.pollRefresh());
        fixture.state.invalidate(5);
        assertTrue(fixture.apply(cube, 5, exact(POSITION)));
        for (int tick = 0; tick < 1000; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
    }

    @Test
    void staleEpochAndReplacedCubeCannotStartRefreshExpectation() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        fixture.state.invalidate(4);
        fixture.state.expect(cube, 3);
        fixture.add(POSITION);
        fixture.state.expect(cube, 4);
        for (int tick = 0; tick < 1000; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
        assertThrows(IllegalArgumentException.class, () -> fixture.state.expect(cube, -1));
    }

    private static LevelCube addScoped(Fixture fixture, CubePos pos) {
        LevelCube cube = fixture.add(pos);
        assertTrue(fixture.state.applyScoped(cube, cube.cc_getLightRevision(), 7, exact(pos), fixture.state.generation()));
        return cube;
    }

    private static void assertRefreshSpacing(List<Integer> ticks) {
        assertFalse(ticks.isEmpty());
        for (int index = 1; index < ticks.size(); index++) {
            assertTrue(ticks.get(index) - ticks.get(index - 1) >= 20, "All anchors share one request per 20 ticks");
        }
    }

    private static CubeLightView exact(CubePos pos) {
        return CubeLightView.fromExact(pos, new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE],
                new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE]);
    }

    private static CubeLightView openSky(CubePos pos) {
        return CubeLightView.fromExact(pos, new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE], TestCubeLightView.filled(15));
    }

    private static CubeLightView solved(Fixture fixture, CubePos center, int roofEdge) {
        Map<CubePos, CubeLightBlockSnapshot> blocks = new HashMap<>();
        for (CubePos pos : CubeWorldLightManager.halo(center).positions()) {
            CubeAccess cube = fixture.cubes.get(pos);
            blocks.put(pos, cube == null ? CubeLightBlockSnapshot.uniform(pos, Blocks.AIR.defaultBlockState()) : CubeLightBlockSnapshot.capture(cube));
        }
        return CubeWorldLightSolver.solve(center, blocks, (x, z) -> roofEdge);
    }

    private static void assertSameLight(CubeLightView expected, CubeLightView actual) {
        for (LightLayer layer : LightLayer.values()) {
            for (int section = 0; section < CubicConstants.SECTION_COUNT; section++) {
                assertArrayEquals(expected.packedLayer(layer, section).orElseThrow(), actual.packedLayer(layer, section).orElseThrow());
            }
        }
    }

    private static final class Fixture {
        private final Level level = mockLevelWithPalettes(Level.class);
        private final Map<CubePos, CubeAccess> cubes = new HashMap<>();
        private final List<CubePos> changed = new ArrayList<>();
        private final ClientCubeLightState state = new ClientCubeLightState(this.cubes::get, this.changed::add);

        private LevelCube add(CubePos pos) {
            LevelCube cube = new LevelCube(this.level, pos);
            this.cubes.put(pos, cube);
            return cube;
        }

        private boolean apply(LevelCube cube, long epoch, CubeLightView view) {
            return this.state.apply(cube, cube.cc_getLightRevision(), epoch, view, this.state.generation());
        }
    }
}
