package io.github.opencubicchunks.smoke;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Two real JVMs and native TCP packets. Files synchronize phases; all game assertions read native server/client state. */
final class WorldMultiplayerProbe {
    private static final int UPPER = 32000;
    private static final int LOWER = -32000;
    private final String role = System.getProperty("cubicchunks.smoke.multiplayer", "");
    private final Path directory = Path.of(System.getProperty("cubicchunks.smoke.networkDirectory")).toAbsolutePath().normalize();
    private final int port = Integer.getInteger("cubicchunks.smoke.networkPort", 25569);
    private final long deadline = System.nanoTime() + java.time.Duration.ofMinutes(10).toNanos();
    private CompletableFuture<Void> setup;
    private volatile HostServer host;
    private int stage;
    private int ticks;
    private boolean mined;
    private boolean sharedMined;
    private boolean published;
    private UUID guestIdentity;

    WorldMultiplayerProbe() {
        require(role.equals("host") || role.equals("guest"), "Unknown multiplayer role");
        require(Files.isDirectory(directory), "Launcher must create a unique shared control directory");
        require(port > 0 && port <= 65535, "Invalid loopback port");
        require(!CubicChunks.IS_IN_TEST, "Multiplayer probe requires production networking");
        CubicChunks.config().setVerticalViewDistance(2);
        if (!isGuest())
            ServerTickEvents.END_SERVER_TICK.register(server -> {
                HostServer current = host;
                if (current != null)
                    current.tick();
            });
    }

    static boolean isGuest() {
        return "guest".equals(System.getProperty("cubicchunks.smoke.multiplayer"));
    }

    private static BlockPos marker(int y) {
        return new BlockPos(2, y, 0);
    }

    private static BlockPos mine(int y) {
        return new BlockPos(3, y, 0);
    }

    private static BlockPos shared() {
        return new BlockPos(4, UPPER, 0);
    }

    void tick(Minecraft client) {
        if (++ticks % 200 == 0)
            System.out.println("CC_MULTIPLAYER_PROGRESS role=" + role + " stage=" + stage + " screen=" + client.gui.screen() + " position="
                    + (client.player == null ? "disconnected" : client.player.position()));
        require(System.nanoTime() < deadline, "Multiplayer probe timed out: role=" + role + " stage=" + stage);
        client.options.pauseOnLostFocus = false;
        client.options.renderDistance().set(2);
        if (isGuest())
            tickGuest(client);
        else
            tickHost(client);
    }

