package io.github.opencubicchunks.cubicchunks.network;

import java.util.Optional;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.platform.Platform;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.resources.Identifier;

/** Wire-only serializer: decoded values are restored to the native optional-position accessor type. */
public final class CubeEntityDataSerializers {
    public static final EntityDataSerializer<Optional<BlockPos>> OPTIONAL_POSITION = EntityDataSerializer
            .forValueType(new StreamCodec<FriendlyByteBuf, Optional<BlockPos>>() {
                @Override public Optional<BlockPos> decode(FriendlyByteBuf buffer) {
                    return CubeActionCodec.readBoolean(buffer) ? Optional.of(CubeActionCodec.readPosition(buffer)) : Optional.empty();
                }

                @Override public void encode(FriendlyByteBuf buffer, Optional<BlockPos> value) {
                    buffer.writeBoolean(value.isPresent());
                    value.ifPresent(pos -> CubeActionCodec.writePosition(buffer, CubicBlockBounds.requireValid(pos)));
                }
            });

    private CubeEntityDataSerializers() {}

    public static void register() {
        // The loader adapter registers this serializer in its synchronized registry.
        Identifier id = Identifier.fromNamespaceAndPath(CubicChunks.MODID, "optional_cube_position");
        Platform.get().registerEntityDataSerializer(id, OPTIONAL_POSITION);
    }
}
