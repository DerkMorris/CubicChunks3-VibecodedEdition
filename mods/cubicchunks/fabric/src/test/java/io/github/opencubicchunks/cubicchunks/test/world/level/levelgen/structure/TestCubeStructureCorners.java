package io.github.opencubicchunks.cubicchunks.test.world.level.levelgen.structure;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockChangesPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubeWithLightPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeSetStructureBlockPacket;
import io.github.opencubicchunks.cubicchunks.server.level.CCServerPlayer;
import io.github.opencubicchunks.cubicchunks.server.level.CloTrackingView;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.structure.CubeStructureCorners;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StructureBlock;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.level.blockscan.BlockMatcher;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

class TestCubeStructureCorners extends BaseTest {
    private static final Identifier NAME = Identifier.withDefaultNamespace("example");

    @Test
    void oneCornerUsesSaveBlockAndPreservesCoordinatesAtBothHeightEndpointsAndCubeBoundaries() {
        for (int y : new int[] { CubicBlockBounds.MIN, -32000000, -4096, 2048, 32000000, CubicBlockBounds.MAX }) {
            Fixture f = new Fixture(new BlockPos(31, y, -1));
            int dy = y == CubicBlockBounds.MAX ? -17 : 17;
            f.corner(f.origin.offset(17, dy, -17));
            clearInvocations(f.level);
            assertTrue(f.controller.detectSize());
            assertEquals(new BlockPos(1, dy > 0 ? 1 : -16, -16), f.controller.getStructurePos());
            assertEquals(new Vec3i(16, 16, 16), f.controller.getStructureSize());
            verify(f.level).blockEntityChanged(f.origin);
            verify(f.level).sendBlockUpdated(eq(f.origin), any(), any(), eq(3));
            verify(f.level, never()).getMinY();
            verify(f.level, never()).getMaxY();
            f.noLoading();
        }
    }

    @Test
    void severalCornersSpanCubesWithoutIncludingAnOutsideController() {
        Fixture f = new Fixture(new BlockPos(0, 32000000, 0));
        f.corner(f.origin.offset(35, 34, 33));
        f.corner(f.origin.offset(42, 44, 45));
        f.corner(f.origin.offset(50, 46, 48));
        assertTrue(f.controller.detectSize());
        assertEquals(new BlockPos(36, 35, 34), f.controller.getStructurePos());
        assertEquals(new Vec3i(14, 11, 14), f.controller.getStructureSize());
        assertEquals(216, f.lookups.size());
        assertEquals(216, new HashSet<>(f.lookups).size());
        f.noLoading();
    }

    @Test
    void allEightSectionsContributeAndMutableScanPositionsDoNotAlias() {
        Fixture f = new Fixture(new BlockPos(0, -32000000, 0));
        CubePos cube = CubePos.from(f.origin);
        for (int section = 0; section < 8; section++) {
            f.corner(Coords.sectionPosByIndex(cube, section).origin().offset(2, 2, 2));
        }
        assertTrue(f.controller.detectSize());
        assertEquals(new BlockPos(3, 3, 3), f.controller.getStructurePos());
        assertEquals(new Vec3i(15, 15, 15), f.controller.getStructureSize());
    }

    @Test
    void onlyMatchingNamedCornerBlocksCountAndStaleEntityMapEntriesAreIgnored() {
        Fixture f = new Fixture(new BlockPos(0, 32000000, 0));
        var corner = f.corner(f.origin.offset(10, 11, 12));
        corner.setStructureName("example"); // Minecraft normalizes the default namespace.
        f.block(f.origin.offset(50, 50, 50), StructureMode.SAVE, NAME);
        f.block(f.origin.offset(60, 60, 60), StructureMode.CORNER, Identifier.withDefaultNamespace("other"));
        BlockPos ghostPos = f.origin.offset(70, 70, 70);
        var ghost = new StructureBlockEntity(ghostPos, Blocks.STRUCTURE_BLOCK.defaultBlockState());
        ghost.setLevel(f.level);
        ghost.setMode(StructureMode.CORNER);
        ghost.setStructureName(NAME);
        f.at(ghostPos).getBlockEntities().put(ghostPos, ghost);
        assertTrue(f.controller.detectSize());
        assertEquals(new BlockPos(1, 1, 1), f.controller.getStructurePos());
        assertEquals(new Vec3i(9, 10, 11), f.controller.getStructureSize());
    }