    private void tickHost(Minecraft client) {
        if (setup == null) {
            IntegratedServer server = client.getSingleplayerServer();
            setup = CompletableFuture.runAsync(() -> {
                ServerLevel level = server.overworld();
                ServerPlayer owner = server.getPlayerList().getPlayers().getFirst();
                preparePlatform(level, UPPER);
                preparePlatform(level, LOWER);
                level.setBlockAndUpdate(marker(UPPER), Blocks.DIAMOND_BLOCK.defaultBlockState());
                level.setBlockAndUpdate(marker(LOWER), Blocks.GOLD_BLOCK.defaultBlockState());
                level.setBlockAndUpdate(mine(UPPER), Blocks.EMERALD_BLOCK.defaultBlockState());
                level.setBlockAndUpdate(mine(LOWER), Blocks.REDSTONE_BLOCK.defaultBlockState());
                owner.setGameMode(GameType.CREATIVE);
                owner.teleportTo(0.5, UPPER, 2.5);
                server.setUsesAuthentication(false);
                host = new HostServer(server, owner.getUUID());
            }, server);
            return;
        }
        if (!setup.isDone())
            return;
        setup.join();
        if (!published) {
            require(client.getSingleplayerServer().publishServer(MinecraftServer.MultiplayerScope.LAN, true, port),
                    "Could not publish the loopback probe server");
            published = true;
            signal("host-ready");
            System.out.println("CC_MULTIPLAYER_LISTEN address=127.0.0.1 port=" + port + " authenticated=false");
        }
        if (!readyClient(client, UPPER))
            return;
        if (stage == 0 && exists("separated")) {
            if (!client.level.getBlockState(marker(UPPER)).is(Blocks.DIAMOND_BLOCK))
                return;
            require(client.level.getBlockState(marker(LOWER)).isAir(), "Upper client aliased the lower marker");
            require(otherPlayers(client) == 0, "Remote player leaked into the vertically separated client entity view");
            if (!mined) {
                if (!exists("guest-separated-verified"))
                    return;
                if (!client.level.getBlockState(mine(UPPER)).is(Blocks.EMERALD_BLOCK))
                    return;
                require(client.gameMode.startDestroyBlock(mine(UPPER), Direction.UP), "Host native block action was rejected");
                mined = true;
            }
            if (exists("host-mine-ack") && client.level.getBlockState(mine(UPPER)).isAir()) {
                signal("host-separated-verified");
                stage = 1;
            }
        } else if (stage == 1 && exists("co-located")) {
            if (!coLocatedPlayer(client) || !client.level.getBlockState(marker(UPPER)).is(Blocks.DIAMOND_BLOCK))
                return;
            if (!sharedMined && exists("guest-colocated-ready")) {
                if (!client.level.getBlockState(shared()).is(Blocks.STONE))
                    return;
                require(client.gameMode.startDestroyBlock(shared(), Direction.UP), "Host shared native block action was rejected");
                sharedMined = true;
            }
            if (sharedMined && exists("shared-mine-ack") && client.level.getBlockState(shared()).isAir()) {
                signal("host-shared-verified");
                stage = 2;
            }
        } else if (stage == 2 && exists("host-server-complete")) {
            require(client.level.getBlockState(marker(UPPER)).is(Blocks.DIAMOND_BLOCK) && client.level.getBlockState(shared()).isAir(),
                    "Final host client state changed unexpectedly");
            System.out.println("CC_MULTIPLAYER_COMPLETE role=host realTcp=true verticalIsolation=true fullYBlockPackets=true"
                    + " sharedBlock=true guestReconnect=true clientMode=true");
            stage = 3;
            client.stop();
        }
    }

