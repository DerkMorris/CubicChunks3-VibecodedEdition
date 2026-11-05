package io.github.opencubicchunks.cubicchunks.client.multiplayer;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockEntityDataPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeOpenSignEditorPacket;
import io.github.opencubicchunks.cubicchunks.platform.ClientPlatform;
import io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext;
import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;

public final class ClientCubeBlockEntityEditing {
    private ClientCubeBlockEntityEditing() {}

    private static @Nullable BlockEntity find(BlockPos pos, ClientPacketContext context) {
        var level = context.client().level;
        if (level == null || context.player().level() != level || !((CanBeCubic) level).cc_isCubic()) {
            return null;
        }
        CubePos cubePos = CubePos.from(pos);
        var cube = ((ClientCubeCache) level.getChunkSource()).cc_getCube(cubePos.getX(), cubePos.getY(), cubePos.getZ(), false);
        return cube == null ? null : cube.getBlockEntities().get(pos);
    }

    public static void openSign(CCClientboundCubeOpenSignEditorPacket packet, ClientPacketContext context) {
        if (find(packet.pos(), context) instanceof SignBlockEntity sign) {
            context.player().openTextEdit(sign, packet.slot());
        }
    }

    public static void update(CCClientboundCubeBlockEntityDataPacket packet, ClientPacketContext context) {
        BlockEntity entity = find(packet.pos(), context);
        if (entity == null || entity.getType() != packet.data().type() || !entity.getType().isValid(entity.getBlockState())) {
            return;
        }
        var level = context.client().level;
        try (var reporter = new ProblemReporter.ScopedCollector(entity.problemPath(), LogUtils.getLogger())) {
            entity.loadWithComponents(TagValueInput.create(reporter, level.registryAccess(), packet.data().tag()));
        }
        level.sendBlockUpdated(packet.pos(), entity.getBlockState(), entity.getBlockState(), Block.UPDATE_ALL);
        if (entity instanceof CommandBlockEntity && context.client().gui.screen() instanceof CommandBlockEditScreen screen) {
            screen.updateGui();
        }
    }
}
