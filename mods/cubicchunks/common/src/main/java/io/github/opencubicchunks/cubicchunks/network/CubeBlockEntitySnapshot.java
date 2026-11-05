package io.github.opencubicchunks.cubicchunks.network;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Complete client-visible entity data: a section/local position and normal initial update tag for every entity block. */
public record CubeBlockEntitySnapshot(List<Entry> entries) {
    private static final int LOCAL_BITS = 12;
    private static final int LOCAL_MASK = (1 << LOCAL_BITS) - 1;
    public static final int MAX_ENTRIES = CubicConstants.SECTION_COUNT << LOCAL_BITS;
    public static final int MAX_BYTES = 524288;

    public CubeBlockEntitySnapshot {
        entries = List.copyOf(entries);
        validateCount(entries.size());
        BitSet positions = new BitSet(MAX_ENTRIES);
        int bytes = VarInt.getByteSize(entries.size());
        for (Entry entry : entries) {
            if (positions.get(entry.position)) {
                throw new IllegalArgumentException("Duplicate cube block entity position");
            }
            positions.set(entry.position);
            bytes += Short.BYTES + entry.data.encodedSize();
            validateBudget(bytes);
        }
    }

    public static CubeBlockEntitySnapshot capture(LevelCube cube) {
        List<BlockPos> positions = new ArrayList<>();
        cube.findBlocks(BlockState::hasBlockEntity, (pos, state) -> positions.add(pos.immutable()));
        List<Entry> entries = new ArrayList<>(positions.size());
        int bytes = 0;
        for (BlockPos pos : positions) {
            var entity = cube.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
            if (entity == null || !entity.getBlockPos().equals(pos) || !entity.getType().isValid(cube.getBlockState(pos))) {
                throw new IllegalArgumentException("Missing or mismatched block entity in full cube snapshot at " + pos);
            }
            // Initial data is distinct from a change packet; entities with no getUpdatePacket still belong in the snapshot.
            CubeBlockEntityUpdate data = CubeBlockEntityUpdate.capture(entity.getType(), entity.getUpdateTag(cube.getLevel().registryAccess()));
            bytes += Short.BYTES + data.encodedSize();
            validateBudget(bytes + VarInt.getByteSize(entries.size() + 1));
            entries.add(new Entry((Coords.blockToIndex(pos) << LOCAL_BITS) | SectionPos.sectionRelativePos(pos), data));
        }
        return new CubeBlockEntitySnapshot(entries);
    }

    public static CubeBlockEntitySnapshot read(FriendlyByteBuf buffer) {
        int start = buffer.readerIndex();
        int count = buffer.readVarInt();
        validateCount(count);
        List<Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int position = buffer.readUnsignedShort();
            int remaining = MAX_BYTES - (buffer.readerIndex() - start);
            if (remaining < 0) {
                throw new IllegalArgumentException("Cube block entity snapshot exceeds its budget");
            }
            entries.add(new Entry(position, CubeBlockEntityUpdate.read(buffer, remaining)));
            validateBudget(buffer.readerIndex() - start);
        }
        return new CubeBlockEntitySnapshot(entries);
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buffer.writeShort(entry.position);
            entry.data.write(buffer);
        }
    }

    public void forEach(CubePos cube, ClientboundLevelChunkPacketData.BlockEntityTagOutput output) {
        for (Entry entry : entries) {
            output.accept(entry.blockPos(cube), entry.data.type(), entry.data.tag());
        }
    }

    private static void validateCount(int count) {
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid cube block entity count");
        }
    }

    private static void validateBudget(int bytes) {
        if (bytes > MAX_BYTES) {
            throw new IllegalArgumentException("Cube block entity snapshot exceeds its budget");
        }
    }

    public record Entry(int position, CubeBlockEntityUpdate data) {
        public Entry {
            if (position < 0 || position >= MAX_ENTRIES) {
                throw new IllegalArgumentException("Invalid cube-local block entity position");
            }
            Objects.requireNonNull(data, "data");
        }

        public BlockPos blockPos(CubePos cube) {
            return Coords.sectionPosByIndex(cube, position >> LOCAL_BITS).relativeToBlockPos((short) (position & LOCAL_MASK));
        }
    }
}