    private void tickGuest(Minecraft client) {
        if (stage == 0) {
            if (client.gui.screen() instanceof TitleScreen && exists("host-ready")) {
                connect(client);
                stage = 1;
            }
            return;
        }
        if (stage == 4) {
            if (client.level == null && exists("reconnect-allowed")) {
                connect(client);
                stage = 5;
            }
            return;
        }
        if (client.level == null || client.player == null || client.gameMode == null || client.gui.screen() != null)
            return;
        if (stage == 1 && exists("separated")) {
            if (!readyClient(client, LOWER) || !client.level.getBlockState(marker(LOWER)).is(Blocks.GOLD_BLOCK))
                return;
            guestIdentity = client.player.getUUID();
            require(client.getConnection().getOnlinePlayers().size() == 2, "Guest did not receive both real player profiles");
            require(client.level.getBlockState(marker(UPPER)).isAir(), "Lower client aliased the upper marker");
            require(otherPlayers(client) == 0, "Separated guest sees the host entity at another height");
            if (!mined) {
                if (!client.level.getBlockState(mine(LOWER)).is(Blocks.REDSTONE_BLOCK))
                    return;
                require(client.gameMode.startDestroyBlock(mine(LOWER), Direction.UP), "Guest native block action was rejected");
                mined = true;
            }
            if (exists("guest-mine-ack") && client.level.getBlockState(mine(LOWER)).isAir()) {
                signal("guest-separated-verified");
                stage = 2;
            }
        } else if (stage == 2 && exists("co-located")) {
            if (!readyClient(client, UPPER) || !coLocatedPlayer(client))
                return;
            if (client.level.getBlockState(shared()).is(Blocks.STONE))
                signal("guest-colocated-ready");
            if (exists("guest-colocated-ready") && exists("shared-mine-ack") && client.level.getBlockState(shared()).isAir()) {
                require(client.level.getBlockState(marker(UPPER)).is(Blocks.DIAMOND_BLOCK), "Guest did not receive the shared upper terrain");
                signal("guest-shared-verified");
                stage = 3;
            }
        } else if (stage == 3 && exists("reconnect-request")) {
            // PauseScreen uses this entry point: it closes the TCP connection before tearing down the client world.
            // Minecraft.disconnect(Screen, boolean) alone only tears down the world and leaves the server waiting for timeout.
            System.out.println("CC_MULTIPLAYER_DISCONNECT_REQUEST nativePauseFlow=true");
            client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
            signal("guest-disconnected");
            stage = 4;
        } else if (stage == 5) {
            if (!readyClient(client, UPPER) || !exists("reconnect-server-ack") || !coLocatedPlayer(client))
                return;
            if (!client.level.getBlockState(marker(UPPER)).is(Blocks.DIAMOND_BLOCK))
                return;
            require(guestIdentity.equals(client.player.getUUID()), "Reconnect changed the player's persistent identity");
            require(client.player.getAbilities().instabuild, "Reconnect lost the saved creative mode");
            require(client.level.getBlockState(shared()).isAir() && client.level.getBlockState(mine(UPPER)).isAir(),
                    "Reconnect lost acknowledged block mutations");
            require(client.level.getBlockState(marker(LOWER)).isAir(), "Reconnect aliased the distant lower cube");
            signal("guest-complete");
            System.out.println("CC_MULTIPLAYER_COMPLETE role=guest realTcp=true verticalIsolation=true fullYBlockPackets=true"
                    + " sharedBlock=true reconnectSavedPosition=true clientMode=true nativePauseDisconnect=true");
            stage = 6;
            client.stop();
        }
    }

    private void connect(Minecraft client) {
        ConnectScreen.startConnecting(new TitleScreen(), client, new ServerAddress("127.0.0.1", port),
                new ServerData("Cubic multiplayer probe", "127.0.0.1:" + port, ServerData.Type.LAN), false, null);
    }

    private static boolean readyClient(Minecraft client, int y) {
        return client.level != null && client.player != null && client.gameMode != null && client.gui.screen() == null
                && ((CanBeCubic) client.level).cc_isCubic() && Math.abs(client.player.getY() - y) < 2;
    }

    private static long otherPlayers(Minecraft client) {
        return client.level.players().stream().filter(player -> !player.getUUID().equals(client.player.getUUID())).count();
    }

    private static boolean coLocatedPlayer(Minecraft client) {
        var others = client.level.players().stream().filter(player -> !player.getUUID().equals(client.player.getUUID())).toList();
        return others.size() == 1 && Math.abs(others.getFirst().getY() - UPPER) < 2 && others.getFirst().distanceToSqr(client.player) < 64;
    }

