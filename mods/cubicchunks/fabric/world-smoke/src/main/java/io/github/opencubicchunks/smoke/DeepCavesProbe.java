package io.github.opencubicchunks.smoke;

import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.gui.screens.DeepCaveSettingsScreen;
import io.github.opencubicchunks.cubicchunks.client.gui.screens.CaveProfileScreen;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldData;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.DeepCaveSettings;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.CaveParameter;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.HeightFormula;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

/** Select the generator via the real creation screen and verify published cubes, lighting inputs and reopening. */
final class DeepCavesProbe {
    private static final KeyEvent PRESS = new KeyEvent(32, 0, 0);
    private static final int[] HEIGHTS = {-300, -2048, -1000000};
    private final OreProfileProbe ores = Boolean.getBoolean("cubicchunks.smoke.oreProfiles") ? new OreProfileProbe() : null;
    private final boolean caveCurves = Boolean.getBoolean("cubicchunks.smoke.caveCurves");
    private static final String OPEN_CURVE = "curve(-1600,75,-320,40)";
    private int curvesEdited, graphTicks;
    private int step, phase, menuTicks;
    private CompletableFuture<BlockPos> work;

    boolean create(Minecraft client) {
        if (++menuTicks > 1800) { throw new IllegalStateException("World creation stuck at " + client.gui.screen()); }
        var screen = client.gui.screen();
        if (step == 0 && screen instanceof TitleScreen) {
            CubicChunks.config().setGenerateNewWorldsAsCC(true);
            CubicChunks.config().setDeepCaveSettings(DeepCaveSettings.CLASSIC);
            CubicChunks.config().setVerticalViewDistance(2);
            client.options.renderDistance().set(2);
            client.options.simulationDistance().set(5);
            client.options.pauseOnLostFocus = false;
            step++;
            CreateWorldScreen.openFresh(client, () -> { throw new IllegalStateException("Canceled world creation"); });
        } else if (step == 1 && screen instanceof CreateWorldScreen create) {
            create.getUiState().setName(System.getProperty("cubicchunks.smoke.world"));
            create.getUiState().setSeed("246813579");
            create.getUiState().setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            create.children().stream().filter(TabNavigationBar.class::isInstance).map(TabNavigationBar.class::cast).findFirst().orElseThrow().selectTab(1, false);
            press(create, Component.translatable("cubicchunks.generation.options", DeepCaveSettingsScreen.modeName(false)));
            step++;
        } else if (step == 2 && screen instanceof DeepCaveSettingsScreen options) {
            options.children().stream().filter(CycleButton.class::isInstance).map(CycleButton.class::cast).findFirst().orElseThrow().onPress(PRESS);
            try {
                var sliders = options.children().stream().filter(AbstractSliderButton.class::isInstance).map(AbstractSliderButton.class::cast).toList();
                var set = AbstractSliderButton.class.getDeclaredMethod("setValue", double.class);
                set.setAccessible(true);
                set.invoke(sliders.get(0), .5);
                set.invoke(sliders.get(1), .65);
                set.invoke(sliders.get(2), .6);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            nextEditor(options);
            step++;
        } else if (step == 3 && screen instanceof CaveProfileScreen graph) {
            editCaveCurve(client, graph);
        } else if (step == 3 && ores != null && screen instanceof io.github.opencubicchunks.cubicchunks.client.gui.screens.OreProfilesScreen profiles) {
            ores.edit(client, profiles);
        } else if (step == 3 && screen instanceof DeepCaveSettingsScreen options) {
            nextEditor(options);
        } else if (step == 3 && screen instanceof CreateWorldScreen create) {
            if (!CubicChunks.config().deepCaveSettings().equals(expected())) { throw new AssertionError(CubicChunks.config().deepCaveSettings()); }
            System.out.println("CC_DEEP_MENU " + expected());
            press(create, Component.translatable("selectWorld.create"));
            step++;
            return true;
        }
        return false;
    }

    private boolean oreEditorOpened;

    private void nextEditor(DeepCaveSettingsScreen options) {
        if (caveCurves && curvesEdited < 2) {
            var graph = options.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(b -> b.getMessage().getString().equals(Component.translatable("cubicchunks.caves.graph").getString()))
                    .skip(curvesEdited).findFirst().orElseThrow();
            if (!graph.active) { throw new AssertionError("Inactive cave graph"); }
            graph.onPress(PRESS);
        } else if (ores != null && !oreEditorOpened) {
            oreEditorOpened = true;
            press(options, Component.translatable("cubicchunks.ores.title"));
        } else {
            press(options, CommonComponents.GUI_DONE);
        }
    }

    private void editCaveCurve(Minecraft client, CaveProfileScreen graph) {
        if (client.gui.overlay() != null) { return; }
        if (++graphTicks == 1) {
            var formula = graph.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
            formula.setValue("curve(");
            var done = graph.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(b -> b.getMessage().getString().equals(CommonComponents.GUI_DONE.getString())).findFirst().orElseThrow();
            if (done.active) { throw new AssertionError("Invalid cave formula accepted"); }
            if (curvesEdited == 0) { press(graph, Component.translatable("cubicchunks.caves.grow")); }
            else { formula.setValue(OPEN_CURVE); }
        }
        if (graphTicks == 35 && curvesEdited == 0) {
            Screenshot.grab(client.gameDirectory, "cave-scale-graph.png", client.gameRenderer.mainRenderTarget(), 1, msg -> System.out.println("CC_CAVE_GRAPH " + msg));
        }
        if (graphTicks == 40) {
            press(graph, CommonComponents.GUI_DONE);
            curvesEdited++;
            graphTicks = 0;
        }
    }

    static void press(Screen screen, Component text) {
        var button = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> b.getMessage().getString().equals(text.getString())).findFirst().orElseThrow(() -> new AssertionError("Missing " + text.getString()));
        if (!button.active) { throw new AssertionError("Inactive " + text); }
        button.onPress(PRESS);
    }

