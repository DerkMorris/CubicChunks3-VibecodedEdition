package io.github.opencubicchunks.cubicchunks.test.network;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubePacketData;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.status.CubeStatusTasks;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeHeightmap;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

public class TestCubeHeightmapPackets extends BaseTest {
    private static final CubePos POSITION = CubePos.of(-5, -9, 7);

    @Test
    void terrainGenerationUpdatesPreviouslyQueriedMapsAwayFromTheOrigin() {
        ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        io.github.opencubicchunks.cubicchunks.testutils.Misc.setupLevelHeight(level, -64, 384);
        var terrain = new io.github.opencubicchunks.cubicchunks.world.level.levelgen.NativeBiomeTerrain(level, Runnable::run);
        org.mockito.Mockito.when(((io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel) level).cc_nativeBiomeTerrain())
                .thenReturn(terrain);
        ProtoCube cube = new ProtoCube(POSITION, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
        cube.cc_snapshotHeightmaps();
        var context = org.mockito.Mockito.mock(net.minecraft.world.level.chunk.status.WorldGenContext.class);
        org.mockito.Mockito.when(context.level()).thenReturn(level);
        CubeStatusTasks.buildTerrain(context, null, null, cube).join();
        assertTrue(cube.isUnsaved());
        for (Heightmap.Types type : Heightmap.Types.values()) {
            assertEquals(cube.getMaxY(), cube.getHeight(type, 0, 0));
            assertEquals(cube.getMaxY(), cube.getHeight(type, 31, 31));
        }
        assertSurfacesMatchBlocks(cube);
    }

    @Test
    void protoEditsAndPromotionKeepAllHeightmapTypesCurrent() {
        ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        ProtoCube proto = new ProtoCube(POSITION, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
        BlockPos top = POSITION.asBlockPos(16, 31, 31);
        BlockPos lower = POSITION.asBlockPos(16, 15, 31);
        assertEquals(POSITION.minCubeY() - 1, proto.getHeight(Heightmap.Types.WORLD_SURFACE, 16, 31));
        proto.tryMarkSaved();
        proto.setBlockState(lower, Blocks.STONE.defaultBlockState());
        proto.setBlockState(top, Blocks.OAK_LEAVES.defaultBlockState());
        assertTrue(proto.isUnsaved());
        assertSurfacesMatchBlocks(proto);
        LevelCube promoted = new LevelCube(level, proto, null);
        for (Heightmap.Types type : Heightmap.Types.values()) {
            assertNotSame(proto.cc_getOrCreateHeightmapUnprimed(type), promoted.cc_getOrCreateHeightmapUnprimed(type));
        }
        assertSurfacesMatchBlocks(promoted);
        promoted.setBlockState(top, Blocks.AIR.defaultBlockState());
        assertEquals(lower.getY(), promoted.getHeight(Heightmap.Types.WORLD_SURFACE, 16, 31));
        assertSurfacesMatchBlocks(promoted);
        promoted.tryMarkSaved();
        assertThrows(IllegalArgumentException.class, () -> promoted.setBlockState(top.above(), Blocks.STONE.defaultBlockState()));
        assertFalse(promoted.isUnsaved());
    }

    @Test
    void packetRoundTripInstallsDetachedCubeSizedHeightmaps() {
        Level level = mockLevelWithPalettes(Level.class);
        LevelCube source = new LevelCube(level, POSITION);
        populate(source);
        CCClientboundLevelCubePacketData packet = new CCClientboundLevelCubePacketData(source);
        Map<Heightmap.Types, long[]> expected = source.cc_snapshotHeightmaps();
        source.setBlockState(POSITION.asBlockPos(31, 31, 31), Blocks.GOLD_BLOCK.defaultBlockState());
        FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CCClientboundLevelCubePacketData.STREAM_CODEC.encode(wire, packet);
            CCClientboundLevelCubePacketData decoded = CCClientboundLevelCubePacketData.STREAM_CODEC.decode(wire);
            assertEquals(packet, decoded);
            assertEquals(packet.hashCode(), decoded.hashCode());
            assertEquals(0, wire.readableBytes());
            LevelCube destination = new LevelCube(level, POSITION);
            destination.setBlockState(POSITION.asBlockPos(1, 31, 1), Blocks.STONE.defaultBlockState());
            destination.cc_snapshotHeightmaps();
            FriendlyByteBuf sections = decoded.getReadBuffer();
            try {
                destination.replaceWithPacketData(sections, decoded.getHeightmaps(), ignored -> {});
            } finally {
                sections.release();
            }
            for (Heightmap.Types type : Heightmap.Types.values()) {
                assertArrayEquals(expected.get(type), destination.cc_snapshotHeightmaps().get(type));
            }
            decoded.getHeightmaps().get(Heightmap.Types.WORLD_SURFACE)[0] = -1;
            assertArrayEquals(expected.get(Heightmap.Types.WORLD_SURFACE), decoded.getHeightmaps().get(Heightmap.Types.WORLD_SURFACE));
            assertSurfacesMatchBlocks(destination);
        } finally {
            wire.release();
        }
    }

    @Test
    void sectionReplacementWithoutCachedMapsRebuildsHeightsAndRejectsBadMapsBeforeMutation() {
        Level level = mockLevelWithPalettes(Level.class);
        LevelCube destination = new LevelCube(level, POSITION);
        populate(destination);
        destination.cc_snapshotHeightmaps();
        CCClientboundLevelCubePacketData empty = new CCClientboundLevelCubePacketData(new LevelCube(level, POSITION));
        FriendlyByteBuf sections = empty.getReadBuffer();
        try {
            long[] invalid = new long[CubeHeightmap.PACKED_LENGTH];
            invalid[0] = 63;
            assertThrows(IllegalArgumentException.class,
                    () -> destination.replaceWithPacketData(sections, Map.of(Heightmap.Types.WORLD_SURFACE, invalid), ignored -> {}));
            assertEquals(0, sections.readerIndex());
            assertEquals(Blocks.STONE.defaultBlockState(), destination.getBlockState(POSITION.asBlockPos(0, 7, 0)));
            destination.replaceWithPacketData(sections, Map.of(), ignored -> {});
            assertSurfacesMatchBlocks(destination);
            assertEquals(POSITION.minCubeY() - 1, destination.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0));
        } finally {
            sections.release();
        }
    }

