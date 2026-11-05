package io.github.opencubicchunks.cubicchunks.network;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeBlockChanges;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import io.github.opencubicchunks.cubicchunks.platform.ClientPlatform;
import io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** One detached section delta. Coordinates are a cube key, an XYZ section index and local 12-bit block positions. */
public record CCClientboundCubeBlockChangesPacket(CubePos pos, int sectionIndex, List<Change> changes) implements CustomPacketPayload {

    private static final int FORMAT_VERSION = 1;
    public static final int MAX_CHANGES = 4096;
    public static final int MAX_TAG_BYTES = 262144;
    public static final Type<CCClientboundCubeBlockChangesPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "cube_block_changes"));
    public static final StreamCodec<FriendlyByteBuf, CCClientboundCubeBlockChangesPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override public CCClientboundCubeBlockChangesPacket decode(FriendlyByteBuf buffer) {
            if (buffer.readVarInt() != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported cube block changes format");
            }
            long packed = buffer.readLong();
            CubePos pos = CubePos.from(packed);
            if (pos.asLong() != packed) {
                throw new IllegalArgumentException("Noncanonical cube block changes position");
            }
            int section = buffer.readUnsignedByte();
            validateSection(section);
            int count = buffer.readVarInt();
            validateCount(count);
            List<Change> changes = new ArrayList<>(count);
            int remainingTagBytes = MAX_TAG_BYTES;
            for (int index = 0; index < count; index++) {
                Change change = readChange(buffer, remainingTagBytes);
                remainingTagBytes -= change.blockEntity == null ? 0 : change.blockEntity.size();
                changes.add(change);
            }
            return new CCClientboundCubeBlockChangesPacket(pos, section, changes);
        }

        @Override public void encode(FriendlyByteBuf buffer, CCClientboundCubeBlockChangesPacket packet) {
            buffer.writeVarInt(FORMAT_VERSION);
            buffer.writeLong(packet.pos.asLong());
            buffer.writeByte(packet.sectionIndex);
            buffer.writeVarInt(packet.changes.size());
            for (Change change : packet.changes) {
                buffer.writeShort(change.localPos);
                buffer.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(change.state));
                buffer.writeBoolean(change.blockEntity != null);
                if (change.blockEntity != null) {
                    change.blockEntity.write(buffer);
                }
            }
        }
    };

    public CCClientboundCubeBlockChangesPacket {
        Objects.requireNonNull(pos, "pos");
        if (!CubePos.from(pos.asLong()).equals(pos)) {
            throw new IllegalArgumentException("Cube block changes position exceeds the supported range");
        }
        validateSection(sectionIndex);
        changes = List.copyOf(changes);
        validateCount(changes.size());
        validateChanges(changes);
    }

    private static void validateSection(int section) {
        if (section < 0 || section >= CubicConstants.SECTION_COUNT) {
            throw new IllegalArgumentException("Invalid cube section index");
        }
    }

    private static void validateCount(int count) {
        if (count < 1 || count > MAX_CHANGES) {
            throw new IllegalArgumentException("Invalid cube section change count");
        }
    }

    private static void validateChanges(List<Change> changes) {
        Set<Integer> positions = new HashSet<>();
        int tagBytes = 0;
        for (Change change : changes) {
            if (!positions.add(change.localPos)) {
                throw new IllegalArgumentException("Duplicate cube block change");
            }
            tagBytes += change.blockEntity == null ? 0 : change.blockEntity.size();
            if (tagBytes > MAX_TAG_BYTES) {
                throw new IllegalArgumentException("Cube block entity updates exceed the packet budget");
            }
        }
    }

    private static Change readChange(FriendlyByteBuf buffer, int remainingTagBytes) {
        int local = buffer.readUnsignedShort();
        int stateId = buffer.readVarInt();
        BlockState state = Block.BLOCK_STATE_REGISTRY.byId(stateId);
        if (stateId < 0 || state == null) {
            throw new IllegalArgumentException("Unknown cube block state ID");
        }
        int present = buffer.readUnsignedByte();
        if (present > 1) {
            throw new IllegalArgumentException("Invalid block entity presence flag");
        }
        return new Change(local, state, present == 0 ? null : CubeBlockEntityUpdate.read(buffer, remainingTagBytes));
    }

    /** Capture only the normal client update tag; inventories and other save-only data must not be exposed. */
    public static CCClientboundCubeBlockChangesPacket capture(LevelCube cube, int sectionIndex, ShortSet changes) {
        List<CCClientboundCubeBlockChangesPacket> packets = new ArrayList<>();
        forEachPacket(cube, sectionIndex, changes, packets::add);
        if (packets.size() != 1) {
            throw new IllegalArgumentException("Section requires multiple block change packets");
        }
        return packets.getFirst();
    }

    /** Stream bounded batches; the caller retains the dirty section until every batch has been sent. */
    public static void forEachPacket(LevelCube cube, int sectionIndex, ShortSet changes, Consumer<CCClientboundCubeBlockChangesPacket> consumer) {
        validateSection(sectionIndex);
        validateCount(changes.size());
        List<Change> entries = new ArrayList<>(changes.size());
        int tagBytes = 0;
        var section = Coords.sectionPosByIndex(cube.cc_getCubePos(), sectionIndex);
        for (short local : changes) {
            if (local < 0 || local >= MAX_CHANGES) {
                throw new IllegalArgumentException("Invalid local block position");
            }
            BlockPos block = section.relativeToBlockPos(local);
            BlockState state = cube.getBlockState(block);
            Change change = new Change(local, state, captureBlockEntity(cube, block, state));
            int bytes = change.blockEntity == null ? 0 : change.blockEntity.size();
            if (tagBytes + bytes > MAX_TAG_BYTES) {
                consumer.accept(new CCClientboundCubeBlockChangesPacket(cube.cc_getCubePos(), sectionIndex, entries));
                entries.clear();
                tagBytes = 0;
            }
            entries.add(change);
            tagBytes += bytes;
        }
        consumer.accept(new CCClientboundCubeBlockChangesPacket(cube.cc_getCubePos(), sectionIndex, entries));
    }

    public static @Nullable CubeBlockEntityUpdate captureBlockEntity(LevelCube cube, BlockPos pos, BlockState state) {
        if (!state.hasBlockEntity()) {
            return null;
        }
        var entity = cube.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
        var packet = entity == null ? null : entity.getUpdatePacket();
        if (packet == null) {
            return null;
        }
        if (!(packet instanceof ClientboundBlockEntityDataPacket update) || !update.getPos().equals(pos)) {
            throw new IllegalArgumentException("Unsupported block entity update packet at " + pos);
        }
        return CubeBlockEntityUpdate.capture(update.getType(), update.getTag());
    }

    public BlockPos blockPos(Change change) {
        return Coords.sectionPosByIndex(this.pos, this.sectionIndex).relativeToBlockPos((short) change.localPos);
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Change(int localPos, BlockState state, @Nullable CubeBlockEntityUpdate blockEntity) {
        public Change {
            Objects.requireNonNull(state, "state");
            if (localPos < 0 || localPos >= MAX_CHANGES || Block.BLOCK_STATE_REGISTRY.getId(state) < 0) {
                throw new IllegalArgumentException("Invalid cube block change");
            }
            if (blockEntity != null && (!state.hasBlockEntity() || !blockEntity.type().isValid(state))) {
                throw new IllegalArgumentException("Block entity type does not match the changed block");
            }
        }
    }

    public static class Handler {
        public void receive(CCClientboundCubeBlockChangesPacket payload, ClientPacketContext context) {
            ClientCubeBlockChanges.apply(payload, context);
        }
    }
}
