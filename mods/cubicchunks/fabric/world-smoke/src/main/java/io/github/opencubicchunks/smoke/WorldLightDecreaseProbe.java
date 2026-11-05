package io.github.opencubicchunks.smoke;

import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Native placement/destruction, with authoritative light delayed while provisional decreases remain visible. */
final class WorldLightDecreaseProbe {
    private final int roof = Integer.getInteger("cubicchunks.smoke.lightHeight", 32000);
    private final BlockPos hole = new BlockPos(8, roof, 8);
    private final BlockPos first = new BlockPos(7, roof - 4, 8);
    private final BlockPos second = new BlockPos(10, roof - 4, 8);
    private CompletableFuture<Void> setup;
    private CompletableFuture<Boolean> confirmed;
    private int phase = -1;
    private int phaseTicks;
    private int pendingTicks;
    private int totalPending;
    private long maxActionNanos;
    private boolean awaitingTorch;
    private boolean screenshot;

    void tick(Minecraft client) {
        if (setup == null) { prepare(client); return; }
        if (!setup.isDone()) { return; }
        setup.join();
        var cache = (ClientCubeCache) client.level.getChunkSource();
        if (phase == -1) {
            if (!ready(cache) || !matches(client.level, -1) || !client.player.getMainHandItem().is(Items.STONE)
                    || client.levelRenderer.visibleSections().stream().noneMatch(section -> Math.abs((long) section.getRenderOrigin().getY() - roof) < 48
                    && section.getSectionMesh().hasRenderableLayers())) { return; }
            act(client, 0);
        }
        if (!matches(client.level, phase)) {
            throw new IllegalStateException("Predicted decrease changed before server light: phase=" + phase + " " + values(client.level));
        }
        if (WorldLightDelay.active() && !ready(cache)) { pendingTicks++; }
        if (++phaseTicks == 10 && phase == 1) {
            Screenshot.grab(client.gameDirectory, "light-prediction-decrease-" + roof + ".png", client.gameRenderer.mainRenderTarget(), 1, message -> {
                screenshot = true;
                System.out.println("CC_LIGHT_DECREASE_SCREENSHOT " + message.getString());
            });
        }
        if (WorldLightDelay.active() || !ready(cache)) { return; }
        if (confirmed == null) {
            int captured = phase;
            var server = client.getSingleplayerServer();
            confirmed = CompletableFuture.supplyAsync(() -> matches(server.overworld(), captured), server);
            return;
        }
        if (!confirmed.isDone()) { return; }
        if (!confirmed.join()) { confirmed = null; return; }
        if (pendingTicks == 0) { throw new IllegalStateException("No delayed-light interval for phase " + phase); }
        if (phase == 2 && !client.player.getMainHandItem().is(Items.TORCH)) {
            if (!awaitingTorch) {
                awaitingTorch = true;
                var server = client.getSingleplayerServer();
                server.execute(() -> server.getPlayerList().getPlayers().getFirst().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH, 64)));
            }
            return;
        }
        System.out.println("CC_LIGHT_DECREASE_PHASE phase=" + phase + " pendingTicks=" + pendingTicks + " " + values(client.level));
        totalPending += pendingTicks;
        if (phase == 4) {
            if (!screenshot) { throw new IllegalStateException("Missing render evidence"); }
            System.out.println("CC_LIGHT_DECREASE_COMPLETE phases=5 pendingTicks=" + totalPending + " maxActionMicros=" + maxActionNanos / 1000);
            client.stop();
        } else {
            act(client, phase + 1);
        }
    }

    private void act(Minecraft client, int next) {
        phase = next;
        phaseTicks = 0;
        pendingTicks = 0;
        confirmed = null;
        WorldLightDelay.arm();
        client.player.setYRot(180);
        client.player.setXRot(15);
        long start = System.nanoTime();
        boolean action;
        if (phase == 0) {
            action = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(hole.east()).add(-0.5, 0, 0), Direction.WEST, hole.east(), false)).consumesAction();
        } else if (phase == 3) {
            action = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(first.below()).add(0, 0.5, 0), Direction.UP, first.below(), false)).consumesAction();
        } else {
            action = client.gameMode.startDestroyBlock(phase == 1 ? first : phase == 2 ? second : hole, Direction.UP);
        }
        if (!action || !matches(client.level, phase)) {
            throw new IllegalStateException("Native action did not predict light immediately: phase=" + phase + " " + values(client.level));
        }
        maxActionNanos = Math.max(maxActionNanos, System.nanoTime() - start);
    }

    private boolean matches(Level level, int stage) {
        boolean firstPresent = stage <= 0 || stage >= 3;
        boolean secondPresent = stage <= 1;
        int firstLight = firstPresent ? 14 : secondPresent ? 11 : 0;
        int secondLight = secondPresent ? 14 : firstPresent ? 11 : 0;
        return level.getBlockState(hole).is(stage == -1 || stage == 4 ? Blocks.AIR : Blocks.STONE)
                && level.getBlockState(first).is(firstPresent ? Blocks.TORCH : Blocks.AIR)
                && level.getBlockState(second).is(secondPresent ? Blocks.TORCH : Blocks.AIR)
                && level.getBrightness(LightLayer.BLOCK, first) == firstLight && level.getBrightness(LightLayer.BLOCK, second) == secondLight
                && level.getBrightness(LightLayer.SKY, hole.below()) == (stage == -1 || stage == 4 ? 15 : 0);
    }

    private String values(Level level) {
        return "sky=" + level.getBrightness(LightLayer.SKY, hole.below()) + " first=" + level.getBlockState(first)
                + "/" + level.getBrightness(LightLayer.BLOCK, first) + " second=" + level.getBlockState(second)
                + "/" + level.getBrightness(LightLayer.BLOCK, second) + " lid=" + level.getBlockState(hole);
    }

    private boolean ready(ClientCubeCache cache) {
        return cache.cc_isCubeLightReady(CubePos.from(hole)) && cache.cc_isCubeLightReady(CubePos.from(first));
    }

    private void prepare(Minecraft client) {
        var server = client.getSingleplayerServer();
        setup = CompletableFuture.runAsync(() -> {
            var level = server.overworld();
            for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(5, roof - 5, 5), new BlockPos(12, roof, 12))) {
                CubePos cube = CubePos.from(pos);
                ((CubicLevelReader) level).cc_getCube(cube.getX(), cube.getY(), cube.getZ(), ChunkStatus.FULL, true);
                boolean shell = pos.getX() == 5 || pos.getX() == 12 || pos.getZ() == 5 || pos.getZ() == 12 || pos.getY() == roof - 5 || pos.getY() == roof;
                level.setBlockAndUpdate(pos, (shell && !pos.equals(hole) ? Blocks.STONE : Blocks.AIR).defaultBlockState());
            }
            level.setBlockAndUpdate(first, Blocks.TORCH.defaultBlockState());
            level.setBlockAndUpdate(second, Blocks.TORCH.defaultBlockState());
            var player = server.getPlayerList().getPlayers().getFirst();
            player.teleportTo(8.5, roof - 4, 10.5);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 64));
        }, server);
    }
}
