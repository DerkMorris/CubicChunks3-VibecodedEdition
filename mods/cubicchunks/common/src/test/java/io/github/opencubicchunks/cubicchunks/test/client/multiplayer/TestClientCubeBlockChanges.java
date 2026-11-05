package io.github.opencubicchunks.cubicchunks.test.client.multiplayer;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockChangesPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockChangesPacket.Change;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubePacketData;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

class TestClientCubeBlockChanges extends BaseTest {
    @Test
    void realClientCacheAppliesAllSectionsAtExtremeHeightsAndUpdatesHeightsAndRenderNotifications() {
        for (int y : new int[] { -CubePos.MAX_COORDINATE_VALUE - 1, -1000000, 1000000, CubePos.MAX_COORDINATE_VALUE }) {
            Fixture fixture = new Fixture();
            CubePos pos = CubePos.of(-17, y, 31);
            LevelCube cube = fixture.install(pos);
            for (int section = 0; section < CubicConstants.SECTION_COUNT; section++) {
                var packet = new CCClientboundCubeBlockChangesPacket(pos, section,
                        List.of(new Change(0, Blocks.STONE.defaultBlockState(), null), new Change(4095, Blocks.GLOWSTONE.defaultBlockState(), null)));
                fixture.receive(packet);
                for (var change : packet.changes()) {
                    BlockPos block = packet.blockPos(change);
                    assertEquals(change.state(), cube.getBlockState(block));
                    assertEquals(block.getY(), cube.getHeight(Heightmap.Types.WORLD_SURFACE, block.getX(), block.getZ()));
                    verify(fixture.level).setBlocksDirty(eq(block), eq(Blocks.AIR.defaultBlockState()), eq(change.state()));
                    verify(fixture.level).sendBlockUpdated(eq(block), eq(Blocks.AIR.defaultBlockState()), eq(change.state()), anyInt());
                }
            }
            verify(fixture.level, never()).setServerVerifiedBlockState(any(), any(), anyInt());
        }
    }

    @Test
    void aFarUpdateNeverChangesThePreviouslyLoadedVanillaCoordinateAlias() {
        Fixture fixture = new Fixture();
        CubePos far = CubePos.of(0, 1000000, 0);
        BlockPos farBlock = far.asBlockPos(1, 2, 3);
        BlockPos alias = BlockPos.of(farBlock.asLong());
        CubePos near = CubePos.from(alias);
        LevelCube nearCube = fixture.install(near);
        LevelCube farCube = fixture.install(far);
        fixture.receive(new CCClientboundCubeBlockChangesPacket(far, 0,
                List.of(new Change(SectionPos.sectionRelativePos(farBlock), Blocks.STONE.defaultBlockState(), null))));
        assertEquals(Blocks.STONE.defaultBlockState(), farCube.getBlockState(farBlock));
        assertTrue(nearCube.getBlockState(alias).isAir());
    }

    @Test
    void actualSignUpdateTagsReachCreatedAndExistingEntitiesAndRemovalClearsThem() {
        for (int y : new int[] { -1000000, 1000000 }) {
            Fixture fixture = new Fixture();
            CubePos pos = CubePos.of(-3, y, 7);
            LevelCube clientCube = fixture.install(pos);
            LevelCube serverCube = new LevelCube(mockLevelWithPalettes(Level.class), pos);
            BlockPos block = pos.asBlockPos(31, 31, 31);
            serverCube.setBlockState(block, Blocks.OAK_SIGN.defaultBlockState());
            SignBlockEntity source = (SignBlockEntity) serverCube.getBlockEntity(block);
            assertNotNull(source);
            setText(source, "high sign");
            fixture.receive(capture(serverCube, block));
            SignBlockEntity installed = (SignBlockEntity) clientCube.getBlockEntity(block);
            assertNotNull(installed);
            assertEquals(block, installed.getBlockPos());
            assertEquals("high sign", installed.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString());
            long revision = clientCube.cc_getLightRevision();
            setText(source, "updated text");
            fixture.receive(capture(serverCube, block));
            assertEquals(revision, clientCube.cc_getLightRevision(), "Same-state NBT must not invalidate the block snapshot");
            assertEquals("updated text", installed.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString());
            serverCube.setBlockState(block, Blocks.STONE.defaultBlockState());
            fixture.receive(capture(serverCube, block));
            assertEquals(Blocks.STONE.defaultBlockState(), clientCube.getBlockState(block));
            assertNull(clientCube.getBlockEntity(block, LevelChunk.EntityCreationType.CHECK));
        }
    }