    @Test
    void packetRejectsUnsupportedVersionAndInvalidHeightmapArrays() {
        for (int length : new int[] { -1, CubeHeightmap.PACKED_LENGTH - 1, CubeHeightmap.PACKED_LENGTH + 1 }) {
            FriendlyByteBuf malformed = new FriendlyByteBuf(Unpooled.buffer());
            try {
                malformed.writeVarInt(2);
                malformed.writeVarInt(0);
                malformed.writeVarInt(length);
                assertThrows(IllegalArgumentException.class, () -> new CCClientboundLevelCubePacketData(malformed));
            } finally {
                malformed.release();
            }
        }
        FriendlyByteBuf malformed = new FriendlyByteBuf(Unpooled.buffer());
        try {
            malformed.writeVarInt(0);
            assertThrows(IllegalArgumentException.class, () -> new CCClientboundLevelCubePacketData(malformed));
            malformed.clear();
            malformed.writeVarInt(2);
            malformed.writeVarInt(0);
            malformed.writeVarInt(CubeHeightmap.PACKED_LENGTH);
            for (int i = 0; i < CubeHeightmap.PACKED_LENGTH; i++) {
                malformed.writeLong(i == 0 ? 63 : 0);
            }
            assertThrows(IllegalArgumentException.class, () -> new CCClientboundLevelCubePacketData(malformed));
        } finally {
            malformed.release();
        }
    }

    private static void populate(LevelCube cube) {
        for (int x : new int[] { 0, 15, 16, 31 }) {
            for (int z : new int[] { 0, 15, 16, 31 }) {
                cube.setBlockState(POSITION.asBlockPos(x, 7, z), Blocks.STONE.defaultBlockState());
                cube.setBlockState(POSITION.asBlockPos(x, 16, z), Blocks.WATER.defaultBlockState());
                cube.setBlockState(POSITION.asBlockPos(x, 29, z), Blocks.OAK_LEAVES.defaultBlockState());
            }
        }
    }

    private static void assertSurfacesMatchBlocks(CubeAccess cube) {
        for (Heightmap.Types type : Heightmap.Types.values()) {
            for (int x = 0; x < CubicConstants.DIAMETER_IN_BLOCKS; x++) {
                for (int z = 0; z < CubicConstants.DIAMETER_IN_BLOCKS; z++) {
                    int expected = cube.getMinY() - 1;
                    for (int y = cube.getMaxY(); y >= cube.getMinY(); y--) {
                        if (type.isOpaque().test(cube.getBlockState(cube.cc_getCubePos().asBlockPos(x, y - cube.getMinY(), z)))) {
                            expected = y;
                            break;
                        }
                    }
                    assertEquals(expected, cube.getHeight(type, x, z), type + " at " + x + "," + z);
                }
            }
        }
    }
}
