package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Predicate;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.junit.jupiter.api.Test;

class TestDeepStructureLocator extends BaseTest {
    @Test
    void deepLocateReturnsActualYWithoutLoadingTerrainAndNativeQueriesFallThrough() {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        Misc.setupLevelHeight(level, -64, 384);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
        var terrain = mock(NativeBiomeTerrain.class);
        var layout = mock(DeepStructureLayout.class);
        when(((CubicServerLevel) level).cc_nativeBiomeTerrain()).thenReturn(terrain);
        when(terrain.deepStructures()).thenReturn(layout);
        var holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.STRONGHOLD);
        var start = new DeepStructureLayout.Start(CubePos.of(0, -1000000, 0), holder.value(), new CompoundTag(),
                new BoundingBox(0, -32000000, 0, 31, -31999969, 31), 7);
        when(layout.findNearestStart(any(), any(), anyInt(), any())).thenReturn(start);
        var query = HolderSet.direct(holder);
        var result = DeepStructureLocator.find(level, query, new BlockPos(16, -32000000, 16), 100, false);
        assertNotNull(result);
        assertEquals(start.bounds().getCenter(), result.getFirst());
        assertEquals(holder, result.getSecond());
        verify((CubicLevelReader) level, never()).cc_getCube(anyInt(), anyInt(), anyInt(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertNull(DeepStructureLocator.find(level, query, new BlockPos(16, -64, 16), 100, false));
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(false);
        assertNull(DeepStructureLocator.find(level, query, new BlockPos(16, -32000, 16), 100, false));
    }

    @Test
    void explorerMapsReserveThePersistentCubeStartWithoutMutatingCachedGraph() {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        Misc.setupLevelHeight(level, -64, 384);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
        var terrain = mock(NativeBiomeTerrain.class);
        var layout = mock(DeepStructureLayout.class);
        when(((CubicServerLevel) level).cc_nativeBiomeTerrain()).thenReturn(terrain);
        when(terrain.deepStructures()).thenReturn(layout);
        var holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.TRIAL_CHAMBERS);
        var start = new DeepStructureLayout.Start(CubePos.of(0, -1000, 0), holder.value(), new CompoundTag(),
                new BoundingBox(0, -32000, 0, 31, -31969, 31), 7);
        var owner = mock(CubeAccess.class);
        var stored = mock(StructureStart.class);
        when(((CubicLevelReader) level).cc_getCube(0, -1000, 0, ChunkStatus.STRUCTURE_STARTS, true)).thenReturn(owner);
        when(owner.getStartForStructure(holder.value())).thenReturn(stored);
        when(stored.isValid()).thenReturn(true);
        when(stored.canBeReferenced()).thenReturn(true);
        when(layout.findNearestStart(any(), any(), anyInt(), any())).thenAnswer(call -> {
            Predicate<DeepStructureLayout.Start> eligible = call.getArgument(3);
            return eligible.test(start) ? start : null;
        });
        var query = HolderSet.direct(holder);
        assertNotNull(DeepStructureLocator.find(level, query, new BlockPos(16, -32000, 16), 100, true));
        verify(stored).addReference();
        verify(owner).markUnsaved();
        assertEquals(new CompoundTag(), start.tag());
        when(stored.canBeReferenced()).thenReturn(false);
        assertNull(DeepStructureLocator.find(level, query, new BlockPos(16, -32000, 16), 100, true));
    }
}