    @Test
    void unnamedCornersRetainVanillaNullNameMatching() {
        Fixture f = new Fixture(new BlockPos(0, 32000000, 0));
        f.controller.setStructureName((Identifier) null);
        f.block(f.origin.offset(3, 4, 5), StructureMode.CORNER, null);
        f.corner(f.origin.offset(20, 20, 20));
        assertTrue(f.controller.detectSize());
        assertEquals(new Vec3i(2, 3, 4), f.controller.getStructureSize());
    }

    @Test
    void searchIncludesRadiusEightyAndExcludesEightyOneOnEveryAxisInsidePartlyOverlappingCubes() {
        Fixture f = new Fixture(new BlockPos(15, -32000001, 15));
        for (Direction direction : Direction.values()) {
            f.corner(f.origin.relative(direction, 80));
            f.corner(f.origin.relative(direction, 81));
        }
        BoundingBox box = CubeStructureCorners.find(f.level, f.origin, NAME).orElseThrow();
        assertEquals(f.origin.offset(-80, -80, -80), new BlockPos(box.minX(), box.minY(), box.minZ()));
        assertEquals(f.origin.offset(80, 80, 80), new BlockPos(box.maxX(), box.maxY(), box.maxZ()));
        assertEquals(216, f.lookups.size());
    }

    @Test
    void clippingAtAllCoordinateLimitsAvoidsCubeKeyAliasesAndIntegerOverflow() {
        for (int endpoint : new int[] { CubicBlockBounds.MIN, CubicBlockBounds.MAX }) {
            Fixture f = new Fixture(new BlockPos(endpoint, endpoint, endpoint));
            int step = endpoint == CubicBlockBounds.MIN ? 10 : -10;
            f.corner(f.origin.offset(step, step, step));
            assertTrue(f.controller.detectSize());
            assertEquals(new BlockPos(step > 0 ? 1 : -9, step > 0 ? 1 : -9, step > 0 ? 1 : -9), f.controller.getStructurePos());
            assertEquals(new Vec3i(9, 9, 9), f.controller.getStructureSize());
            assertEquals(27, f.lookups.size());
            for (CubePos pos : f.lookups) {
                assertEquals(pos, CubePos.from(pos.asLong()));
                assertTrue(CubicBlockBounds.contains(pos.asBlockPos()));
            }
        }
    }

    @Test
    void unsupportedOriginsReturnNoResultBeforeAnyLookup() {
        Fixture f = new Fixture(BlockPos.ZERO);
        for (BlockPos invalid : List.of(new BlockPos(Integer.MAX_VALUE, 0, 0), new BlockPos(0, Integer.MIN_VALUE, 0),
                new BlockPos(0, 0, CubicBlockBounds.MAX + 1))) {
            assertTrue(CubeStructureCorners.find(f.level, invalid, NAME).isEmpty());
        }
        assertTrue(f.lookups.isEmpty());
    }

    @Test
    void missingCoverageKeepsPreviousDimensionsAndPendingTagsUntilACompleteRetry() {
        Fixture f = new Fixture(new BlockPos(0, -32000000, 0));
        BlockPos cornerPos = f.origin.offset(10, 11, 12);
        f.pendingCorner(cornerPos);
        f.controller.setStructurePos(new BlockPos(7, 8, 9));
        f.controller.setStructureSize(new Vec3i(3, 4, 5));
        f.unavailable.add(CubePos.from(f.origin.offset(80, 80, 80)));
        clearInvocations(f.level);
        assertFalse(f.controller.detectSize());
        assertEquals(new BlockPos(7, 8, 9), f.controller.getStructurePos());
        assertEquals(new Vec3i(3, 4, 5), f.controller.getStructureSize());
        assertNotNull(f.at(cornerPos).getBlockEntityNbt(cornerPos));
        assertNull(f.at(cornerPos).getBlockEntities().get(cornerPos));
        verify(f.level, never()).blockEntityChanged(f.origin);
        verify(f.level, never()).sendBlockUpdated(any(), any(), any(), anyInt());
        f.unavailable.clear();
        assertTrue(f.controller.detectSize());
        assertEquals(new Vec3i(9, 10, 11), f.controller.getStructureSize());
        assertNull(f.at(cornerPos).getBlockEntityNbt(cornerPos));
        assertNotNull(f.at(cornerPos).getBlockEntities().get(cornerPos));
        f.noLoading();
    }