    @Test
    void changedBlocksRevokeLightingButDuplicateUpdatesPreserveTheCurrentSnapshot() {
        Fixture fixture = new Fixture();
        CubePos pos = CubePos.of(0, 1000000, 0);
        LevelCube cube = fixture.install(pos);
        LevelCube neighbor = fixture.install(CubePos.of(1, pos.getY(), 0));
        var delta = new CCClientboundCubeBlockChangesPacket(pos, 0, List.of(new Change(0, Blocks.STONE.defaultBlockState(), null)));
        fixture.receive(delta);
        fixture.light(cube, 2);
        fixture.light(neighbor, 2);
        long revision = cube.cc_getLightRevision();
        long generation = fixture.cache.cc_lightGeneration();
        fixture.receive(delta);
        assertTrue(fixture.cache.cc_isCubeLightReady(pos));
        fixture.receive(new CCClientboundCubeBlockChangesPacket(pos, 0, List.of(new Change(0, Blocks.GLOWSTONE.defaultBlockState(), null))));
        assertFalse(fixture.cache.cc_isCubeLightReady(pos));
        assertFalse(fixture.cache.cc_isCubeLightReady(neighbor.cc_getCubePos()));
        assertFalse(fixture.cache.cc_applyCubeLight(cube, revision, 2, exact(pos), generation));
    }

    @Test
    void packetsForMissingEvictedOrdinaryOrPreviousWorldsDoNotCreateOrModifyCubes() {
        Fixture fixture = new Fixture();
        CubePos pos = CubePos.of(0, -1000000, 0);
        var packet = new CCClientboundCubeBlockChangesPacket(pos, 0, List.of(new Change(0, Blocks.STONE.defaultBlockState(), null)));
        fixture.receive(packet);
        assertEquals(0, fixture.cache.cc_getLoadedCubeCount());
        LevelCube cube = fixture.install(pos);
        when(((CanBeCubic) fixture.level).cc_isCubic()).thenReturn(false);
        fixture.receive(packet);
        assertTrue(cube.getBlockState(pos.asBlockPos()).isAir());
        when(((CanBeCubic) fixture.level).cc_isCubic()).thenReturn(true);
        fixture.client.level = mock(ClientLevel.class);
        fixture.receive(packet);
        assertTrue(cube.getBlockState(pos.asBlockPos()).isAir());
        fixture.client.level = fixture.level;
        fixture.cache.cc_drop(pos);
        fixture.receive(packet);
        assertNull(fixture.cache.cc_getCube(pos.getX(), pos.getY(), pos.getZ(), false));
    }

    private static void setText(SignBlockEntity sign, String text) {
        List<Component> lines = List.of(Component.literal(text), Component.empty(), Component.empty(), Component.empty());
        sign.setText(new SignText(lines, lines, DyeColor.BLACK, false), SignTextSlot.FRONT);
    }

    private static CCClientboundCubeBlockChangesPacket capture(LevelCube cube, BlockPos block) {
        return CCClientboundCubeBlockChangesPacket.capture(cube, Coords.blockToIndex(block),
                new ShortOpenHashSet(new short[] { SectionPos.sectionRelativePos(block) }));
    }

    private static CubeLightView exact(CubePos pos) {
        return CubeLightView.fromExact(pos, new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE],
                new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE]);
    }

    private static final class Fixture {
        private final ClientLevel level = mockLevelWithPalettes(ClientLevel.class);
        private final Minecraft client = mock();
        private final ClientCubeCache cache;
        private final io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext context = mock();

        private Fixture() {
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.isClientSide()).thenReturn(true);
            Misc.setupLevelHeight(level, -64, 384);
            ClientChunkCache vanilla = new ClientChunkCache(level, 5);
            cache = (ClientCubeCache) vanilla;
            when(level.getChunkSource()).thenReturn(vanilla);
            client.level = level;
            LocalPlayer player = mock();
            when(player.level()).thenReturn(level);
            when(context.client()).thenReturn(client);
            when(context.player()).thenReturn(player);
        }

        private LevelCube install(CubePos pos) {
            cache.cc_updateViewCenter(pos.getX(), pos.getY(), pos.getZ());
            var data = new CCClientboundLevelCubePacketData(new LevelCube(level, pos));
            FriendlyByteBuf buffer = data.getReadBuffer();
            try {
                return cache.cc_replaceWithPacketData(pos.getX(), pos.getY(), pos.getZ(), buffer, data.getHeightmaps(), ignored -> {});
            } finally {
                buffer.release();
            }
        }

        private void light(LevelCube cube, long epoch) {
            assertTrue(cache.cc_applyCubeLight(cube, cube.cc_getLightRevision(), epoch, exact(cube.cc_getCubePos()), cache.cc_lightGeneration()));
        }

        private void receive(CCClientboundCubeBlockChangesPacket packet) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                CCClientboundCubeBlockChangesPacket.STREAM_CODEC.encode(buffer, packet);
                var decoded = CCClientboundCubeBlockChangesPacket.STREAM_CODEC.decode(buffer);
                new CCClientboundCubeBlockChangesPacket.Handler().receive(decoded, context);
            } finally {
                buffer.release();
            }
        }
    }
}
