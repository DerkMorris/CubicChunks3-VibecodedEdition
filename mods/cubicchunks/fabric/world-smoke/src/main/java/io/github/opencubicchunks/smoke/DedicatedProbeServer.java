package io.github.opencubicchunks.smoke;

import static io.github.opencubicchunks.smoke.ProbeSignals.require;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Properties;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldData;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.DeepCaveSettings;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.WorldHeightSettings;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gamerules.GameRules;

/** Separate headless dedicated server, production storage/networking, and two independently running clients. */
public final class DedicatedProbeServer implements ModInitializer {
    static final int UPPER = 32000;
    static final int LOWER = -32000;
    static final String ONE = "CCSurvivalOne";
    static final String TWO = "CCSurvivalTwo";
    static final WorldHeightSettings TEST_HEIGHTS = new WorldHeightSettings(32000, -96, -48);

    static BlockPos marker(int y) {
        return new BlockPos(2, y, 0);
    }

    static BlockPos mine(int y) {
        return new BlockPos(3, y, 0);
    }

    static BlockPos shared() {
        return new BlockPos(4, UPPER, 0);
    }

    static BlockPos chestPos() {
        return new BlockPos(6, UPPER, 0);
    }

    static BlockPos delayedLamp(int y) {
        return new BlockPos(7, y, 3);
    }

    private ProbeSignals signals;
    private String scenario;
    private boolean reopen;
    private int stage;
    private int ticks;
    private long deadline;
    private SurvivalChecks.ServerChecks survival;
    private BlockMechanicsChecks mechanics;
    private GameEventsChecks events;
    private PersistenceChecks persistenceUpper;
    private PersistenceChecks persistenceLower;
    private Properties expected;

    @Override public void onInitialize() {
        if (!Boolean.getBoolean("cubicchunks.smoke.dedicatedServer"))
            return;
        require(!CubicChunks.IS_IN_TEST, "Dedicated probe requires production paths");
        reopen = Boolean.getBoolean("cubicchunks.smoke.dedicatedReopen");
        scenario = System.getProperty("cubicchunks.smoke.dedicatedScenario", "all");
        signals = new ProbeSignals(Path.of(System.getProperty("cubicchunks.smoke.networkDirectory")));
        CubicChunks.config().setGenerateNewWorldsAsCC(!reopen);
        CubicChunks.config().setVerticalViewDistance(2);
        if (Boolean.getBoolean("cubicchunks.smoke.heights")) {
            var defaults = DeepCaveSettings.CLASSIC;
            CubicChunks.config().setDeepCaveSettings(new DeepCaveSettings(defaults.enabled(), defaults.scale(), defaults.openness(),
                    defaults.oreFrequency(), defaults.oreRules(), defaults.caveCurves(), reopen ? WorldHeightSettings.DEFAULT : TEST_HEIGHTS));
        }
        deadline = System.nanoTime() + java.time.Duration.ofMinutes(25).toNanos();
        ServerLifecycleEvents.SERVER_STARTED.register(this::started);
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
    }

