package io.github.opencubicchunks.cubicchunks.client.multiplayer;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockChangesPacket;
import io.github.opencubicchunks.cubicchunks.network.CubeBlockEntityUpdate;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.platform.ClientPlatform;
import io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueInput;

/** Applies authoritative cube data without column height checks or BlockPos/SectionPos long packing. */

public final class ClientCubeBlockChanges {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    private ClientCubeBlockChanges() {}

    public static void apply(CCClientboundCubeBlockChangesPacket payload, ClientPacketContext context) {
        ClientLevel level = context.client().level;
        if (level == null || context.player().level() != level || !((CanBeCubic) level).cc_isCubic()) {
            return;
        }
        ClientCubeCache cache = (ClientCubeCache) level.getChunkSource();
        var pos = payload.pos();
        LevelCube cube = cache.cc_getCube(pos.getX(), pos.getY(), pos.getZ(), false);
        if (cube == null) {
            return;
        }
        for (var change : payload.changes()) {
            BlockPos block = payload.blockPos(change);
            applyServerUpdate(level, cube, block, change.state(), change.blockEntity());
        }
    }

    public static void applyServerUpdate(ClientLevel level, LevelCube cube, BlockPos pos, BlockState state, @Nullable CubeBlockEntityUpdate entity) {
        ClientCubePredictions predictions = ((CubicClientLevel) level).cc_getCubePredictions();
        if (predictions == null || !predictions.update(cube, pos, state, entity)) {
            applyState(level, cube, pos, state, entity);
        }
    }

    public static boolean applyState(ClientLevel level, LevelCube cube, BlockPos pos, BlockState state, @Nullable CubeBlockEntityUpdate entity) {
        var previous = cube.setBlockState(pos, state, FLAGS);
        if (previous != null) {
            level.setBlocksDirty(pos, previous, state);
            level.sendBlockUpdated(pos, previous, state, FLAGS);
        }
        applyBlockEntity(level, cube, pos, state, entity);
        return previous != null;
    }

    private static void applyBlockEntity(ClientLevel level, LevelCube cube, BlockPos pos, BlockState state, @Nullable CubeBlockEntityUpdate update) {
        if (update == null) {
            return;
        }
        var entity = cube.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
        if (entity == null || entity.getType() != update.type()) {
            return;
        }
        try (var reporter = new ProblemReporter.ScopedCollector(entity.problemPath(), LogUtils.getLogger())) {
            entity.loadWithComponents(TagValueInput.create(reporter, level.registryAccess(), update.tag()));
        }
        // Same-state NBT changes also affect rendering, e.g. sign text and banners.
        level.sendBlockUpdated(pos, state, state, FLAGS);
    }
}
