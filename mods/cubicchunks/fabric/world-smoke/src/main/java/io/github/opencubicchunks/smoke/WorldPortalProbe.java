package io.github.opencubicchunks.smoke;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.level.storage.LevelData.RespawnData;

/** Real portal collisions, dimension packets, cubic/vanilla mode switches and the normal End boss lifecycle. */
final class WorldPortalProbe {
    private static final int[] HEIGHTS = { 96, 32000, -32000 };
    private volatile int stage;
    private volatile int acknowledged = -1;
    private volatile int phase;
    private int stageTicks;
    private boolean started;
    private int clientTicks;
    private DragonFightChecks dragon;
    private volatile BlockPos source;

    WorldPortalProbe() {
        ServerTickEvents.END_SERVER_TICK.register(this::serverTick);
    }

    void tick(Minecraft client) {
        if (!started) {
            started = true;
            client.getSingleplayerServer().execute(() -> prepare(client.getSingleplayerServer()));
        }
        if (client.level != null && ++clientTicks % 100 == 0 && ((CanBeCubic) client.level).cc_isCubic()) {
            try {
                var storageField = net.minecraft.client.multiplayer.ClientChunkCache.class.getDeclaredField("cc_cubeStorage");
                storageField.setAccessible(true);
                var storage = (io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache.Storage) storageField
                        .get(client.level.getChunkSource());
                System.out.println("CC_PORTAL_CLIENT_STORAGE center=" + storage.viewCenterX + "," + storage.viewCenterY + "," + storage.viewCenterZ
                        + " count=" + storage.chunkCount + " player=" + client.player.position());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
        if (client.level == null || client.player == null || client.gui.screen() != null)
            return;
        boolean cubic = ((CanBeCubic) client.level).cc_isCubic();
        int observed = stage;
        if ((observed == 1 || observed == 3 || observed == 6) && client.level.dimension() == Level.OVERWORLD && cubic
                && Math.abs(client.player.getY() - HEIGHTS[phase]) < 5
                && !client.level.getBlockState(client.player.blockPosition().below()).isAir()) {
            acknowledged = observed;
        } else if (observed == 2 && client.level.dimension() == Level.NETHER && !cubic) {
            acknowledged = observed;
        } else if (observed == 4 && client.level.dimension() == Level.END && !cubic) {
            acknowledged = observed;
        } else if (observed == 7) {
            System.out.println("CC_PORTAL_COMPLETE netherRoundtrips=3 endRoundtrip=true cubicModeSwitch=true nativePortalCollision=true testBypass="
                    + CubicChunks.IS_IN_TEST);
            client.stop();
        }
    }

    private void prepare(MinecraftServer server) {
        require(!CubicChunks.IS_IN_TEST, "Portal probe requires production paths");
        ServerLevel level = server.overworld();
        source = new BlockPos(1599 + phase * 512, HEIGHTS[phase], 511);
        for (BlockPos pos : BlockPos.betweenClosed(source.offset(-4, -1, -4), source.offset(10, 5, 4))) {
            CubePos cube = CubePos.from(pos);
            ((CubicLevelReader) level).cc_getCube(cube.getX(), cube.getY(), cube.getZ(), ChunkStatus.FULL, true);
            level.setBlock(pos, (pos.getY() == source.getY() - 1 ? Blocks.OBSIDIAN : Blocks.AIR).defaultBlockState(), 2);
        }
        for (int x = -1; x <= 2; x++) {
            for (int y = -1; y <= 3; y++) {
                if (x == -1 || x == 2 || y == -1 || y == 3) {
                    level.setBlockAndUpdate(source.offset(x, y, 0), Blocks.OBSIDIAN.defaultBlockState());
                }
            }
        }
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        player.teleportTo(source.getX() + 0.5, source.getY(), source.getZ() + 2.5);
        enter(1);
    }

    private void serverTick(MinecraftServer server) {
        if (stage == 0 || stage == 7)
            return;
        if (++stageTicks > (stage == 5 ? 2000 : 1200)) {
            throw new IllegalStateException("Portal probe timed out phase=" + phase + " stage=" + stage);
        }
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        if (stageTicks % 100 == 0) {
            System.out.println("CC_PORTAL_PROGRESS phase=" + phase + " stage=" + stage + " ticks=" + stageTicks + " dimension="
                    + player.level().dimension() + " position=" + player.position() + " clientAck=" + acknowledged);
        }
        if (stage == 1 && acknowledged == 1) {
            var shape = PortalShape.findEmptyPortalShape(server.overworld(), source.above(), Direction.Axis.X).orElseThrow();
            shape.createPortalBlocks(server.overworld());
            player.setPortalCooldown(0);
            player.teleportTo(source.getX() + 0.5, source.getY(), source.getZ() + 0.5);
            enter(2);
        } else if (stage == 2 && acknowledged == 2 && player.level().dimension() == Level.NETHER) {
            require(!((CanBeCubic) player.level()).cc_isCubic(), "Nether unexpectedly became cubic");
            System.out.println("CC_PORTAL_ENTER_NETHER sourceY=" + HEIGHTS[phase] + " destination=" + player.position() + " clientMode=false");
            BlockPos exit = nearbyPortal(player.level(), player.blockPosition());
            player.setPortalCooldown(0);
            player.teleportTo(exit.getX() + 0.5, exit.getY(), exit.getZ() + 0.5);
            enter(3);
        } else if (stage == 3 && acknowledged == 3 && player.level() == server.overworld()) {
            require(Math.abs(player.getY() - source.getY()) < 5 && player.blockPosition().distSqr(source) < 100,
                    "Nether return did not find the original full-height portal");
            System.out.println("CC_PORTAL_ROUNDTRIP height=" + HEIGHTS[phase] + " position=" + player.position() + " clientMode=true");
            if (++phase < HEIGHTS.length) {
                prepare(server);
            } else {
                phase = HEIGHTS.length - 1;
                BlockPos returnPoint = source.offset(7, 0, 2);
                player.setRespawnPosition(new ServerPlayer.RespawnConfig(RespawnData.of(Level.OVERWORLD, returnPoint, 0, 0), true), false);
                BlockPos endPortal = source.offset(7, 0, 0);
                server.overworld().setBlockAndUpdate(endPortal, Blocks.END_PORTAL.defaultBlockState());
                player.setPortalCooldown(0);
                player.teleportTo(endPortal.getX() + 0.5, endPortal.getY() + 0.5, endPortal.getZ() + 0.5);
                enter(4);
            }
        } else if (stage == 4 && acknowledged == 4 && player.level().dimension() == Level.END) {
            require(!((CanBeCubic) player.level()).cc_isCubic(), "End unexpectedly became cubic");
            System.out.println("CC_PORTAL_ENTER_END sourceY=" + HEIGHTS[phase] + " clientMode=false");
            ServerLevel end = player.level();
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++)
                    end.getChunk(x, z);
            }
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.teleportTo(0.5, 90, 40.5);
            dragon = new DragonFightChecks(end);
            enter(5);
        } else if (stage == 5 && dragon.tick()) {
            // The separate credits UI is skipped; the real End portal and saved high-Y respawn position handle the return.
            player.seenCredits = true;
            BlockPos exit = new BlockPos(10, 82, 10);
            player.level().setBlockAndUpdate(exit.below(), Blocks.OBSIDIAN.defaultBlockState());
            player.level().setBlockAndUpdate(exit, Blocks.END_PORTAL.defaultBlockState());
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
            player.setPortalCooldown(0);
            player.teleportTo(exit.getX() + 0.5, exit.getY() + 0.5, exit.getZ() + 0.5);
            enter(6);
        } else if (stage == 6 && acknowledged == 6 && player.level() == server.overworld()) {
            require(Math.abs(player.getY() - HEIGHTS[phase]) < 5, "End return lost the saved high-Y spawn");
            System.out.println("CC_PORTAL_END_RETURN position=" + player.position() + " clientMode=true");
            enter(7);
        }
    }

    private void enter(int next) {
        acknowledged = -1;
        stageTicks = 0;
        stage = next;
    }

    private static BlockPos nearbyPortal(ServerLevel level, BlockPos center) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-4, -4, -4), center.offset(4, 4, 4))) {
            if (level.getBlockState(pos).is(Blocks.NETHER_PORTAL))
                return pos.immutable();
        }
        throw new IllegalStateException("Arrived without a native Nether portal nearby: " + center);
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalStateException(message);
    }
}