    @Test
    void wrongPositionOrWorldInCacheCannotSupplyCoverage() {
        for (boolean foreignWorld : new boolean[] { false, true }) {
            Fixture f = new Fixture(new BlockPos(0, 32000000, 0));
            f.corner(f.origin.offset(10, 11, 12));
            CubePos pos = CubePos.from(f.origin.offset(80, 80, 80));
            ServerLevel owner = foreignWorld ? mockLevelWithPalettes(ServerLevel.class) : f.level;
            CubePos actual = foreignWorld ? pos : CubePos.of(pos.getX() + 1, pos.getY(), pos.getZ());
            f.cubes.put(pos, new LevelCube(owner, actual));
            clearInvocations(f.level);
            assertFalse(f.controller.detectSize());
            verify(f.level, never()).sendBlockUpdated(any(), any(), any(), anyInt());
        }
    }

    @Test
    void absentCornersWrongModeAndDegenerateInteriorLeaveDimensionsAndDirtyNotificationsAlone() {
        Fixture f = new Fixture(new BlockPos(0, 32000000, 0));
        f.controller.setStructurePos(new BlockPos(7, 8, 9));
        f.controller.setStructureSize(new Vec3i(3, 4, 5));
        clearInvocations(f.level);
        assertFalse(f.controller.detectSize());
        f.corner(f.origin.offset(1, 10, 10));
        assertFalse(f.controller.detectSize());
        assertEquals(new BlockPos(7, 8, 9), f.controller.getStructurePos());
        assertEquals(new Vec3i(3, 4, 5), f.controller.getStructureSize());
        verify(f.level, never()).blockEntityChanged(f.origin);
        verify(f.level, never()).sendBlockUpdated(any(), any(), any(), anyInt());
        f.controller.setMode(StructureMode.LOAD);
        f.lookups.clear();
        assertFalse(f.controller.detectSize());
        assertTrue(f.lookups.isEmpty());
    }

    @Test
    void ordinaryWorldRetainsVanillaColumnSearchAndProducesTheSameInteriorForKnownCorners() {
        for (int cornerCount = 0; cornerCount <= 3; cornerCount++) {
            Fixture cubic = new Fixture(new BlockPos(10, 70, 10));
            Fixture vanilla = new Fixture(cubic.origin);
            List<BlockPos> positions = new ArrayList<>();
            for (int index = 0; index < cornerCount; index++) {
                BlockPos pos = cubic.origin.offset(5 + 3 * index, 6 + 4 * index, 7 + 5 * index);
                cubic.corner(pos);
                vanilla.corner(pos);
                positions.add(pos);
            }
            vanilla.vanillaScan(positions);
            assertEquals(vanilla.controller.detectSize(), cubic.controller.detectSize());
            assertEquals(vanilla.controller.getStructurePos(), cubic.controller.getStructurePos());
            assertEquals(vanilla.controller.getStructureSize(), cubic.controller.getStructureSize());
            verify(vanilla.level).findBlocksIn(new BlockPos(-70, -64, -70), new BlockPos(90, 319, 90));
            assertTrue(vanilla.lookups.isEmpty());
        }
    }