    private DeepCaveSettings expected() {
        return new DeepCaveSettings(true, 92, 65, 60, ores == null ? Map.of() : OreProfileProbe.rules(), caveCurves
                ? Map.of(CaveParameter.SCALE, HeightFormula.parse("curve(-1600,144,-640,80,-64,32)"), CaveParameter.OPENNESS, HeightFormula.parse(OPEN_CURVE))
                : Map.of());
    }

    void tick(Minecraft client, boolean reopen) {
        var server = client.getSingleplayerServer();
        if (work == null) {
            work = server.submit(() -> {
                var level = server.overworld();
                if (!((CubicWorldData) level.getLevelData()).cc_deepCaveSettings().equals(expected())) { throw new AssertionError("World settings changed"); }
                if (ores != null && phase == 0) { ores.checkBlocks(level); }
                var marker = server.getWorldPath(LevelResource.ROOT).resolve("deep-probe.txt");
                try {
                    if (phase == 0 && reopen) {
                        var xyz = Files.readString(marker).trim().split(" ");
                        var pos = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
                        if (!level.getBlockState(pos).is(Blocks.EMERALD_BLOCK)) { throw new AssertionError("Lost underground edit"); }
                    }
                    var air = sample(level, HEIGHTS[phase], reopen);
                    if (phase == 0 && !reopen) {
                        var edit = air.below();
                        level.setBlockAndUpdate(edit, Blocks.EMERALD_BLOCK.defaultBlockState());
                        Files.writeString(marker, edit.getX() + " " + edit.getY() + " " + edit.getZ());
                    }
                    var player = server.getPlayerList().getPlayers().getFirst();
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    player.teleportTo(air.getX() + .5, air.getY(), air.getZ() + .5);
                    return air;
                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            return;
        }
        if (!work.isDone()) { return; }
        var pos = work.join();
        var key = CubePos.from(pos);
        var cache = (ClientCubeCache) client.level.getChunkSource();
        if (cache.cc_getCube(key.getX(), key.getY(), key.getZ(), false) == null || !cache.cc_isCubeLightReady(key)) { return; }
        if (!client.level.getBlockState(pos).isAir() || client.level.getBrightness(LightLayer.SKY, pos) != 0) { throw new AssertionError("Client cave/light mismatch at " + pos); }
        System.out.println("CC_DEEP_CLIENT pos=" + pos + " sky=0");
        work = null;
        if (++phase == HEIGHTS.length) {
            System.out.println("CC_DEEP_PASS reopen=" + reopen);
            client.stop();
        }
    }

    private static BlockPos sample(ServerLevel level, int height, boolean reopen) {
        var terrain = ((CubicServerLevel) level).cc_nativeBiomeTerrain();
        for (int attempt = 0; attempt < 12; attempt++) {
            var key = CubePos.from(new BlockPos(attempt * 32, height, 0));
            var cube = ((CubicLevelReader) level).cc_getCube(key.getX(), key.getY(), key.getZ(), ChunkStatus.FULL, true);
            var predicted = terrain.blocks(key).join();
            int air = 0, ore = 0;
            BlockPos room = null;
            int size = CubicConstants.DIAMETER_IN_BLOCKS;
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    for (int y = 0; y < size; y++) {
                        var pos = key.asBlockPos(x, y, z);
                        var state = cube.getBlockState(pos);
                        if (predicted.state(x, y, z) != state && !(reopen && state.is(Blocks.EMERALD_BLOCK))) { throw new AssertionError("Lighting input differs at " + pos + " gen=" + predicted.state(x, y, z) + " cube=" + state + " raw=" + cube.getSections()[io.github.opencubicchunks.cc_core.utils.Coords.blockToIndex(x, y, z)].getBlockState(x & 15,y & 15,z & 15)); }
                        if (state.isAir()) {
                            air++;
                            if (room == null && y > 0 && y < size - 1 && cube.getBlockState(pos.above()).isAir() && !cube.getBlockState(pos.below()).isAir()) { room = pos; }
                        }
                        if (state.is(net.minecraft.tags.BlockTags.IRON_ORES)) { ore++; }
                    }
                }
            }
            if (room != null && ore > 0) {
                System.out.println("CC_DEEP_CUBE y=" + height + " air=" + air + " iron=" + ore + " total=" + (size * size * size));
                return room;
            }
        }
        throw new AssertionError("No accessible cave with ore around " + height);
    }
}
