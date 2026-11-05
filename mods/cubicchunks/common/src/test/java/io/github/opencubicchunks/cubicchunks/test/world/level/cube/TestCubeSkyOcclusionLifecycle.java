package io.github.opencubicchunks.cubicchunks.test.world.level.cube;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.util.OptionalInt;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubePacketData;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ImposterProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.status.CubeStatusTasks;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.SerializableCubeData;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeSkyOcclusion;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.Test;

public class TestCubeSkyOcclusionLifecycle extends BaseTest {
    private static final CubePos POSITION = CubePos.of(-5, -9000, 7);

    @Test
    void protoEditsRebuildInternalEdgesAndBothBoundaryPlanesWithoutMutatingOlderSnapshots() {
        ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        ProtoCube cube = proto(level);
        BlockPos bottom = POSITION.asBlockPos(31, 0, 17);
        BlockPos middle = POSITION.asBlockPos(31, 16, 17);
        BlockPos top = POSITION.asBlockPos(31, 31, 17);
        CubeSkyOcclusion empty = cube.cc_snapshotSkyOcclusion();
        assertSame(empty, cube.cc_snapshotSkyOcclusion());
        assertTrue(empty.highestInternalEdgeY(31, 17).isEmpty());

        cube.setBlockState(bottom, Blocks.WATER.defaultBlockState());
        cube.setBlockState(middle, Blocks.STONE.defaultBlockState());
        cube.setBlockState(top, Blocks.OAK_LEAVES.defaultBlockState());
        CubeSkyOcclusion filled = cube.cc_snapshotSkyOcclusion();
        assertNotSame(empty, filled);
        assertEquals(OptionalInt.of(middle.getY() + 1), filled.highestInternalEdgeY(31, 17));
        assertEquals(Blocks.WATER.defaultBlockState(), filled.bottomState(31, 17));
        assertEquals(Blocks.OAK_LEAVES.defaultBlockState(), filled.topState(31, 17));
        assertTrue(empty.highestInternalEdgeY(31, 17).isEmpty());
        assertEquals(Blocks.AIR.defaultBlockState(), empty.bottomState(31, 17));
        assertEquals(Blocks.AIR.defaultBlockState(), empty.topState(31, 17));

        cube.setBlockState(middle, Blocks.AIR.defaultBlockState());
        assertEquals(OptionalInt.of(bottom.getY() + 1), cube.cc_snapshotSkyOcclusion().highestInternalEdgeY(31, 17));
        cube.setBlockState(bottom, Blocks.AIR.defaultBlockState());
        cube.setBlockState(top, Blocks.AIR.defaultBlockState());
        CubeSkyOcclusion cleared = cube.cc_snapshotSkyOcclusion();
        assertTrue(cleared.highestInternalEdgeY(31, 17).isEmpty());
        assertEquals(Blocks.AIR.defaultBlockState(), cleared.bottomState(31, 17));
        assertEquals(Blocks.AIR.defaultBlockState(), cleared.topState(31, 17));
        assertEquals(OptionalInt.of(middle.getY() + 1), filled.highestInternalEdgeY(31, 17));
        cube.setBlockState(top, Blocks.AIR.defaultBlockState());
        assertSame(cleared, cube.cc_snapshotSkyOcclusion(), "An unchanged block does not invalidate the snapshot");
        assertFalse(cube.isLightCorrect());
    }

    @Test
    void initializeStageCapturesLocalOcclusionWithoutClaimingCompleteLighting() {
        ProtoCube cube = spy(proto(mockLevelWithPalettes(ServerLevel.class)));
        cube.setBlockState(POSITION.asBlockPos(3, 20, 5), Blocks.STONE.defaultBlockState());
        cube.tryMarkSaved();

        assertSame(cube, CubeStatusTasks.initializeLight(null, null, null, cube).join());

        verify(cube).initializeLightSources();
        verify(cube).cc_snapshotSkyOcclusion();
        assertFalse(cube.isLightCorrect());
        assertFalse(cube.isUnsaved(), "Capturing derived occlusion does not change persisted state");
        assertEquals(OptionalInt.of(POSITION.minCubeY() + 21), cube.cc_snapshotSkyOcclusion().highestInternalEdgeY(3, 5));
        assertThrows(UnsupportedOperationException.class, cube::getSkyLightSources);
    }

