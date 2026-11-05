package io.github.opencubicchunks.cubicchunks.test.client.multiplayer;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubePredictions;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubicClientLevel;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockChangesPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubePacketData;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubeWithLightPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubePlayerActionPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeUseItemOnPacket;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevel;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class TestCubePredictions extends BaseTest {
    @Test
    void realClientGameModePredictsCreativeBreakThenSendsCustomActionAndReconciles() throws ReflectiveOperationException {
        Fixture fixture = new Fixture(5);
        BlockPos pos = new BlockPos(1, 32000000, 2);
        LevelCube cube = fixture.install(CubePos.from(pos));
        cube.setBlockState(pos, Blocks.STONE.defaultBlockState());
        List<Packet<?>> sent = new java.util.ArrayList<>();
        MultiPlayerGameMode mode = fixture.gameMode(sent);
        fixture.player.getAbilities().instabuild = true;
        when(fixture.player.getMainHandItem()).thenReturn(new ItemStack(Items.DIAMOND_PICKAXE));
        try (var networking = mockStatic(ClientPlayNetworking.class)) {
            networking.when(() -> ClientPlayNetworking.canSend(CCServerboundCubePlayerActionPacket.TYPE)).thenReturn(true);
            assertTrue(mode.startDestroyBlock(pos, Direction.UP));
        }
        assertTrue(cube.getBlockState(pos).isAir());
        var payload = (CCServerboundCubePlayerActionPacket) ((ServerboundCustomPayloadPacket) sent.getLast()).payload();
        assertEquals(pos, payload.pos());
        assertEquals(fixture.prediction.currentSequence(), payload.sequence());
        assertFalse(fixture.prediction.isPredicting());
        fixture.level.handleBlockChangedAck(payload.sequence());
        assertEquals(Blocks.STONE.defaultBlockState(), cube.getBlockState(pos));
    }

    @Test
    void realStoneItemPlacementPredictsNeighborAndUsePacketSharesItsSequence() throws ReflectiveOperationException {
        Fixture fixture = new Fixture(5);
        BlockPos pos = new BlockPos(1, -32000000, 2);
        BlockPos placed = pos.above();
        LevelCube cube = fixture.install(CubePos.from(pos));
        cube.setBlockState(pos, Blocks.STONE.defaultBlockState());
        List<Packet<?>> sent = new java.util.ArrayList<>();
        MultiPlayerGameMode mode = fixture.gameMode(sent);
        ItemStack stack = new ItemStack(Items.STONE);
        when(fixture.player.getItemInHand(any())).thenReturn(stack);
        when(fixture.player.getMainHandItem()).thenReturn(stack);
        when(fixture.player.getOffhandItem()).thenReturn(ItemStack.EMPTY);
        when(fixture.player.getDirection()).thenReturn(Direction.NORTH);
        when(fixture.player.getCooldowns()).thenReturn(mock());
        when(fixture.player.hasInfiniteMaterials()).thenReturn(true);
        when(fixture.level.isUnobstructed(any(), any(), any())).thenReturn(true);
        try (var networking = mockStatic(ClientPlayNetworking.class)) {
            networking.when(() -> ClientPlayNetworking.canSend(CCServerboundCubeUseItemOnPacket.TYPE)).thenReturn(true);
            assertTrue(mode.useItemOn(fixture.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false)).consumesAction());
        }
        assertEquals(Blocks.STONE.defaultBlockState(), cube.getBlockState(placed));
        var payload = (CCServerboundCubeUseItemOnPacket) ((ServerboundCustomPayloadPacket) sent.getLast()).payload();
        assertEquals(pos, payload.pos());
        assertFalse(fixture.prediction.isPredicting());
        fixture.receive(placed, Blocks.AIR.defaultBlockState());
        assertEquals(Blocks.STONE.defaultBlockState(), cube.getBlockState(placed));
        fixture.level.handleBlockChangedAck(payload.sequence());
        assertTrue(cube.getBlockState(placed).isAir());
    }

    @Test
    void actualClientSetBlockAndAcknowledgementRestoreRejectedEditsAtBothRangeEndpoints() throws ReflectiveOperationException {
        for (int y : new int[] { CubicBlockBounds.MIN, -32000000, 32000000, CubicBlockBounds.MAX }) {
            Fixture fixture = new Fixture(5);
            BlockPos pos = new BlockPos(-17, y, 31);
            LevelCube cube = fixture.install(CubePos.from(pos));
            cube.setBlockState(pos, Blocks.STONE.defaultBlockState());
            int sequence = fixture.predict(pos, Blocks.AIR.defaultBlockState());
            assertTrue(fixture.level.getBlockState(pos).isAir());
            fixture.level.handleBlockChangedAck(sequence);
            assertEquals(Blocks.STONE.defaultBlockState(), fixture.level.getBlockState(pos));
            assertEquals(384, fixture.level.getHeight());
            assertEquals(24, fixture.level.getSectionsCount());
            assertFalse(fixture.level.isOutsideBuildHeight(pos));
        }
    }

    @Test
    void serverStateIsDeferredUntilAckAndAcceptedPredictionsDoNotRevert() throws ReflectiveOperationException {
        Fixture fixture = new Fixture(5);
        BlockPos pos = new BlockPos(1, 32000000, 2);
        LevelCube cube = fixture.install(CubePos.from(pos));
        cube.setBlockState(pos, Blocks.STONE.defaultBlockState());
        int sequence = fixture.predict(pos, Blocks.AIR.defaultBlockState());
        fixture.receive(pos, Blocks.DIRT.defaultBlockState());
        assertTrue(cube.getBlockState(pos).isAir());
        fixture.level.handleBlockChangedAck(sequence);
        assertEquals(Blocks.DIRT.defaultBlockState(), cube.getBlockState(pos));
        int next = fixture.predict(pos, Blocks.AIR.defaultBlockState());
        fixture.receive(pos, Blocks.AIR.defaultBlockState());
        long revision = cube.cc_getLightRevision();
        fixture.level.handleBlockChangedAck(next);
        assertTrue(cube.getBlockState(pos).isAir());
        assertEquals(revision, cube.cc_getLightRevision());
    }

    @Test
    void repeatedPredictionsWaitForLatestSequenceAndStaleAckDoesNotReapplyAnything() throws ReflectiveOperationException {
        Fixture fixture = new Fixture(5);
        BlockPos pos = new BlockPos(1, -32000000, 2);
        LevelCube cube = fixture.install(CubePos.from(pos));
        cube.setBlockState(pos, Blocks.STONE.defaultBlockState());
        int first = fixture.predict(pos, Blocks.DIRT.defaultBlockState());
        fixture.receive(pos, Blocks.DIRT.defaultBlockState());
        int second = fixture.predict(pos, Blocks.GLOWSTONE.defaultBlockState());
        fixture.level.handleBlockChangedAck(first);
        assertEquals(Blocks.GLOWSTONE.defaultBlockState(), cube.getBlockState(pos));
        fixture.level.handleBlockChangedAck(second);
        assertEquals(Blocks.DIRT.defaultBlockState(), cube.getBlockState(pos));
        fixture.receive(pos, Blocks.STONE.defaultBlockState());
        fixture.level.handleBlockChangedAck(first);
        fixture.level.handleBlockChangedAck(second);
        assertEquals(Blocks.STONE.defaultBlockState(), cube.getBlockState(pos));
    }

    @Test
    void simultaneouslyLoadedBlocksWithIdenticalVanillaPackedKeysHaveIndependentPredictions() throws ReflectiveOperationException {
        Fixture fixture = new Fixture(65);
        BlockPos lower = new BlockPos(1, 32000000, 2);
        BlockPos upper = lower.above(4096);
        assertEquals(lower.asLong(), upper.asLong());
        LevelCube lowCube = fixture.install(CubePos.from(lower));
        LevelCube highCube = fixture.install(CubePos.from(upper));
        fixture.cache.cc_updateViewCenter(0, CubePos.from(lower).getY() + 64, 0);
        lowCube.setBlockState(lower, Blocks.STONE.defaultBlockState());
        highCube.setBlockState(upper, Blocks.DIRT.defaultBlockState());
        int first = fixture.predict(lower, Blocks.AIR.defaultBlockState());
        int second = fixture.predict(upper, Blocks.GLOWSTONE.defaultBlockState());
        fixture.receive(lower, Blocks.COBBLESTONE.defaultBlockState());
        fixture.level.handleBlockChangedAck(first);
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), lowCube.getBlockState(lower));
        assertEquals(Blocks.GLOWSTONE.defaultBlockState(), highCube.getBlockState(upper));
        fixture.level.handleBlockChangedAck(second);
        assertEquals(Blocks.DIRT.defaultBlockState(), highCube.getBlockState(upper));
    }

    @Test
    void authoritativeEntityTagWaitsWithPredictedStateAndRestoresRealSignOnAck() throws ReflectiveOperationException {
        Fixture fixture = new Fixture(5);
        BlockPos pos = new BlockPos(1, -32000000, 2);
        LevelCube cube = fixture.install(CubePos.from(pos));
        cube.setBlockState(pos, Blocks.OAK_SIGN.defaultBlockState());
        LevelCube serverCube = new LevelCube(mockLevelWithPalettes(Level.class), CubePos.from(pos));
        serverCube.setBlockState(pos, Blocks.OAK_SIGN.defaultBlockState());
        SignBlockEntity sign = (SignBlockEntity) serverCube.getBlockEntity(pos);
        List<Component> text = List.of(Component.literal("server text"), Component.empty(), Component.empty(), Component.empty());
        sign.setText(new SignText(text, text, DyeColor.BLACK, false), SignTextSlot.FRONT);
        int sequence = fixture.predict(pos, Blocks.AIR.defaultBlockState());
        fixture.receive(CCClientboundCubeBlockChangesPacket.capture(serverCube, Coords.blockToIndex(pos),
                new ShortOpenHashSet(new short[] { SectionPos.sectionRelativePos(pos) })));
        assertTrue(cube.getBlockState(pos).isAir());
        fixture.level.handleBlockChangedAck(sequence);
        SignBlockEntity restored = (SignBlockEntity) cube.getBlockEntity(pos);
        assertNotNull(restored);
        assertEquals("server text", restored.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString());
    }

    @Test
    void rejectedSignBreakWithoutCorrectionRestoresPreviousVisibleEntityData() throws ReflectiveOperationException {
        Fixture fixture = new Fixture(5);
        BlockPos pos = new BlockPos(1, 32000000, 2);
        LevelCube cube = fixture.install(CubePos.from(pos));
        cube.setBlockState(pos, Blocks.OAK_SIGN.defaultBlockState());
        SignBlockEntity sign = (SignBlockEntity) cube.getBlockEntity(pos);
        List<Component> text = List.of(Component.literal("original text"), Component.empty(), Component.empty(), Component.empty());
        sign.setText(new SignText(text, text, DyeColor.BLACK, false), SignTextSlot.FRONT);
        int sequence = fixture.predict(pos, Blocks.AIR.defaultBlockState());
        fixture.level.handleBlockChangedAck(sequence);
        SignBlockEntity restored = (SignBlockEntity) cube.getBlockEntity(pos);
        assertEquals("original text", restored.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString());
    }

    @Test
    void rollbackMovesCollidingPlayerButNeverRewindsATeleport() throws ReflectiveOperationException {
        Fixture fixture = new Fixture(5);
        BlockPos pos = new BlockPos(1, 32000000, 2);
        LevelCube cube = fixture.install(CubePos.from(pos));
        when(fixture.player.isColliding(any(), any())).thenReturn(true);
        cube.setBlockState(pos, Blocks.STONE.defaultBlockState());
        int first = fixture.predict(pos, Blocks.AIR.defaultBlockState());
        fixture.level.handleBlockChangedAck(first);
        verify(fixture.player).absSnapTo(1.25, 32000002.0, 2.5);
        int second = fixture.predict(pos, Blocks.AIR.defaultBlockState());
        fixture.prediction.onTeleport();
        org.mockito.Mockito.clearInvocations(fixture.player);
        fixture.level.handleBlockChangedAck(second);
        assertEquals(Blocks.STONE.defaultBlockState(), cube.getBlockState(pos));
        verify(fixture.player, never()).absSnapTo(anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void dropAndSameObjectFullSnapshotDiscardOldPredictionsBeforeLateAck() throws ReflectiveOperationException {
        Fixture fixture = new Fixture(5);
        BlockPos pos = new BlockPos(1, 32000000, 2);
        CubePos cubePos = CubePos.from(pos);
        LevelCube original = fixture.install(cubePos);
        original.setBlockState(pos, Blocks.STONE.defaultBlockState());
        int first = fixture.predict(pos, Blocks.DIRT.defaultBlockState());
        assertSame(original, fixture.install(cubePos));
        fixture.level.handleBlockChangedAck(first);
        assertTrue(original.getBlockState(pos).isAir());
        int second = fixture.predict(pos, Blocks.GLOWSTONE.defaultBlockState());
        fixture.cache.cc_drop(cubePos);
        LevelCube replacement = fixture.install(cubePos);
        replacement.setBlockState(pos, Blocks.COBBLESTONE.defaultBlockState());
        fixture.level.handleBlockChangedAck(second);
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), replacement.getBlockState(pos));
        assertEquals(1, fixture.cache.cc_getLoadedCubeCount());
    }

    @Test
    void fullRelightPreservesPendingBreakAndRejectsLightForReplayedBlocks() throws ReflectiveOperationException {
        Fixture fixture = new Fixture(5);
        BlockPos pos = new BlockPos(1, 32000000, 2);
        LevelCube cube = fixture.install(CubePos.from(pos));
        cube.setBlockState(pos, Blocks.STONE.defaultBlockState());
        CubeLightView oldLight = fixture.zeroLight(CubePos.from(pos));
        assertTrue(fixture.cache.cc_applyScopedCubeLight(cube, cube.cc_getLightRevision(), 1, oldLight, fixture.cache.cc_lightGeneration()));
        int first = fixture.predict(pos, Blocks.AIR.defaultBlockState());
        LevelCube server = new LevelCube(mockLevelWithPalettes(Level.class), CubePos.from(pos));
        server.setBlockState(pos, Blocks.DIRT.defaultBlockState());
        fixture.receiveFull(server, 2);
        assertTrue(cube.getBlockState(pos).isAir(), "A relight snapshot cannot undo an unacknowledged local break");
        fixture.queuedLight.forEach(Runnable::run);
        assertNull(fixture.cache.cc_getCubeLightView(CubePos.from(pos)), "Queued light describes the old solid block");
        assertSame(oldLight, fixture.cache.cc_getVisibleCubeLightView(CubePos.from(pos)));
        fixture.level.handleBlockChangedAck(first);
        assertEquals(Blocks.DIRT.defaultBlockState(), cube.getBlockState(pos), "Rejection uses the latest authoritative snapshot");
        int second = fixture.predict(pos, Blocks.AIR.defaultBlockState());
        fixture.receiveFull(server, 3);
        fixture.receive(pos, Blocks.AIR.defaultBlockState());
        assertTrue(cube.getBlockState(pos).isAir());
        fixture.level.handleBlockChangedAck(second);
        assertTrue(cube.getBlockState(pos).isAir(), "A later delta confirms the same pending sequence");
    }

    @Test
    void fullSnapshotKeepsSpeculativeSignTagAndAcknowledgesItsLatestServerTag() throws ReflectiveOperationException {
        Fixture fixture = new Fixture(5);
        BlockPos pos = new BlockPos(1, -32000000, 2);
        LevelCube cube = fixture.install(CubePos.from(pos));
        cube.setBlockState(pos, Blocks.STONE.defaultBlockState());
        int sequence = fixture.predict(pos, Blocks.OAK_SIGN.defaultBlockState());
        setSignText(cube, pos, "local predicted sign");
        LevelCube server = new LevelCube(mockLevelWithPalettes(Level.class), CubePos.from(pos));
        server.setBlockState(pos, Blocks.OAK_SIGN.defaultBlockState());
        setSignText(server, pos, "authoritative sign");
        fixture.receiveFull(server, 2);
        assertEquals("local predicted sign", signText(cube, pos));
        fixture.level.handleBlockChangedAck(sequence);
        assertEquals("authoritative sign", signText(cube, pos));
    }

    private static void setSignText(LevelCube cube, BlockPos pos, String message) {
        List<Component> text = List.of(Component.literal(message), Component.empty(), Component.empty(), Component.empty());
        ((SignBlockEntity) cube.getBlockEntity(pos)).setText(new SignText(text, text, DyeColor.BLACK, false), SignTextSlot.FRONT);
    }

    private static String signText(LevelCube cube, BlockPos pos) {
        return ((SignBlockEntity) cube.getBlockEntity(pos)).getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString();
    }

    @Test
    void verifiedStateEntryPointUsesCubicQueueAndOrdinaryWorldRetainsVanillaHeightBounds() throws ReflectiveOperationException {
        Fixture fixture = new Fixture(5);
        BlockPos pos = new BlockPos(1, 32000000, 2);
        LevelCube cube = fixture.install(CubePos.from(pos));
        int sequence = fixture.predict(pos, Blocks.STONE.defaultBlockState());
        fixture.level.setServerVerifiedBlockState(pos, Blocks.DIRT.defaultBlockState(), 19);
        assertEquals(Blocks.STONE.defaultBlockState(), cube.getBlockState(pos));
        fixture.level.handleBlockChangedAck(sequence);
        assertEquals(Blocks.DIRT.defaultBlockState(), cube.getBlockState(pos));
        assertFalse(fixture.level.setBlock(new BlockPos(1, CubicBlockBounds.MAX + 1, 2), Blocks.STONE.defaultBlockState(), 18, 0));
        assertFalse(fixture.level.isInValidBounds(new BlockPos(Integer.MAX_VALUE, 0, 0)));
        assertFalse(fixture.level.isInValidBounds(new BlockPos(0, 0, Integer.MIN_VALUE)));
        setField(Level.class, fixture.level, "cc_isCubic", false);
        assertFalse(fixture.level.isInsideBuildHeight(pos));
        assertFalse(fixture.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 18, 0));
        assertTrue(fixture.level.isInsideBuildHeight(-64));
        assertTrue(fixture.level.isInsideBuildHeight(319));
        assertFalse(fixture.level.isInsideBuildHeight(320));
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class Fixture {
        private final ClientLevel level = mockLevelWithPalettes(ClientLevel.class);
        private final Minecraft client = mock();
        private final LocalPlayer player = mock();
        private final BlockStatePredictionHandler prediction = new BlockStatePredictionHandler();
        private final ClientCubeCache cache;
        private final io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext context = mock();
        private final List<Runnable> queuedLight = new java.util.ArrayList<>();

        private Fixture(int radius) throws ReflectiveOperationException {
            client.level = level;
            client.player = player;
            when(player.level()).thenReturn(level);
            when(player.position()).thenReturn(new Vec3(1.25, 32000002.0, 2.5));
            when(player.getAbilities()).thenReturn(new Abilities());
            when(level.isClientSide()).thenReturn(true);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            Misc.setupLevelHeight(level, -64, 384);
            setField(Level.class, level, "cc_isCubic", true);
            setField(ClientLevel.class, level, "minecraft", client);
            setField(ClientLevel.class, level, "blockStatePredictionHandler", prediction);
            var predictionGetter = ClientLevel.class.getDeclaredMethod("getBlockStatePredictionHandler");
            predictionGetter.setAccessible(true);
            when((BlockStatePredictionHandler) predictionGetter.invoke(level)).thenReturn(prediction);
            when(level.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
            ClientCubePredictions predictions = new ClientCubePredictions();
            setField(ClientLevel.class, level, "cc_predictions", predictions);
            when(((CubicClientLevel) level).cc_getCubePredictions()).thenReturn(predictions);
            doCallRealMethod().when((CubicClientLevel) level).cc_forgetCubePredictions(any());
            ClientChunkCache vanilla = new ClientChunkCache(level, radius);
            cache = (ClientCubeCache) vanilla;
            setField(ClientLevel.class, level, "chunkSource", vanilla);
            when(level.getChunkSource()).thenReturn(vanilla);
            when(((CubicClientLevel) level).cc_getCubeSource()).thenReturn(cache);
            when(((CubicLevel) level).cc_getCubeAt(any())).thenAnswer(invocation -> {
                CubePos pos = CubePos.from((BlockPos) invocation.getArgument(0));
                return cache.cc_getCube(pos.getX(), pos.getY(), pos.getZ(), false);
            });
            doCallRealMethod().when(level).isInsideBuildHeight(anyInt());
            doCallRealMethod().when(level).isInsideBuildHeight(any(BlockPos.class));
            doCallRealMethod().when(level).isOutsideBuildHeight(anyInt());
            doCallRealMethod().when(level).isOutsideBuildHeight(any(BlockPos.class));
            doCallRealMethod().when(level).isInValidBounds(any());
            doCallRealMethod().when(level).getBlockState(any());
            doCallRealMethod().when(level).getFluidState(any());
            doCallRealMethod().when(level).setBlock(any(), any(), anyInt());
            doCallRealMethod().when(level).setBlock(any(), any(), anyInt(), anyInt());
            doCallRealMethod().when(level).handleBlockChangedAck(anyInt());
            doCallRealMethod().when(level).setServerVerifiedBlockState(any(), any(), anyInt());
            when(context.client()).thenReturn(client);
            when(context.player()).thenReturn(player);
            doAnswer(invocation -> {
                queuedLight.add(invocation.getArgument(0));
                return null;
            }).when(level).queueLightUpdate(any());
        }

        private MultiPlayerGameMode gameMode(List<Packet<?>> sent) throws ReflectiveOperationException {
            ClientPacketListener listener = mock();
            Connection connection = mock();
            when(client.getTutorial()).thenReturn(mock());
            when(level.getWorldBorder()).thenReturn(new WorldBorder());
            when(player.getInventory()).thenReturn(mock());
            when(listener.isFeatureEnabled(any())).thenReturn(true);
            setField(ClientCommonPacketListenerImpl.class, listener, "minecraft", client);
            setField(ClientCommonPacketListenerImpl.class, listener, "connection", connection);
            doAnswer(invocation -> {
                sent.add(invocation.getArgument(0));
                return null;
            }).when(connection).send(any());
            doCallRealMethod().when(listener).send(any());
            return new MultiPlayerGameMode(client, listener);
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

        private int predict(BlockPos pos, BlockState state) {
            try (var scope = prediction.startPredicting()) {
                assertTrue(level.setBlock(pos, state, 18, 0));
                return scope.currentSequence();
            }
        }

        private CubeLightView zeroLight(CubePos pos) {
            return CubeLightView.fromExact(pos, new byte[CubicConstants.SECTION_COUNT][2048], new byte[CubicConstants.SECTION_COUNT][2048]);
        }

        private void receiveFull(LevelCube server, long epoch) {
            var packet = new CCClientboundLevelCubeWithLightPacket(server.cc_getCubePos(), new CCClientboundLevelCubePacketData(server), epoch,
                    zeroLight(server.cc_getCubePos()), true);
            new CCClientboundLevelCubeWithLightPacket.Handler().receive(packet, context);
        }

        private void receive(BlockPos pos, BlockState state) {
            receive(new CCClientboundCubeBlockChangesPacket(CubePos.from(pos), Coords.blockToIndex(pos),
                    List.of(new CCClientboundCubeBlockChangesPacket.Change(SectionPos.sectionRelativePos(pos), state, null))));
        }

        private void receive(CCClientboundCubeBlockChangesPacket packet) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                CCClientboundCubeBlockChangesPacket.STREAM_CODEC.encode(buffer, packet);
                new CCClientboundCubeBlockChangesPacket.Handler().receive(CCClientboundCubeBlockChangesPacket.STREAM_CODEC.decode(buffer), context);
            } finally {
                buffer.release();
            }
        }
    }
}
