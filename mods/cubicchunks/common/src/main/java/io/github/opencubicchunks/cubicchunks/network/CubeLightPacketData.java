package io.github.opencubicchunks.cubicchunks.network;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.LightLayer;

/** Fixed cube-section layout: two complete layers, with no vanilla column masks or unbounded array counts. */
public final class CubeLightPacketData {
    private static final int BYTES_PER_SECTION = 2048;

    private CubeLightPacketData() {}

    public static @Nullable CubeLightView read(FriendlyByteBuf buffer, CubePos pos) {
        int present = buffer.readUnsignedByte();
        if (present == 0) {
            return null;
        }
        if (present != 1) {
            throw new IllegalArgumentException("Invalid cube light presence flag");
        }
        return CubeLightView.fromExact(pos, readLayer(buffer), readLayer(buffer));
    }

    public static void write(FriendlyByteBuf buffer, @Nullable CubeLightView light) {
        if (light == null) {
            buffer.writeByte(0);
            return;
        }
        if (!light.isReady()) {
            throw new IllegalArgumentException("Cannot transmit uncertain cube light as exact values");
        }
        buffer.writeByte(1);
        writeLayer(buffer, light, LightLayer.BLOCK);
        writeLayer(buffer, light, LightLayer.SKY);
    }

    public static void validateEpoch(long epoch) {
        if (epoch < 0) {
            throw new IllegalArgumentException("Cube light epoch must be nonnegative");
        }
    }

    private static byte[][] readLayer(FriendlyByteBuf buffer) {
        if (buffer.readVarInt() != CubicConstants.SECTION_COUNT) {
            throw new IllegalArgumentException("Wrong cube light section count");
        }
        byte[][] sections = new byte[CubicConstants.SECTION_COUNT][];
        for (int index = 0; index < sections.length; index++) {
            if (buffer.readVarInt() != BYTES_PER_SECTION) {
                throw new IllegalArgumentException("Wrong cube light section length");
            }
            sections[index] = new byte[BYTES_PER_SECTION];
            buffer.readBytes(sections[index]);
        }
        return sections;
    }

    private static void writeLayer(FriendlyByteBuf buffer, CubeLightView light, LightLayer layer) {
        buffer.writeVarInt(CubicConstants.SECTION_COUNT);
        for (int index = 0; index < CubicConstants.SECTION_COUNT; index++) {
            byte[] section = light.packedLayer(layer, index).orElseThrow(() -> new IllegalArgumentException("Missing exact cube light section"));
            if (section.length != BYTES_PER_SECTION) {
                throw new IllegalArgumentException("Wrong cube light section length");
            }
            buffer.writeVarInt(section.length);
            buffer.writeBytes(section);
        }
    }
}