    @Test
    void promotionCreatesItsOwnDerivedCaches() throws ReflectiveOperationException {
        ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        ProtoCube proto = proto(level);
        BlockPos block = POSITION.asBlockPos(16, 15, 31);
        proto.setBlockState(block, Blocks.STONE.defaultBlockState());
        CubeSkyOcclusion protoSnapshot = proto.cc_snapshotSkyOcclusion();

        LevelCube full = new LevelCube(level, proto, null);
        CubeSkyOcclusion fullSnapshot = full.cc_snapshotSkyOcclusion();
        assertNotSame(protoSnapshot, fullSnapshot);
        assertSameContents(protoSnapshot, fullSnapshot);
        Field vanillaSources = CubeAccess.class.getDeclaredField("skyLightSources");
        vanillaSources.setAccessible(true);
        assertNotSame(vanillaSources.get(proto), vanillaSources.get(full));

        full.setBlockState(block, Blocks.AIR.defaultBlockState());
        assertTrue(full.cc_snapshotSkyOcclusion().highestInternalEdgeY(16, 31).isEmpty());
        assertEquals(OptionalInt.of(block.getY() + 1), protoSnapshot.highestInternalEdgeY(16, 31));
        assertEquals(OptionalInt.of(block.getY() + 1), fullSnapshot.highestInternalEdgeY(16, 31));
        assertFalse(full.isLightCorrect());
    }

    @Test
    void imposterReadsAndInvalidatesOnlyTheWrappedCache() {
        LevelCube full = new LevelCube(mockLevelWithPalettes(Level.class), POSITION);
        ImposterProtoCube readOnly = new ImposterProtoCube(full, false);
        ImposterProtoCube writable = new ImposterProtoCube(full, true);
        BlockPos block = POSITION.asBlockPos(18, 19, 20);
        full.setBlockState(block, Blocks.STONE.defaultBlockState());
        CubeSkyOcclusion original = full.cc_snapshotSkyOcclusion();
        assertSame(original, readOnly.cc_snapshotSkyOcclusion());
        assertSame(original, writable.cc_snapshotSkyOcclusion());

        readOnly.setBlockState(block, Blocks.AIR.defaultBlockState());
        assertSame(original, full.cc_snapshotSkyOcclusion());
        writable.setBlockState(block, Blocks.AIR.defaultBlockState());
        CubeSkyOcclusion cleared = full.cc_snapshotSkyOcclusion();
        assertNotSame(original, cleared);
        assertSame(cleared, readOnly.cc_snapshotSkyOcclusion());
        assertTrue(cleared.highestInternalEdgeY(18, 20).isEmpty());

        readOnly.cc_invalidateHeightmaps();
        full.getSection(Coords.blockToIndex(block)).setBlockState(2, 3, 4, Blocks.STONE.defaultBlockState());
        readOnly.initializeLightSources();
        CubeSkyOcclusion replaced = readOnly.cc_snapshotSkyOcclusion();
        assertNotSame(cleared, replaced);
        assertSame(replaced, full.cc_snapshotSkyOcclusion());
        assertEquals(OptionalInt.of(block.getY() + 1), replaced.highestInternalEdgeY(18, 20));
        readOnly.cc_invalidateSkyOcclusion();
        assertNotSame(replaced, full.cc_snapshotSkyOcclusion());
        assertThrows(UnsupportedOperationException.class, readOnly::getSkyLightSources);
        assertFalse(full.isLightCorrect());
    }

