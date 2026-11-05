package io.github.opencubicchunks.cubicchunks.network;

import static io.github.opencubicchunks.cubicchunks.network.MiscStreamCodecs.CUBE_POS_STREAM_CODEC;

import java.util.Objects;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubicClientLevel;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightPacketSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import io.github.opencubicchunks.cubicchunks.platform.ClientPlatform;
import io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Complete light is bound to the full block snapshot in this packet; unavailable light never means an all-zero field. */
public record CCClientboundLevelCubeWithLightPacket(
        CubePos pos, CCClientboundLevelCubePacketData cubeData, long lightEpoch, @Nullable CubeLightView light, boolean scopedLight
) implements CustomPacketPayload {

    private static final int FORMAT_VERSION = 4;
    public static final Type<CCClientboundLevelCubeWithLightPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "level_cube_with_light"));

    public static final StreamCodec<FriendlyByteBuf, CCClientboundLevelCubeWithLightPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override public CCClientboundLevelCubeWithLightPacket decode(FriendlyByteBuf buffer) {
            if (buffer.readVarInt() != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported full cube packet format");
            }
            long packed = buffer.readLong();
            CubePos pos = CubePos.from(packed);
            if (pos.asLong() != packed) {
                throw new IllegalArgumentException("Noncanonical full cube position");
            }
            long epoch = buffer.readLong();
            CubeLightPacketData.validateEpoch(epoch);
            CCClientboundLevelCubePacketData blocks = CCClientboundLevelCubePacketData.STREAM_CODEC.decode(buffer);
            CubeLightView light = CubeLightPacketData.read(buffer, pos);
            int scoped = buffer.readUnsignedByte();
            if (scoped > 1) {
                throw new IllegalArgumentException("Invalid scoped-light flag");
            }
            return new CCClientboundLevelCubeWithLightPacket(pos, blocks, epoch, light, scoped == 1);
        }

        @Override public void encode(FriendlyByteBuf buffer, CCClientboundLevelCubeWithLightPacket packet) {
            buffer.writeVarInt(FORMAT_VERSION);
            CUBE_POS_STREAM_CODEC.encode(buffer, packet.pos);
            buffer.writeLong(packet.lightEpoch);
            CCClientboundLevelCubePacketData.STREAM_CODEC.encode(buffer, packet.cubeData);
            CubeLightPacketData.write(buffer, packet.light);
            buffer.writeBoolean(packet.scopedLight);
        }
    };

    public CCClientboundLevelCubeWithLightPacket {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(cubeData, "cubeData");
        if (!CubePos.from(pos.asLong()).equals(pos)) {
            throw new IllegalArgumentException("Cube position exceeds the packet coordinate range");
        }
        CubeLightPacketData.validateEpoch(lightEpoch);
        if (light != null && (!light.pos().equals(pos) || !light.isReady())) {
            throw new IllegalArgumentException("Packet light must be complete and belong to its cube");
        }
    }

    public CCClientboundLevelCubeWithLightPacket(CubePos pos, CCClientboundLevelCubePacketData data, long epoch, @Nullable CubeLightView light) {
        this(pos, data, epoch, light, false);
    }

    /** Unlit convenience snapshot; production sends use the current server lighting export. */
    public CCClientboundLevelCubeWithLightPacket(LevelCube cube) {
        this(cube.cc_getCubePos(), new CCClientboundLevelCubePacketData(cube), 0, null);
    }

    /** Construct on the owning server thread immediately after exporting this cube's current light. */
    public CCClientboundLevelCubeWithLightPacket(LevelCube cube, CubeLightPacketSnapshot snapshot) {
        this(cube.cc_getCubePos(), new CCClientboundLevelCubePacketData(cube), snapshot.epoch(), snapshot.light(), snapshot.scoped());
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class Handler {
        public void receive(CCClientboundLevelCubeWithLightPacket payload, ClientPacketContext context) {
            ClientLevel level = (ClientLevel) context.player().level();
            Minecraft client = context.client();
            if (client.level != level || !((CanBeCubic) level).cc_isCubic()) {
                return;
            }
            ClientCubeCache cache = (ClientCubeCache) level.getChunkSource();
            var predictions = ((CubicClientLevel) level).cc_getCubePredictions();
            var pending = predictions == null ? null : predictions.beforeFullSnapshot(payload.pos);
            LevelCube cube;
            FriendlyByteBuf blocks = payload.cubeData.getReadBuffer();
            try {
                cube = cache.cc_replaceWithPacketData(payload.pos.getX(), payload.pos.getY(), payload.pos.getZ(), blocks,
                        payload.cubeData.getHeightmaps(), output -> payload.cubeData.getBlockEntities().forEach(payload.pos, output));
            } finally {
                blocks.release();
            }
            if (cube == null) {
                return;
            }
            if (payload.light != null) {
                expectLight(payload, cache, cube);
            }
            long revision = cube.cc_getLightRevision();
            long generation = cache.cc_lightGeneration();
            // This is already an immutable, solved field. Install first light before the first mesh can sample the new cube.
            if (payload.scopedLight && payload.light != null && cache.cc_getVisibleCubeLightView(payload.pos) == null) {
                applyLight(payload, client, level, cache, cube, revision, generation);
            } else {
                level.queueLightUpdate(() -> applyLight(payload, client, level, cache, cube, revision, generation));
            }
            if (predictions != null) {
                predictions.afterFullSnapshot(level, cube, pending);
            }
        }

        private static void expectLight(CCClientboundLevelCubeWithLightPacket payload, ClientCubeCache cache, LevelCube cube) {
            if (payload.scopedLight) {
                cache.cc_expectScopedCubeLight(cube, payload.lightEpoch);
            } else {
                cache.cc_expectCubeLight(cube, payload.lightEpoch);
            }
        }

        private static void applyLight(
                CCClientboundLevelCubeWithLightPacket payload, Minecraft client, ClientLevel level, ClientCubeCache cache, LevelCube cube,
                long revision, long generation
        ) {
            if (client.level != level || cache.cc_getCube(payload.pos.getX(), payload.pos.getY(), payload.pos.getZ(), false) != cube
                    || cube.cc_getLightRevision() != revision) {
                return;
            }
            if (payload.scopedLight) {
                cache.cc_applyScopedCubeLight(cube, revision, payload.lightEpoch, payload.light, generation);
            } else {
                cache.cc_applyCubeLight(cube, revision, payload.lightEpoch, payload.light, generation);
            }
        }
    }
}
