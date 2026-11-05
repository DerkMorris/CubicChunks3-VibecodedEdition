package io.github.opencubicchunks.smoke;

import java.util.List;
import java.util.Map;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.client.gui.screens.OreProfilesScreen;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.HeightFormula;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.OreKind;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.OreRule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Exercise the actual formula fields and compare both native and cubic terrain to the saved ore rules. */
final class OreProfileProbe {
    private static final String FORMULA = "if(y>=32,100,if(y>=-64,0,curve(-1000,100,-64,0)))";
    private int ticks;

    static Map<OreKind, OreRule> rules() {
        return Map.of(OreKind.DIAMOND, new OreRule(-1000, 47, 6, HeightFormula.parse(FORMULA)),
                OreKind.GOLD, new OreRule(CubicBlockBounds.MIN, CubicBlockBounds.MAX, 3, HeightFormula.parse("0")));
    }

    void edit(Minecraft client, OreProfilesScreen page) {
        if (client.gui.overlay() != null) { return; }
        ticks++;
        if (ticks == 1) {
            DeepCavesProbe.press(page, Component.translatable("cubicchunks.ores.cutoff"));
        }
        if (ticks == 45) {
            Screenshot.grab(client.gameDirectory, "ore-profile-editor.png", client.gameRenderer.mainRenderTarget(), 1, message -> System.out.println("CC_ORE_GRAPH " + message));
        }
        if (ticks != 55) {
            return;
        }
        List<EditBox> fields = page.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();
        fields.get(3).setValue("if(");
        var done = page.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> b.getMessage().getString().equals(CommonComponents.GUI_DONE.getString())).findFirst().orElseThrow();
        if (done.active) { throw new AssertionError("Invalid formula accepted"); }
        fields.get(3).setValue(FORMULA);
        fields.get(1).setValue("47");
        fields.get(2).setValue("6");
        var selector = page.children().stream().filter(CycleButton.class::isInstance).map(CycleButton.class::cast).findFirst().orElseThrow();
        var key = new KeyEvent(32, 0, 0);
        selector.onPress(key);
        selector.onPress(key);
        DeepCavesProbe.press(page, Component.translatable("cubicchunks.ores.default"));
        fields.get(3).setValue("0");
        DeepCavesProbe.press(page, CommonComponents.GUI_DONE);
    }

    void checkBlocks(ServerLevel level) {
        for (int height : new int[]{32, -32, -1000, -2048}) {
            int count = 0, naturalRock = 0;
            for (int cx = 0; cx < 3; cx++) {
                var pos = CubePos.from(new BlockPos(cx * 32, height, 0));
                var cube = ((CubicLevelReader) level).cc_getCube(pos.getX(), pos.getY(), pos.getZ(), ChunkStatus.FULL, true);
                int size = CubicConstants.DIAMETER_IN_BLOCKS;
                for (int x = 0; x < size; x++) {
                    for (int z = 0; z < size; z++) {
                        for (int y = 0; y < size; y++) {
                            var block = pos.asBlockPos(x, y, z);
                            var state = cube.getBlockState(block);
                            if (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)) {
                                if (DIAMONDS.percent(block.getY()) == 0) { throw new AssertionError("Diamond leaked at " + block); }
                                count++;
                            }
                            if (state.is(Blocks.GOLD_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE)) { throw new AssertionError("Disabled gold at " + block); }
                            if (!state.isAir()) { naturalRock++; }
                        }
                    }
                }
            }
            if (naturalRock == 0 || ((height == 32 || height == -1000) != (count > 0))) {
                throw new AssertionError("Unexpected diamond count " + count + " at " + height);
            }
            System.out.println("CC_ORE_BLOCKS Y=" + height + " diamonds=" + count + " gold=0");
        }
    }

    private static final OreRule DIAMONDS = rules().get(OreKind.DIAMOND);
}