    @Test
    void decodedScanPacketRunsRealSearchWithPermissionsAndItsResultReachesTheClientCube() {
        for (int y : new int[] { -32000000, 32000000 }) {
            Fixture f = new Fixture(new BlockPos(0, y, 0));
            f.corner(f.origin.offset(35, 34, 33));
            f.corner(f.origin.offset(42, 44, 45));
            ClientFixture client = new ClientFixture(f);
            ServerPlayer player = mock();
            when(player.level()).thenReturn(f.level);
            when(((CCServerPlayer) player).cc_getCloTrackingView()).thenReturn(CloTrackingView.cc_of(CloPos.cube(CubePos.from(f.origin)), 5));
            ServerGamePacketListenerImpl listener = mock();
            player.connection = listener;
            listener.player = player;
            when(listener.hasClientLoaded()).thenReturn(true);
            doCallRealMethod().when(listener).handleSetStructureBlock(any());
            CubePos pos = CubePos.from(f.origin);
            when(f.source.cc_getCube(pos.getX(), pos.getY(), pos.getZ(), ChunkStatus.FULL, false)).thenReturn(f.at(f.origin));
            ServerPlayNetworking.Context context = mock();
            when(context.player()).thenReturn(player);
            var packet = new CCServerboundCubeSetStructureBlockPacket(f.origin, StructureBlockEntity.UpdateType.SCAN_AREA, StructureMode.SAVE,
                    NAME.toString(), BlockPos.ZERO, Vec3i.ZERO, Mirror.NONE, Rotation.NONE, "", true, false, false, true, 1, 0);
            var decoded = roundTrip(CCServerboundCubeSetStructureBlockPacket.STREAM_CODEC, packet);
            try (var thread = mockStatic(PacketUtils.class)) {
                new CCServerboundCubeSetStructureBlockPacket.Handler().receive(decoded, context.player());
                assertTrue(f.lookups.isEmpty());
                when(player.canUseGameMasterBlocks()).thenReturn(true);
                new CCServerboundCubeSetStructureBlockPacket.Handler().receive(decoded, context.player());
            }
            assertEquals(new BlockPos(36, 35, 34), f.controller.getStructurePos());
            assertEquals(new Vec3i(6, 9, 11), f.controller.getStructureSize());
            client.receiveResult(f);
            assertEquals(f.controller.getStructurePos(), client.entity.getStructurePos());
            assertEquals(f.controller.getStructureSize(), client.entity.getStructureSize());
            assertEquals(f.origin, client.entity.getBlockPos());
        }
    }

