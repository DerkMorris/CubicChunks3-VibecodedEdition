package io.github.opencubicchunks.cubicchunks.network;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeHeightmap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/** Detached sections, local heightmaps and complete client-visible block-entity data. */
public class CCClientboundLevelCubePacketData {
    // Together with the 512 KiB entity budget, heightmaps and 32 KiB light this stays below the custom-payload limit.
    public static final int MAX_SECTION_BYTES = 262144;
    private static final int FORMAT_VERSION = 2;
    private static final int HASH_MULTIPLIER = 31;

    private final byte[] buffer;
    private final Map<Heightmap.Types, long[]> heightmaps;
    private final CubeBlockEntitySnapshot blockEntities;

    public static final StreamCodec<FriendlyByteBuf, CCClientboundLevelCubePacketData> STREAM_CODEC = new StreamCodec<>() {
        public CCClientboundLevelCubePacketData decode(FriendlyByteBuf buffer) {
            return new CCClientboundLevelCubePacketData(buffer);
        }

        public void encode(FriendlyByteBuf buffer, CCClientboundLevelCubePacketData data) {
            data.write(buffer);
        }
    };

    public CCClientboundLevelCubePacketData(LevelCube cube) {
        int size = calculateChunkSize(cube);
        validateSectionSize(size);
        buffer = new byte[size];
        FriendlyByteBuf sections = new FriendlyByteBuf(this.getWriteBuffer());
        try {
            extractChunkData(sections, cube);
        } finally {
            sections.release();
        }
        this.heightmaps = cube.cc_snapshotHeightmaps();
        this.blockEntities = CubeBlockEntitySnapshot.capture(cube);
    }

    public CCClientboundLevelCubePacketData(final FriendlyByteBuf byteBuf) {
        if (byteBuf.readVarInt() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported cube packet format");
        }
        int i = byteBuf.readVarInt();
        validateSectionSize(i);
        this.buffer = new byte[i];
        byteBuf.readBytes(this.buffer);
        this.heightmaps = new EnumMap<>(Heightmap.Types.class);
        for (Heightmap.Types type : Heightmap.Types.values()) {
            if (byteBuf.readVarInt() != CubeHeightmap.PACKED_LENGTH) {
                throw new IllegalArgumentException("Wrong cube heightmap packet length");
            }
            long[] heights = new long[CubeHeightmap.PACKED_LENGTH];
            for (int index = 0; index < heights.length; index++) {
                heights[index] = byteBuf.readLong();
            }
            CubeHeightmap.validateRawData(heights);
            this.heightmaps.put(type, heights);
        }
        this.blockEntities = CubeBlockEntitySnapshot.read(byteBuf);
    }

    public void write(final FriendlyByteBuf byteBuf) {
        byteBuf.writeVarInt(FORMAT_VERSION);
        byteBuf.writeVarInt(this.buffer.length);
        byteBuf.writeBytes(this.buffer);
        for (Heightmap.Types type : Heightmap.Types.values()) {
            long[] heights = this.heightmaps.get(type);
            byteBuf.writeVarInt(heights.length);
            for (long height : heights) {
                byteBuf.writeLong(height);
            }
        }
        this.blockEntities.write(byteBuf);
    }

    private static void validateSectionSize(int size) {
        if (size < 0 || size > MAX_SECTION_BYTES) {
            throw new IllegalArgumentException("Cube section data exceeds the packet budget");
        }
    }

    // TODO could maybe dasm copy these from ClientboundLevelChunkPacketData?
    private ByteBuf getWriteBuffer() {
        ByteBuf bytebuf = Unpooled.wrappedBuffer(this.buffer);
        bytebuf.writerIndex(0);
        return bytebuf;
    }

    private static int calculateChunkSize(LevelCube cube) {
        int i = 0;

        for (LevelChunkSection levelchunksection : cube.getSections()) {
            i += levelchunksection.getSerializedSize();
        }

        return i;
    }

    // TODO could dasm-copy this from ClientboundLevelChunkPacketData
    public static void extractChunkData(FriendlyByteBuf buffer, LevelCube cube) {
        for (LevelChunkSection levelchunksection : cube.getSections()) {
            levelchunksection.write(buffer);
        }

        if (buffer.writerIndex() != buffer.capacity()) {
            throw new IllegalStateException("Didn't fill cube buffer: expected " + buffer.capacity() + " bytes, got " + buffer.writerIndex());
        }
    }

    public FriendlyByteBuf getReadBuffer() {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(this.buffer).asReadOnly());
    }

    public CubeBlockEntitySnapshot getBlockEntities() {
        return blockEntities;
    }

    public Map<Heightmap.Types, long[]> getHeightmaps() {
        Map<Heightmap.Types, long[]> copy = new EnumMap<>(Heightmap.Types.class);
        this.heightmaps.forEach((type, heights) -> copy.put(type, heights.clone()));
        return copy;
    }

    // Implement .equals for unit testing
    @Override public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CCClientboundLevelCubePacketData that = (CCClientboundLevelCubePacketData) o;
        if (!Objects.deepEquals(buffer, that.buffer)) {
            return false;
        }
        for (Heightmap.Types type : Heightmap.Types.values()) {
            if (!Arrays.equals(this.heightmaps.get(type), that.heightmaps.get(type))) {
                return false;
            }
        }
        return blockEntities.equals(that.blockEntities);
    }

    @Override public int hashCode() {
        int hash = Arrays.hashCode(buffer);
        for (Heightmap.Types type : Heightmap.Types.values()) {
            hash = HASH_MULTIPLIER * hash + Arrays.hashCode(this.heightmaps.get(type));
        }
        return HASH_MULTIPLIER * hash + blockEntities.hashCode();
    }
}
