package io.github.opencubicchunks.cubicchunks.client.gui.screens;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import io.github.opencubicchunks.cubicchunks.world.level.levelgen.CaveParameter;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.DeepCaveSettings;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.HeightFormula;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.OreKind;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.OreRule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Edits the settings for a new world. Escape and Cancel return without committing the draft. */
public final class DeepCaveSettingsScreen extends Screen {
    private static final int ROW = 24;
    private static final int WIDTH = 280;
    private static final int GRAPH_WIDTH = 72;
    private static final int GAP = 4;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ORE_ROW = 3;
    private static final int EDITOR_ROW = 4;
    private static final int FOOTER_ROW = 5;
    private final Screen parent;
    private final Consumer<DeepCaveSettings> apply;
    private boolean enabled;
    private int scale;
    private int openness;
    private int ores;
    private Map<OreKind, OreRule> profiles;
    private final Map<CaveParameter, HeightFormula> curves = new EnumMap<>(CaveParameter.class);

    public DeepCaveSettingsScreen(Screen parent, DeepCaveSettings settings, Consumer<DeepCaveSettings> apply) {
        super(Component.translatable("cubicchunks.generation.title"));
        this.parent = parent;
        this.apply = apply;
        enabled = settings.enabled();
        scale = settings.scale();
        openness = settings.openness();
        ores = settings.oreFrequency();
        profiles = settings.oreRules();
        curves.putAll(settings.caveCurves());
    }

    public static Component modeName(boolean deep) {
        return Component.translatable(deep ? "cubicchunks.generation.deep" : "cubicchunks.generation.classic");
    }

    @Override protected void init() {
        int x = (width - WIDTH) / 2;
        int y = height / 2 - 2 * ROW;
        addRenderableWidget(CycleButton.<Boolean>builder(DeepCaveSettingsScreen::modeName, enabled).withValues(false, true).create(x, y, WIDTH,
                BUTTON_HEIGHT, Component.translatable("cubicchunks.generation.mode"), (button, value) -> {
                    enabled = value;
                    rebuildWidgets();
                }));
        addCaveRow(x, y + ROW, CaveParameter.SCALE, scale, value -> scale = value);
        addCaveRow(x, y + 2 * ROW, CaveParameter.OPENNESS, openness, value -> openness = value);
        addRenderableWidget(
                new IntSlider(x, y + ORE_ROW * ROW, WIDTH, "ores", 0, DeepCaveSettings.PERCENT, ores, value -> ores = value)).active = enabled;
        addRenderableWidget(Button
                .builder(Component.translatable("cubicchunks.ores.title"),
                        button -> minecraft.gui.setScreen(new OreProfilesScreen(this, profiles, rules -> profiles = rules)))
                .bounds(x, y + EDITOR_ROW * ROW, WIDTH, BUTTON_HEIGHT).build());
        int half = WIDTH / 2;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> {
            apply.accept(new DeepCaveSettings(enabled, scale, openness, ores, profiles, curves));
            onClose();
        }).bounds(x, y + FOOTER_ROW * ROW, half - 2, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(x + half + 2, y + FOOTER_ROW * ROW, half - 2, BUTTON_HEIGHT).build());
    }

    private void addCaveRow(int x, int y, CaveParameter parameter, int value, IntConsumer edit) {
        String key = parameter.getSerializedName();
        var slider = addRenderableWidget(new IntSlider(x, y, WIDTH - GRAPH_WIDTH - GAP, key, parameter.min, parameter.max, value, edit));
        boolean custom = curves.containsKey(parameter);
        slider.active = enabled && !custom;
        if (custom) {
            slider.setMessage(Component.translatable("cubicchunks.caves." + key + ".title"));
        }
        addRenderableWidget(Button
                .builder(Component.translatable("cubicchunks.caves.graph"),
                        button -> minecraft.gui
                                .setScreen(new CaveProfileScreen(this, parameter, parameter == CaveParameter.SCALE ? scale : openness, curves)))
                .bounds(x + WIDTH - GRAPH_WIDTH, y, GRAPH_WIDTH, BUTTON_HEIGHT).build()).active = enabled;
    }

    @Override public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, height / 2 - 2 * ROW - ROW, -1);
    }

    private static final class IntSlider extends AbstractSliderButton {
        private final String key;
        private final int min;
        private final int max;
        private final IntConsumer onChange;

        private IntSlider(int x, int y, int width, String key, int min, int max, int initial, IntConsumer onChange) {
            super(x, y, width, BUTTON_HEIGHT, Component.empty(), (double) (initial - min) / (max - min));
            this.key = "cubicchunks.generation." + key;
            this.min = min;
            this.max = max;
            this.onChange = onChange;
            setTooltip(Tooltip.create(Component.translatable(this.key + ".info")));
            updateMessage();
        }

        private int amount() {
            return min + (int) Math.round(value * (max - min));
        }

        @Override protected void updateMessage() {
            setMessage(Component.translatable(key, amount()));
        }

        @Override protected void applyValue() {
            onChange.accept(amount());
        }
    }
}