    private static <T> T roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, packet);
            T decoded = codec.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static final class Fixture {
        private final BlockPos origin;
        private final ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        private final ServerChunkCache cache = mock();
        private final CubeSource source = (CubeSource) cache;
        private final Map<CubePos, LevelCube> cubes = new HashMap<>();
        private final Set<CubePos> unavailable = new HashSet<>();
        private final List<CubePos> lookups = new ArrayList<>();
        private final StructureBlockEntity controller;

        private Fixture(BlockPos origin) {
            this.origin = origin;
            Misc.setupLevelHeight(level, -64, 384);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.getChunkSource()).thenReturn(cache);
            when(cache.getLightEngine()).thenReturn(mock());
            // Unlisted positions model already loaded empty cubes; production never creates them during this lookup.
            when(source.cc_getCubeNow(anyInt(), anyInt(), anyInt())).thenAnswer(i -> {
                CubePos pos = CubePos.of(i.getArgument(0), i.getArgument(1), i.getArgument(2));
                lookups.add(pos);
                return unavailable.contains(pos) ? null : at(pos.asBlockPos());
            });
            when(level.getBlockState(any())).thenAnswer(i -> at(i.getArgument(0)).getBlockState(i.getArgument(0)));
            when(level.getBlockEntity(any(BlockPos.class))).thenAnswer(i -> at(i.getArgument(0)).getBlockEntity(i.getArgument(0)));
            when(level.getBlockEntity(any(BlockPos.class), eq(BlockEntityTypes.STRUCTURE_BLOCK)))
                    .thenAnswer(i -> Optional.ofNullable((StructureBlockEntity) at(i.getArgument(0)).getBlockEntity(i.getArgument(0))));
            when(level.setBlock(any(), any(), anyInt()))
                    .thenAnswer(i -> at(i.getArgument(0)).setBlockState(i.getArgument(0), i.getArgument(1)) != null);
            controller = block(origin, StructureMode.SAVE, NAME);
        }

        private LevelCube at(BlockPos pos) {
            return cubes.computeIfAbsent(CubePos.from(pos), p -> new LevelCube(level, p));
        }

        private StructureBlockEntity block(BlockPos pos, StructureMode mode, Identifier name) {
            LevelCube cube = at(pos);
            cube.setBlockState(pos, Blocks.STRUCTURE_BLOCK.defaultBlockState().setValue(StructureBlock.MODE, mode));
            StructureBlockEntity entity = (StructureBlockEntity) cube.getBlockEntity(pos);
            entity.setMode(mode);
            entity.setStructureName(name);
            return entity;
        }

        private StructureBlockEntity corner(BlockPos pos) {
            return block(pos, StructureMode.CORNER, NAME);
        }

        private void pendingCorner(BlockPos pos) {
            StructureBlockEntity entity = corner(pos);
            var tag = entity.saveWithFullMetadata(level.registryAccess());
            at(pos).getBlockEntities().remove(pos);
            at(pos).setBlockEntityNbt(tag);
        }

        private void noLoading() {
            verify(source, never()).cc_getCube(anyInt(), anyInt(), anyInt(), any(), anyBoolean());
            verify(source, never()).cc_getCube(anyInt(), anyInt(), anyInt(), anyBoolean());
            verify(level, never()).getChunkAt(any());
            verify(level, never()).findBlocksIn(any(BlockPos.class), any(BlockPos.class));
        }

        private void vanillaScan(List<BlockPos> positions) {
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(false);
            BlockMatcher matcher = mock();
            when(level.findBlocksIn(any(BlockPos.class), any(BlockPos.class))).thenReturn(matcher);
            when(matcher.filterState(any())).thenReturn(matcher);
            doAnswer(i -> {
                BiConsumer<BlockPos, BlockState> action = i.getArgument(0);
                positions.forEach(pos -> action.accept(pos, at(pos).getBlockState(pos)));
                return null;
            }).when(matcher).forEach(any());
        }
    }

    private static final class ClientFixture {
        private final io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext context = mock();
        private final StructureBlockEntity entity;

        private ClientFixture(Fixture server) {
            ClientLevel level = mockLevelWithPalettes(ClientLevel.class);
            Misc.setupLevelHeight(level, -64, 384);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.isClientSide()).thenReturn(true);
            ClientChunkCache vanilla = new ClientChunkCache(level, 5);
            ClientCubeCache cache = (ClientCubeCache) vanilla;
            when(level.getChunkSource()).thenReturn(vanilla);
            Minecraft client = mock();
            client.level = level;
            LocalPlayer player = mock();
            when(player.level()).thenReturn(level);
            when(context.client()).thenReturn(client);
            when(context.player()).thenReturn(player);
            CubePos pos = CubePos.from(server.origin);
            cache.cc_updateViewCenter(pos.getX(), pos.getY(), pos.getZ());
            var packet = roundTrip(CCClientboundLevelCubeWithLightPacket.STREAM_CODEC,
                    new CCClientboundLevelCubeWithLightPacket(server.at(server.origin)));
            new CCClientboundLevelCubeWithLightPacket.Handler().receive(packet, context);
            entity = (StructureBlockEntity) cache.cc_getCube(pos.getX(), pos.getY(), pos.getZ(), false).getBlockEntities().get(server.origin);
        }

        private void receiveResult(Fixture server) {
            var packet = CCClientboundCubeBlockChangesPacket.capture(server.at(server.origin), Coords.blockToIndex(server.origin),
                    new ShortOpenHashSet(new short[] { SectionPos.sectionRelativePos(server.origin) }));
            new CCClientboundCubeBlockChangesPacket.Handler().receive(roundTrip(CCClientboundCubeBlockChangesPacket.STREAM_CODEC, packet), context);
        }
    }
}