    @Test
    void packetSectionReplacementInvalidatesPreviouslyCapturedSkyOcclusion() {
        Level level = mockLevelWithPalettes(Level.class);
        LevelCube source = new LevelCube(level, POSITION);
        source.setBlockState(POSITION.asBlockPos(31, 0, 31), Blocks.WATER.defaultBlockState());
        source.setBlockState(POSITION.asBlockPos(0, 31, 0), Blocks.STONE.defaultBlockState());
        CubeSkyOcclusion expected = source.cc_snapshotSkyOcclusion();
        CCClientboundLevelCubePacketData packet = new CCClientboundLevelCubePacketData(source);
        source.setBlockState(POSITION.asBlockPos(31, 0, 31), Blocks.AIR.defaultBlockState());
        LevelCube destination = new LevelCube(level, POSITION);
        destination.setBlockState(POSITION.asBlockPos(17, 18, 19), Blocks.STONE.defaultBlockState());
        CubeSkyOcclusion old = destination.cc_snapshotSkyOcclusion();
        FriendlyByteBuf sections = packet.getReadBuffer();
        try {
            destination.replaceWithPacketData(sections, packet.getHeightmaps(), ignored -> {});
        } finally {
            sections.release();
        }

        CubeSkyOcclusion received = destination.cc_snapshotSkyOcclusion();
        assertNotSame(old, received);
        assertSameContents(expected, received);
        assertEquals(OptionalInt.of(POSITION.minCubeY() + 19), old.highestInternalEdgeY(17, 19));
        assertFalse(destination.isLightCorrect());
    }

    @Test
    void bothDiskFormatsRebuildOcclusionFromDetachedSectionPalettes() {
        ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        ProtoCube source = proto(level);
        source.setBlockState(POSITION.asBlockPos(31, 0, 31), Blocks.WATER.defaultBlockState());
        source.setBlockState(POSITION.asBlockPos(0, 31, 0), Blocks.STONE.defaultBlockState());
        source.setBlockState(POSITION.asBlockPos(17, 15, 19), Blocks.STONE.defaultBlockState());
        CubeSkyOcclusion expected = source.cc_snapshotSkyOcclusion();
        CompoundTag encoded = SerializableCubeData.copyOf(level, source).write();
        source.setBlockState(POSITION.asBlockPos(17, 15, 19), Blocks.AIR.defaultBlockState());

        for (int format : new int[] { 1, 2 }) {
            CompoundTag tag = encoded.copy();
            tag.putInt("CubeFormat", format);
            if (format == 1) {
                tag.remove("CubeHeightmaps");
            } else {
                // A valid but stale heightmap must not substitute for actual light-dampening and shape checks.
                tag.put("CubeHeightmaps", SerializableCubeData.copyOf(level, proto(level)).write().getCompound("CubeHeightmaps").orElseThrow());
            }
            SerializableCubeData data = SerializableCubeData.parse(tag, POSITION);
            CubeSkyOcclusion rebuilt = data.skyOcclusion(level.palettedContainerFactory());
            assertNotSame(expected, rebuilt);
            assertSameContents(expected, rebuilt);
            assertEquals(tag, data.write(), "Rebuilding a derived summary does not change the saved format");
        }
    }

    @Test
    void persistedSkySummaryRejectsBrokenSectionPalettes() {
        ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        CompoundTag encoded = SerializableCubeData.copyOf(level, proto(level)).write();
        ListTag sections = encoded.getList("sections").orElseThrow();
        ((CompoundTag) sections.getFirst()).put("block_states", new CompoundTag());
        SerializableCubeData parsed = SerializableCubeData.parse(encoded, POSITION);

        assertThrows(IllegalArgumentException.class, () -> parsed.skyOcclusion(level.palettedContainerFactory()));
    }

    private static ProtoCube proto(ServerLevel level) {
        return new ProtoCube(POSITION, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
    }

    private static void assertSameContents(CubeSkyOcclusion expected, CubeSkyOcclusion actual) {
        assertEquals(expected.pos(), actual.pos());
        for (int x = 0; x < CubicConstants.DIAMETER_IN_BLOCKS; x++) {
            for (int z = 0; z < CubicConstants.DIAMETER_IN_BLOCKS; z++) {
                assertEquals(expected.highestInternalEdgeY(x, z), actual.highestInternalEdgeY(x, z));
                assertEquals(expected.bottomState(x, z), actual.bottomState(x, z));
                assertEquals(expected.topState(x, z), actual.topState(x, z));
            }
        }
    }
}
