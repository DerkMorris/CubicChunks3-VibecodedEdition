package io.github.opencubicchunks.smoke;

import static io.github.opencubicchunks.smoke.DedicatedProbeServer.*;
import static io.github.opencubicchunks.smoke.ProbeSignals.require;

import java.nio.file.Path;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

/** Native client actions against a separate server JVM; files carry phase signals only. */
final class DedicatedProbeClient {
    private final String role = System.getProperty("cubicchunks.smoke.dedicatedClient");
    private final boolean primary = role.equals("one");
    private final boolean reopen = Boolean.getBoolean("cubicchunks.smoke.dedicatedReopen");
    private final ProbeSignals signals = new ProbeSignals(Path.of(System.getProperty("cubicchunks.smoke.networkDirectory")));
    private final int port = Integer.getInteger("cubicchunks.smoke.networkPort", 25570);
    private final long deadline = System.nanoTime() + java.time.Duration.ofMinutes(25).toNanos();
    private boolean connected;
    private boolean mined;
    private boolean sharedMined;
    private boolean disconnected;
    private boolean reconnected;
    private boolean stopped;
    private int ticks;
    private String survivalPhase = "";
    private SurvivalChecks.ClientChecks survival;

    void tick(Minecraft client) {
        require(System.nanoTime() < deadline, "Dedicated client timeout role=" + role + " phase=" + signals.read("phase"));
        client.options.pauseOnLostFocus = false;
        client.options.renderDistance().set(2);
        if (++ticks % 200 == 0)
            System.out.println("CC_DEDICATED_CLIENT_PROGRESS role=" + role + " phase=" + signals.read("phase") + " screen=" + client.gui.screen()
                    + " position=" + (client.player == null ? "absent" : client.player.position()));
        if (!connected && client.gui.screen() instanceof TitleScreen && signals.has(reopen ? "server-reopened" : "server-ready")) {
            connect(client);
            connected = true;
            return;
        }
        if (disconnected && !reconnected && client.level == null && signals.has("reconnect-allowed")) {
            connect(client);
            reconnected = true;
            return;
        }
        String phase = signals.read("phase");
        if (primary && phase.startsWith("survival-")) {
            if (!phase.equals(survivalPhase)) {
                survivalPhase = phase;
                survival = new SurvivalChecks.ClientChecks(signals.directory.resolve(phase), phase.equals("survival-upper") ? UPPER : LOWER);
            }
            survival.tick(client);
            if (survival.isDone())
                signals.signal(phase + "-client");
            return;
        }
        if (!reopen && phase.equals("finished") || reopen && phase.equals("reopen-finished")) {
            if (!stopped) {
                require(client.level != null && client.player != null, "Client vanished before final native disconnect");
                System.out.println("CC_DEDICATED_CLIENT_COMPLETE role=" + role + " reopen=" + reopen + " dedicated=true realTcp=true");
                client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
                signals.signal(role + "-finished");
                stopped = true;
                client.stop();
            }
            return;
        }
        if (client.level == null || client.player == null || client.gameMode == null || client.gui.screen() != null)
            return;
        require(((CanBeCubic) client.level).cc_isCubic(), "Dedicated client did not receive cubic mode");
        require(client.getSingleplayerServer() == null, "Dedicated client accidentally owns an integrated server");
        if (reopen) {
            int y = primary ? UPPER : LOWER;
            if (!phase.equals("reopened") || !ready(client, y))
                return;
            if (!client.level.getBlockState(marker(y)).is(primary ? Blocks.DIAMOND_BLOCK : Blocks.GOLD_BLOCK))
                return;
            require(client.level.getBlockState(mine(y)).isAir(), "Client reopen lost native terrain edit");
            require(client.player.getAbilities().instabuild, "Client reopen lost saved game mode");
            require(otherPlayers(client) == 0, "Client reopen leaked distant player");
            signals.signal(role + "-reopen");
            return;
        }
        if (phase.equals("network-separated")) {
            int y = primary ? UPPER : LOWER;
            if (!ready(client, y) || !client.level.getBlockState(marker(y)).is(primary ? Blocks.DIAMOND_BLOCK : Blocks.GOLD_BLOCK))
                return;
            require(client.getConnection().getOnlinePlayers().size() == 2, "Both real profiles missing");
            require(otherPlayers(client) == 0 && client.level.getBlockState(marker(-y)).isAir(), "Full-Y view aliased another player's region");
            if (!mined) {
                if (primary && !signals.has("two-separated"))
                    return;
                if (!client.level.getBlockState(mine(y)).is(primary ? Blocks.EMERALD_BLOCK : Blocks.REDSTONE_BLOCK))
                    return;
                require(client.gameMode.startDestroyBlock(mine(y), Direction.UP), "Native action rejected");
                mined = true;
            }
            if (signals.has(primary ? "upper-ack" : "lower-ack") && client.level.getBlockState(mine(y)).isAir())
                signals.signal(role + "-separated");
        } else if (phase.equals("network-shared")) {
            if (!ready(client, UPPER) || !coLocated(client))
                return;
            if (client.level.getBlockState(shared()).is(Blocks.STONE))
                signals.signal(role + "-shared-ready");
            if (primary && !sharedMined && signals.has("two-shared-ready") && signals.has("one-shared-ready")) {
                require(client.gameMode.startDestroyBlock(shared(), Direction.UP), "Shared native action rejected");
                sharedMined = true;
            }
            if (signals.has(role + "-shared-ready") && signals.has("shared-ack") && client.level.getBlockState(shared()).isAir())
                signals.signal(role + "-shared");
        } else if (phase.equals("network-disconnect") && !primary) {
            if (!disconnected) {
                client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
                signals.signal("two-disconnected");
                disconnected = true;
            } else if (reconnected && signals.has("reconnect-ack") && ready(client, UPPER) && coLocated(client)
                    && client.level.getBlockState(marker(UPPER)).is(Blocks.DIAMOND_BLOCK)) {
                require(client.player.getAbilities().instabuild && client.level.getBlockState(shared()).isAir(), "Reconnect lost state");
                signals.signal("two-reconnected");
            }
        }
    }

    private void connect(Minecraft client) {
        ConnectScreen.startConnecting(new TitleScreen(), client, new ServerAddress("127.0.0.1", port),
                new ServerData("Cubic dedicated probe", "127.0.0.1:" + port, ServerData.Type.OTHER), false, null);
    }

    private static boolean ready(Minecraft client, int y) {
        return Math.abs(client.player.getY() - y) < 2 && !client.level.getBlockState(client.player.blockPosition().below()).isAir();
    }

    private static long otherPlayers(Minecraft client) {
        return client.level.players().stream().filter(p -> !p.getUUID().equals(client.player.getUUID())).count();
    }

    private static boolean coLocated(Minecraft client) {
        var others = client.level.players().stream().filter(p -> !p.getUUID().equals(client.player.getUUID())).toList();
        return others.size() == 1 && Math.abs(others.getFirst().getY() - UPPER) < 2 && others.getFirst().distanceToSqr(client.player) < 64;
    }
}