    private void started(MinecraftServer server) {
        require(server.isDedicatedServer(), "Probe accidentally started an integrated server");
        require(((CanBeCubic) server.overworld()).cc_isCubic(), "Dedicated world is not cubic");
        verifyWorldHeights(server.overworld());
        server.overworld().getGameRules().set(GameRules.SPAWN_MOBS, false, server);
        server.overworld().getGameRules().set(GameRules.KEEP_INVENTORY, false, server);
        if (!reopen) {
            platform(server.overworld(), UPPER);
            platform(server.overworld(), LOWER);
            server.overworld().setBlockAndUpdate(marker(UPPER), Blocks.DIAMOND_BLOCK.defaultBlockState());
            server.overworld().setBlockAndUpdate(marker(LOWER), Blocks.GOLD_BLOCK.defaultBlockState());
            server.overworld().setBlockAndUpdate(mine(UPPER), Blocks.EMERALD_BLOCK.defaultBlockState());
            server.overworld().setBlockAndUpdate(mine(LOWER), Blocks.REDSTONE_BLOCK.defaultBlockState());
        } else {
            try {
                expected = new Properties();
                expected.load(new StringReader(signals.read("persisted.properties")));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        signals.signal(reopen ? "server-reopened" : "server-ready");
        System.out.println("CC_DEDICATED_STARTED dedicated=true reopen=" + reopen + " testBypass=false");
    }

    private void verifyWorldHeights(ServerLevel level) {
        if (!Boolean.getBoolean("cubicchunks.smoke.heights")) {
            return;
        }
        var saved = ((CubicWorldData) level.getLevelData()).cc_deepCaveSettings().heights();
        require(saved.equals(TEST_HEIGHTS), "Saved dedicated world heights lost: " + saved);
        require(CubicChunks.config().deepCaveSettings().heights().equals(reopen ? WorldHeightSettings.DEFAULT : TEST_HEIGHTS),
                "Dedicated height creation defaults were not configured independently of the saved world");
        System.out.println("CC_DEDICATED_HEIGHTS role=server reopen=" + reopen
                + " cloud=32000 deepslate=-96 skyFade=-48 saved=true creationDefaultsReset=" + reopen);
    }

    private void tick(MinecraftServer server) {
        require(System.nanoTime() < deadline, "Dedicated scenario timed out stage=" + stage);
        if (++ticks % 200 == 0)
            System.out.println("CC_DEDICATED_PROGRESS stage=" + stage + " phase=" + signals.read("phase") + " players="
                    + server.getPlayerList().getPlayerCount());
        ServerPlayer one = player(server, ONE);
        ServerPlayer two = player(server, TWO);
        ServerLevel level = server.overworld();
        if (reopen) {
            tickReopen(server, one, two);
            return;
        }
        switch (stage) {
            case 0 -> {
                if (one == null || two == null)
                    return;
                one.setGameMode(GameType.CREATIVE);
                two.setGameMode(GameType.CREATIVE);
                one.teleportTo(0.5, UPPER, 2.5);
                two.teleportTo(0.5, LOWER, 2.5);
                phase("network-separated");
                stage = 1;
            }
            case 1 -> {
                require(one != null && two != null, "Client disconnected before isolated actions");
                require(level.getBlockState(marker(UPPER)).is(Blocks.DIAMOND_BLOCK) && level.getBlockState(marker(LOWER)).is(Blocks.GOLD_BLOCK),
                        "Cross-height marker corruption");
                if (level.getBlockState(mine(LOWER)).isAir() && !signals.has("lower-ack")) {
                    require(level.getBlockState(mine(UPPER)).is(Blocks.EMERALD_BLOCK), "Lower action changed upper terrain");
                    signals.signal("lower-ack");
                }
                if (level.getBlockState(mine(UPPER)).isAir()) {
                    require(signals.has("two-separated"), "Upper action out of order");
                    signals.signal("upper-ack");
                }
                if (!signals.has("one-separated") || !signals.has("two-separated"))
                    return;
                two.teleportTo(1.5, UPPER, 2.5);
                level.setBlockAndUpdate(shared(), Blocks.STONE.defaultBlockState());
                phase("network-shared");
                stage = 2;
            }
            case 2 -> {
                if (level.getBlockState(shared()).isAir())
                    signals.signal("shared-ack");
                if (!signals.has("one-shared") || !signals.has("two-shared"))
                    return;
                phase("network-disconnect");
                stage = 3;
            }
            case 3 -> {
                if (two != null || !signals.has("two-disconnected"))
                    return;
                signals.signal("reconnect-allowed");
                stage = 4;
            }
            case 4 -> {
                if (two == null)
                    return;
                require(Math.abs(two.getY() - UPPER) < 2 && two.getAbilities().instabuild, "Reconnect lost saved full Y or game mode");
                signals.signal("reconnect-ack");
                stage = 5;
            }
            case 5 -> {
                if (!signals.has("two-reconnected"))
                    return;
                System.out.println("CC_DEDICATED_NETWORK_COMPLETE clients=2 realTcp=true fullY=true reconnect=true dedicated=true");
                if (scenario.equals("network") || scenario.equals("persistence")) {
                    finish(server, one, two);
                    return;
                }
                if (scenario.equals("events")) {
                    startEvents(level, one, UPPER);
                    stage = 12;
                    return;
                }
                if (scenario.equals("mechanics")) {
                    startMechanics(level, one, UPPER);
                    stage = 8;
                    return;
                }
                startSurvival(level, one, UPPER);
                stage = 6;
            }
            case 6, 7 -> {
                survival.tick();
                if (!survival.isDone() || !signals.has(stage == 6 ? "survival-upper-client" : "survival-lower-client"))
                    return;
                if (stage == 6) {
                    startSurvival(level, one, LOWER);
                    stage = 7;
                } else if (scenario.equals("survival"))
                    finish(server, one, two);
                else {
                    startMechanics(level, one, UPPER);
                    stage = 8;
                }
            }
            case 8, 9 -> {
                if (!mechanics.tick())
                    return;
                mechanics.close();
                if (stage == 8) {
                    startMechanics(level, one, LOWER);
                    stage = 9;
                } else if (scenario.equals("mechanics"))
                    finish(server, one, two);
                else {
                    startEvents(level, one, UPPER);
                    stage = 12;
                }
            }
            case 12, 13 -> {
                if (!events.tick())
                    return;
                events.close();
                if (stage == 12) {
                    startEvents(level, one, LOWER);
                    stage = 13;
                } else
                    finish(server, one, two);
            }
            case 10 -> {
                if (one != null || two != null || !signals.has("one-finished") || !signals.has("two-finished"))
                    return;
                if (persistenceScenario()) {
                    for (int y : new int[] { UPPER, LOWER }) {
                        expected.setProperty("persistence." + y, PersistenceChecks.encode(PersistenceChecks.prepare(level, y)));
                    }
                    writeExpected();
                }
                for (int y : new int[] { UPPER, LOWER }) {
                    load(level, delayedLamp(y));
                    level.setBlock(delayedLamp(y), Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true), 2);
                    level.scheduleTick(delayedLamp(y), Blocks.REDSTONE_LAMP, 80);
                }
                server.saveEverything(true, true, true);
                signals.signal("restart-ready");
                System.out.println("CC_DEDICATED_COMPLETE restartReady=true testBypass=false");
                stage = 11;
                server.halt(false);
            }
            default -> {}
        }
    }

    private void startSurvival(ServerLevel level, ServerPlayer player, int y) {
        String name = y > 0 ? "survival-upper" : "survival-lower";
        survival = new SurvivalChecks.ServerChecks(level, player, signals.directory.resolve(name), y);
        phase(name);
    }

    private void startMechanics(ServerLevel level, ServerPlayer player, int y) {
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        mechanics = new BlockMechanicsChecks(level, player, new BlockPos(128, y, 128));
        phase(y > 0 ? "mechanics-upper" : "mechanics-lower");
    }

    private void startEvents(ServerLevel level, ServerPlayer player, int y) {
        events = new GameEventsChecks(level, player, new BlockPos(256, y, 256));
        phase(y > 0 ? "events-upper" : "events-lower");
    }

    private void finish(MinecraftServer server, ServerPlayer one, ServerPlayer two) {
        require(one != null && two != null, "Missing final players");
        one.setGameMode(GameType.CREATIVE);
        two.setGameMode(GameType.CREATIVE);
        one.teleportTo(0.5, UPPER, 2.5);
        two.teleportTo(0.5, LOWER, 2.5);
        server.overworld().setBlockAndUpdate(chestPos(), Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity) server.overworld().getBlockEntity(chestPos());
        chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
        chest.setChanged();
        expected = new Properties();
        for (ServerPlayer player : new ServerPlayer[] { one, two }) {
            String key = player.getGameProfile().name();
            expected.setProperty(key + ".uuid", player.getUUID().toString());
            expected.setProperty(key + ".y", Double.toString(player.getY()));
            expected.setProperty(key + ".inventory", inventory(player));
            expected.setProperty(key + ".xp", player.experienceLevel + ":" + player.totalExperience + ":" + player.experienceProgress);
        }
        writeExpected();
        phase("finished");
        stage = 10;
    }

    private void writeExpected() {
        try {
            var writer = new StringWriter();
            expected.store(writer, "Native persisted player state");
            signals.put("persisted.properties", writer.toString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void tickReopen(MinecraftServer server, ServerPlayer one, ServerPlayer two) {
        if (stage == 0 && one != null && two != null) {
            for (ServerPlayer player : new ServerPlayer[] { one, two }) {
                String key = player.getGameProfile().name();
                require(player.getUUID().toString().equals(expected.getProperty(key + ".uuid")), "Reopen UUID mismatch");
                require(Math.abs(player.getY() - Double.parseDouble(expected.getProperty(key + ".y"))) < 2, "Fresh server lost player full Y");
                require(player.getAbilities().instabuild && sameInventory(inventory(player), expected.getProperty(key + ".inventory")),
                        "Fresh server lost inventory/components or mode");
                require((player.experienceLevel + ":" + player.totalExperience + ":" + player.experienceProgress)
                        .equals(expected.getProperty(key + ".xp")), "Fresh server lost XP");
            }
            load(server.overworld(), chestPos());
            load(server.overworld(), marker(LOWER));
            var chest = (ChestBlockEntity) server.overworld().getBlockEntity(chestPos());
            require(chest != null && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 3, "Fresh server lost container contents");
            require(server.overworld().getBlockState(mine(UPPER)).isAir() && server.overworld().getBlockState(mine(LOWER)).isAir()
                    && server.overworld().getBlockState(marker(UPPER)).is(Blocks.DIAMOND_BLOCK)
                    && server.overworld().getBlockState(marker(LOWER)).is(Blocks.GOLD_BLOCK), "Fresh server lost terrain mutations");
            for (int y : new int[] { UPPER, LOWER }) {
                load(server.overworld(), delayedLamp(y));
                require(server.overworld().getBlockState(delayedLamp(y)).is(Blocks.REDSTONE_LAMP), "Fresh server lost pending-tick block");
                if (server.overworld().getBlockState(delayedLamp(y)).getValue(BlockStateProperties.LIT)) {
                    require(server.overworld().getBlockTicks().hasScheduledTick(delayedLamp(y), Blocks.REDSTONE_LAMP),
                            "Fresh server lost scheduled block tick");
                }
                if (persistenceScenario())
                    PersistenceChecks.verifySaved(server.overworld(), PersistenceChecks.decode(expected.getProperty("persistence." + y)));
            }
            signals.signal("reopen-server-verified");
            phase("reopened");
            stage = 1;
        } else if (stage == 1 && signals.has("one-reopen") && signals.has("two-reopen")) {
            if (server.overworld().getBlockState(delayedLamp(UPPER)).getValue(BlockStateProperties.LIT)
                    || server.overworld().getBlockState(delayedLamp(LOWER)).getValue(BlockStateProperties.LIT))
                return;
            if (scenario.equals("all") || scenario.equals("mechanics")) {
                for (int y : new int[] { UPPER, LOWER }) {
                    BlockPos cloned = new BlockPos(161, y + 2, 152);
                    load(server.overworld(), cloned);
                    require(server.overworld().getBlockState(cloned).is(Blocks.DIAMOND_BLOCK), "Native clone result did not survive server restart");
                    BlockPos obsidian = new BlockPos(149, y - 1, 164);
                    load(server.overworld(), obsidian);
                    require(server.overworld().getBlockState(obsidian).is(Blocks.OBSIDIAN), "Fluid interaction did not survive server restart");
                }
            }
            if (persistenceScenario()) {
                persistenceUpper = new PersistenceChecks(server.overworld(), PersistenceChecks.decode(expected.getProperty("persistence." + UPPER)));
                persistenceLower = new PersistenceChecks(server.overworld(), PersistenceChecks.decode(expected.getProperty("persistence." + LOWER)));
                one.teleportTo(512.5, UPPER, 512.5);
                two.teleportTo(512.5, LOWER, 512.5);
                phase("persistence-reopened");
                stage = 3;
            } else
                finishReopen();
        } else if (stage == 3) {
            boolean upperDone = persistenceUpper.tick();
            boolean lowerDone = persistenceLower.tick();
            if (upperDone && lowerDone)
                finishReopen();
        } else if (stage == 2 && one == null && two == null) {
            stage = 4;
            server.halt(false);
        }
    }

    private void finishReopen() {
        System.out.println(
                "CC_DEDICATED_REOPEN_COMPLETE freshServer=true clients=2 savedPlayerY=true inventoryComponents=true xp=true terrain=true container=true scheduledTicks=true persistenceScenario="
                        + persistenceScenario());
        phase("reopen-finished");
        stage = 2;
    }

    private boolean persistenceScenario() {
        return scenario.equals("all") || scenario.equals("persistence");
    }

    private static String inventory(ServerPlayer player) {
        var value = new ListTag();
        var ops = player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
            value.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, player.getInventory().getItem(slot)).getOrThrow());
        var root = new CompoundTag();
        root.put("items", value);
        return root.toString();
    }

    private static boolean sameInventory(String actual, String expected) {
        try {
            return TagParser.parseCompoundFully(actual).equals(TagParser.parseCompoundFully(expected));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new IllegalStateException("Invalid recorded inventory", e);
        }
    }

    static ServerPlayer player(MinecraftServer server, String name) {
        return server.getPlayerList().getPlayers().stream().filter(p -> p.getGameProfile().name().equals(name)).findFirst().orElse(null);
    }

    private void phase(String phase) {
        signals.put("phase", phase);
    }

    private static void load(ServerLevel level, BlockPos pos) {
        CubePos c = CubePos.from(pos);
        ((CubicLevelReader) level).cc_getCube(c.getX(), c.getY(), c.getZ(), ChunkStatus.FULL, true);
    }

    private static void platform(ServerLevel level, int y) {
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(-4, y - 1, -4), new BlockPos(8, y + 5, 5))) {
            load(level, pos);
            level.setBlock(pos, (pos.getY() == y - 1 ? Blocks.BEDROCK : Blocks.AIR).defaultBlockState(), 2);
        }
    }
}