    private static void preparePlatform(ServerLevel level, int y) {
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(-4, y - 1, -4), new BlockPos(8, y + 5, 5))) {
            CubePos cube = CubePos.from(pos);
            ((CubicLevelReader) level).cc_getCube(cube.getX(), cube.getY(), cube.getZ(), ChunkStatus.FULL, true);
            level.setBlock(pos, (pos.getY() == y - 1 ? Blocks.BEDROCK : Blocks.AIR).defaultBlockState(), 2);
        }
    }

    private boolean exists(String name) {
        return Files.isRegularFile(directory.resolve(name));
    }

    private void signal(String name) {
        if (exists(name))
            return;
        try {
            Files.writeString(directory.resolve(name), role + "\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write multiplayer control signal " + name, e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalStateException(message);
    }

    private final class HostServer {
        final IntegratedServer server;
        final ServerLevel level;
        final UUID owner;
        UUID guest;
        int phase;

        HostServer(IntegratedServer server, UUID owner) {
            this.server = server;
            this.level = server.overworld();
            this.owner = owner;
        }

        void tick() {
            var guests = server.getPlayerList().getPlayers().stream().filter(player -> !player.getUUID().equals(owner)).toList();
            require(guests.size() <= 1, "Loopback probe unexpectedly received more than one guest");
            ServerPlayer remote = guests.isEmpty() ? null : guests.getFirst();
            if (phase == 0 && remote != null) {
                guest = remote.getUUID();
                remote.setGameMode(GameType.CREATIVE);
                remote.teleportTo(0.5, LOWER, 2.5);
                signal("separated");
                phase = 1;
            } else if (phase == 1) {
                require(remote != null && remote.getUUID().equals(guest), "Guest disconnected before separated actions finished");
                require(level.getBlockState(marker(UPPER)).is(Blocks.DIAMOND_BLOCK) && level.getBlockState(marker(LOWER)).is(Blocks.GOLD_BLOCK),
                        "Native full-Y block action changed another marker");
                if (level.getBlockState(mine(LOWER)).isAir() && !exists("guest-mine-ack")) {
                    require(level.getBlockState(mine(UPPER)).is(Blocks.EMERALD_BLOCK), "Guest action changed the other height before host input");
                    signal("guest-mine-ack");
                }
                if (level.getBlockState(mine(UPPER)).isAir()) {
                    require(exists("guest-separated-verified"), "Upper block changed before the host sent its native action");
                    signal("host-mine-ack");
                }
                if (exists("host-separated-verified") && exists("guest-separated-verified")) {
                    require(level.getBlockState(mine(UPPER)).isAir() && level.getBlockState(mine(LOWER)).isAir(),
                            "Server did not acknowledge both full-Y actions");
                    level.setBlockAndUpdate(shared(), Blocks.STONE.defaultBlockState());
                    remote.teleportTo(1.5, UPPER, 2.5);
                    signal("co-located");
                    phase = 2;
                }
            } else if (phase == 2) {
                require(remote != null && Math.abs(remote.getY() - UPPER) < 2, "Guest failed to co-locate at full height");
                if (level.getBlockState(shared()).isAir())
                    signal("shared-mine-ack");
                if (exists("host-shared-verified") && exists("guest-shared-verified")) {
                    signal("reconnect-request");
                    phase = 3;
                }
            } else if (phase == 3 && remote == null && exists("guest-disconnected")) {
                // The native logout path has saved the guest's position and game mode before removing it from PlayerList.
                signal("reconnect-allowed");
                phase = 4;
            } else if (phase == 4 && remote != null) {
                require(remote.getUUID().equals(guest) && Math.abs(remote.getY() - UPPER) < 2 && remote.getAbilities().instabuild,
                        "Native reconnect did not restore the guest's full-Y position, identity, and creative mode");
                require(level.getBlockState(shared()).isAir(), "Shared mutation disappeared during reconnect");
                signal("reconnect-server-ack");
                phase = 5;
            } else if (phase == 5 && exists("guest-complete") && remote == null) {
                CubePos lowerCube = CubePos.from(marker(LOWER));
                ((CubicLevelReader) level).cc_getCube(lowerCube.getX(), lowerCube.getY(), lowerCube.getZ(), ChunkStatus.FULL, true);
                require(level.getBlockState(marker(UPPER)).is(Blocks.DIAMOND_BLOCK) && level.getBlockState(marker(LOWER)).is(Blocks.GOLD_BLOCK)
                        && level.getBlockState(mine(UPPER)).isAir() && level.getBlockState(mine(LOWER)).isAir()
                        && level.getBlockState(shared()).isAir(), "Final server terrain differs from acknowledged two-client mutations");
                System.out.println("CC_MULTIPLAYER_SERVER_COMPLETE clients=2 heights=32000,-32000 realTcp=true fullYActions=3 reconnect=true");
                signal("host-server-complete");
                phase = 6;
            }
        }
    }
}
