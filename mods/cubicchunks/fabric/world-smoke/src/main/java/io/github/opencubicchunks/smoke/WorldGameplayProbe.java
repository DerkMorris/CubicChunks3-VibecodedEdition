package io.github.opencubicchunks.smoke;

import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.Fluids;

/** Real server-tick progression and client transport; no direct calls to block, fluid, or block-entity tick methods. */
final class WorldGameplayProbe {
    private static final int[] HEIGHTS = { 96, 32000, -32000 };
    private volatile Scene scene;
    private volatile Snapshot snapshot;
    private CompletableFuture<Void> setup;
    private CompletableFuture<Void> action;
    private int phase;
    private int waiting;
    private boolean started;
    private boolean clientSawLitFurnace;
    private boolean turningOff;
    private boolean screenshot;
    private volatile boolean screenshotDone;
    private boolean cleaningEntities;

    WorldGameplayProbe() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Scene current = scene;
            if (current != null && current.running && current.level == server.overworld()) {
                current.ticks++;
                current.sawLitFurnace |= current.level.getBlockState(current.furnace()).getValue(BlockStateProperties.LIT);
                snapshot = current.capture();
                if (current.ticks > 1200) {
                    throw new IllegalStateException("Gameplay mechanics did not finish: " + snapshot);
                }
            }
        });
    }

    void tick(Minecraft client) {
        if (setup == null) { prepare(client); return; }
        if (!setup.isDone()) { return; }
        setup.join();
        Scene current = scene;
        if (current == null || current.height != HEIGHTS[phase]) { return; }
        if (action != null && action.isDone()) { action.join(); }
        if (!started) {
            if (!client.level.getBlockState(current.furnace()).is(Blocks.FURNACE)
                    || !client.level.getBlockState(current.lamp()).is(Blocks.REDSTONE_LAMP)
                    || Math.abs(client.player.getY() - current.height) > 8) {
                if (++waiting > 1200) { throw new IllegalStateException("Gameplay scene did not reach client at " + current.height); }
                return;
            }
            action = CompletableFuture.runAsync(current::start, client.getSingleplayerServer());
            started = true;
        }
        if (client.level.getBlockState(current.furnace()).is(Blocks.FURNACE)) {
            clientSawLitFurnace |= client.level.getBlockState(current.furnace()).getValue(BlockStateProperties.LIT);
        }
        Snapshot observed = snapshot;
        if (observed == null || observed.height != current.height) { return; }
        if (observed.ticks % 100 == 0 && waiting != observed.ticks) {
            waiting = observed.ticks;
            System.out.println("CC_GAMEPLAY_PROGRESS " + observed + " clientFurnaceLit=" + clientSawLitFurnace);
        }
        if (!turningOff) {
            if (!observed.progressed() || !clientSawLitFurnace || !current.visibleResult(client.level, true)) { return; }
            System.out.println("CC_GAMEPLAY_ON " + observed + " clientConfirmed=true");
            turningOff = true;
            action = CompletableFuture.runAsync(() -> current.power(false), client.getSingleplayerServer());
            return;
        }
        if (!action.isDone() || observed.powered || observed.lamp || !current.visibleResult(client.level, false)) { return; }
        if (!screenshot) {
            screenshot = true;
            client.player.setYRot(180);
            client.player.setXRot(20);
            Screenshot.grab(client.gameDirectory, "gameplay-" + current.height + ".png", client.gameRenderer.mainRenderTarget(), 1,
                    message -> { screenshotDone = true; System.out.println("CC_GAMEPLAY_SCREENSHOT " + message.getString()); });
            return;
        }
        if (!screenshotDone) { return; }
        if (!cleaningEntities) {
            cleaningEntities = true;
            action = CompletableFuture.runAsync(current.entities::close, client.getSingleplayerServer());
            return;
        }
        if (!action.isDone()) { return; }
        action.join();
        current.running = false;
        System.out.println("CC_GAMEPLAY_PHASE height=" + current.height + " ticks=" + observed.ticks
                + " redstoneOnOff=true fluidAcrossSeam=true randomGrowth=true smeltedAndTransferred=" + observed.ingots
                + " entityMechanics=" + observed.entities + " clientConfirmed=true testBypass=" + CubicChunks.IS_IN_TEST);
        if (++phase == HEIGHTS.length) {
            System.out.println("CC_GAMEPLAY_COMPLETE heights=3 redstone=true fluids=true randomTicks=true furnaceHopper=true entityMechanics=true");
            client.stop();
        } else {
            setup = null; action = null; started = false; turningOff = false; clientSawLitFurnace = false;
            screenshot = false; screenshotDone = false; cleaningEntities = false; waiting = 0;
        }
    }

    private void prepare(Minecraft client) {
        int height = HEIGHTS[phase];
        var server = client.getSingleplayerServer();
        setup = CompletableFuture.runAsync(() -> {
            if (CubicChunks.IS_IN_TEST) { throw new IllegalStateException("Gameplay probe requires production tick paths"); }
            scene = null; snapshot = null;
            ServerLevel level = server.overworld();
            level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 128, server);
            for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(24, height - 3, 0), new BlockPos(39, height + 4, 15))) {
                CubePos cube = CubePos.from(pos);
                ((CubicLevelReader) level).cc_getCube(cube.getX(), cube.getY(), cube.getZ(), ChunkStatus.FULL, true);
                boolean shell = pos.getY() == height - 3 || pos.getY() == height + 4
                        || pos.getX() == 24 || pos.getX() == 39 || pos.getZ() == 0 || pos.getZ() == 15;
                level.setBlock(pos, (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
            }
            Scene next = new Scene(level, height);
            for (int x = 29; x <= 33; x++) { level.setBlockAndUpdate(new BlockPos(x, height - 2, 3), Blocks.STONE.defaultBlockState()); }
            level.setBlockAndUpdate(next.lever(), Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
            level.setBlockAndUpdate(next.repeater(), Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
            level.setBlockAndUpdate(next.lamp(), Blocks.REDSTONE_LAMP.defaultBlockState());
            level.setBlockAndUpdate(next.chest(), Blocks.CHEST.defaultBlockState());
            level.setBlockAndUpdate(next.hopper(), Blocks.HOPPER.defaultBlockState());
            level.setBlockAndUpdate(next.furnace(), Blocks.FURNACE.defaultBlockState());
            level.setBlockAndUpdate(next.cane().below(), Blocks.SAND.defaultBlockState());
            level.setBlockAndUpdate(next.cane().below().west(), Blocks.WATER.defaultBlockState());
            // The source is primed at its native growth threshold; only a random tick may create the next block.
            level.setBlockAndUpdate(next.cane(), Blocks.SUGAR_CANE.defaultBlockState().setValue(BlockStateProperties.AGE_15, 15));
            // A narrow well forces flow across the vertical cube boundary without washing away other fixtures.
            for (int y = height - 2; y <= height + 2; y++) {
                BlockPos center = new BlockPos(37, y, 5);
                for (Direction direction : Direction.Plane.HORIZONTAL) { level.setBlockAndUpdate(center.relative(direction), Blocks.GLASS.defaultBlockState()); }
            }
            server.getPlayerList().getPlayers().getFirst().teleportTo(32.5, height - 2, 12.5);
            scene = next;
        }, server);
    }

    private record Snapshot(int height, int ticks, boolean powered, boolean lamp, boolean water, boolean cane,
                            boolean furnaceLit, int ingots, boolean entities) {
        boolean progressed() { return powered && lamp && water && cane && furnaceLit && ingots == 1 && entities; }
    }

    private static final class Scene {
        private final ServerLevel level;
        private final int height;
        private volatile boolean running;
        private int ticks;
        private boolean sawLitFurnace;
        private GameplayEntityChecks entities;

        private Scene(ServerLevel level, int height) { this.level = level; this.height = height; }
        private BlockPos lever() { return new BlockPos(30, height - 1, 3); }
        private BlockPos repeater() { return new BlockPos(31, height - 1, 3); }
        private BlockPos lamp() { return new BlockPos(32, height - 1, 3); }
        private BlockPos furnace() { return new BlockPos(34, height, 9); }
        private BlockPos hopper() { return furnace().below(); }
        private BlockPos chest() { return furnace().below(2); }
        private BlockPos cane() { return new BlockPos(27, height - 1, 4); }
        private BlockPos water() { return new BlockPos(37, height - 1, 5); }

        private void start() {
            Container furnace = (Container) level.getBlockEntity(furnace());
            furnace.setItem(0, new ItemStack(Items.RAW_IRON));
            furnace.setItem(1, new ItemStack(Items.COAL));
            furnace.setChanged();
            level.setBlockAndUpdate(water().above(2), Blocks.WATER.defaultBlockState());
            power(true);
            entities = new GameplayEntityChecks(level, height);
            running = true;
        }

        private void power(boolean powered) {
            level.setBlockAndUpdate(lever(), level.getBlockState(lever()).setValue(BlockStateProperties.POWERED, powered));
            level.updateNeighborsAt(lever(), Blocks.LEVER);
            level.updateNeighborsAt(lever().below(), Blocks.LEVER);
        }

        private boolean visibleResult(Level view, boolean powered) {
            return view.getBlockState(repeater()).is(Blocks.REPEATER)
                    && view.getBlockState(repeater()).getValue(BlockStateProperties.POWERED) == powered
                    && view.getBlockState(lamp()).is(Blocks.REDSTONE_LAMP)
                    && view.getBlockState(lamp()).getValue(BlockStateProperties.LIT) == powered
                    && view.getFluidState(water()).getType().isSame(Fluids.WATER)
                    && view.getBlockState(cane().above()).is(Blocks.SUGAR_CANE);
        }

        private Snapshot capture() {
            Container output = (Container) level.getBlockEntity(chest());
            int ingots = 0;
            for (int slot = 0; slot < output.getContainerSize(); slot++) {
                if (output.getItem(slot).is(Items.IRON_INGOT)) { ingots += output.getItem(slot).getCount(); }
            }
            return new Snapshot(height, ticks, level.getBlockState(repeater()).getValue(BlockStateProperties.POWERED),
                    level.getBlockState(lamp()).getValue(BlockStateProperties.LIT), level.getFluidState(water()).getType().isSame(Fluids.WATER),
                    level.getBlockState(cane().above()).is(Blocks.SUGAR_CANE), sawLitFurnace, ingots, entities.capture(ticks));
        }
    }
}
