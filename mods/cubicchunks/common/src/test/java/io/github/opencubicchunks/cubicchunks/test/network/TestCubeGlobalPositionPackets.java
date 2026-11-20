package io.github.opencubicchunks.cubicchunks.test.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.storage.LevelData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Native item-component and player-spawn packets must retain the same full coordinates as their saved NBT. */
class TestCubeGlobalPositionPackets extends BaseTest {
    @ParameterizedTest
    @MethodSource("heights")
    void lodestoneItemAndComponentRetainFullPositionAndTrackedFlag(int y) {
        var position = GlobalPos.of(Level.OVERWORLD, new BlockPos(17, y, -31));
        for (boolean tracked : new boolean[] { false, true }) {
            var tracker = new LodestoneTracker(Optional.of(position), tracked);
            assertEquals(tracker, TestCubeVillagerMetadata.roundTrip(LodestoneTracker.STREAM_CODEC, tracker));
            ItemStack compass = new ItemStack(Items.COMPASS);
            compass.set(DataComponents.LODESTONE_TRACKER, tracker);
            var decoded = TestCubeVillagerMetadata.roundTrip(ItemStack.STREAM_CODEC, compass);
            assertTrue(decoded.is(Items.COMPASS));
            assertEquals(1, decoded.getCount());
            assertEquals(tracker, decoded.get(DataComponents.LODESTONE_TRACKER));
            assertEquals(tracker, LodestoneTracker.CODEC
                    .parse(NbtOps.INSTANCE, LodestoneTracker.CODEC.encodeStart(NbtOps.INSTANCE, tracker).getOrThrow()).getOrThrow());
        }
    }

    @ParameterizedTest
    @MethodSource("heights")
    void nativeLoginAndRespawnRetainLastDeathCoordinatesAcrossDimensions(int y) {
        var death = GlobalPos.of(Level.NETHER, new BlockPos(-19, y, 43));
        var type = VanillaRegistriesExtension.shared().lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        var spawn = new CommonPlayerSpawnInfo(type, Level.OVERWORLD, 123L, GameType.SURVIVAL, Optional.of(GameType.CREATIVE), false, false,
                Optional.of(death), 17, 63);
        var login = new ClientboundLoginPacket(41, false, Set.of(Level.OVERWORLD, Level.NETHER), 2, 2, 5, false, true, false, spawn, false, false);
        var decodedLogin = TestCubeVillagerMetadata.roundTrip(ClientboundLoginPacket.STREAM_CODEC, login);
        assertEquals(spawn, decodedLogin.commonPlayerSpawnInfo());
        assertEquals(Optional.of(death), decodedLogin.commonPlayerSpawnInfo().lastDeathLocation());
        var respawn = new ClientboundRespawnPacket(spawn, ClientboundRespawnPacket.KEEP_ALL_DATA);
        var decodedRespawn = TestCubeVillagerMetadata.roundTrip(ClientboundRespawnPacket.STREAM_CODEC, respawn);
        assertEquals(spawn, decodedRespawn.commonPlayerSpawnInfo());
        assertEquals(ClientboundRespawnPacket.KEEP_ALL_DATA, decodedRespawn.dataToKeep());
        var defaultSpawn = new ClientboundSetDefaultSpawnPositionPacket(LevelData.RespawnData.of(Level.OVERWORLD, death.pos(), 45, -12));
        assertEquals(defaultSpawn, TestCubeVillagerMetadata.roundTrip(ClientboundSetDefaultSpawnPositionPacket.STREAM_CODEC, defaultSpawn));
    }

    @ParameterizedTest
    @ValueSource(ints = { -2048, -64, 0, 96, 319, 2047 })
    void legalVanillaGlobalPositionsKeepExactNativeBytes(int y) {
        var position = GlobalPos.of(Level.END, new BlockPos(29_999_999, y, -29_999_999));
        var expected = new FriendlyByteBuf(Unpooled.buffer());
        var actual = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ResourceKey.streamCodec(Registries.DIMENSION).encode(expected, position.dimension());
            expected.writeLong(position.pos().asLong());
            GlobalPos.STREAM_CODEC.encode(actual, position);
            assertEquals(expected, actual, "Ordinary positions remain wire-compatible with vanilla");
            assertEquals(position, GlobalPos.STREAM_CODEC.decode(actual));
            assertEquals(0, actual.readableBytes());
        } finally {
            expected.release();
            actual.release();
        }
    }

    @Test
    void emptyTargetAndAbsentDeathLocationRetainNativeSemantics() {
        var tracker = new LodestoneTracker(Optional.empty(), true);
        assertEquals(tracker, TestCubeVillagerMetadata.roundTrip(LodestoneTracker.STREAM_CODEC, tracker));
        var type = VanillaRegistriesExtension.shared().lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END);
        var spawn = new CommonPlayerSpawnInfo(type, Level.END, 0, GameType.SURVIVAL, Optional.empty(), false, false, Optional.empty(), 0, 0);
        assertEquals(spawn, TestCubeVillagerMetadata.roundTrip(CommonPlayerSpawnInfo.STREAM_CODEC, spawn));
    }

    @Test
    void malformedExtendedGlobalPositionIsRejectedBeforeUse() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> GlobalPos.STREAM_CODEC.encode(buffer, GlobalPos.of(Level.OVERWORLD, new BlockPos(0, CubicBlockBounds.MAX + 1, 0))));
            buffer.clear();
            ResourceKey.streamCodec(Registries.DIMENSION).encode(buffer, Level.OVERWORLD);
            buffer.writeLong(Long.MIN_VALUE);
            buffer.writeInt(0);
            buffer.writeInt(CubicBlockBounds.MIN - 1);
            buffer.writeInt(0);
            assertThrows(IllegalArgumentException.class, () -> GlobalPos.STREAM_CODEC.decode(buffer));
            buffer.clear();
            ResourceKey.streamCodec(Registries.DIMENSION).encode(buffer, Level.OVERWORLD);
            buffer.writeLong(Long.MIN_VALUE);
            assertThrows(IndexOutOfBoundsException.class, () -> GlobalPos.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static IntStream heights() {
        return IntStream.of(96, 32000, -32000, 32000000, -32000000, CubicBlockBounds.MIN, CubicBlockBounds.MAX);
    }
}
