package io.github.opencubicchunks.cubicchunks.client.gui.screens;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.HeightFormula;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.OreKind;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.OreRule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Drafts for each ore survive switching pages and resizing. Only Done commits them to the parent editor. */
@SuppressWarnings("checkstyle:MagicNumber") // Pixel coordinates, graph colors and preset values.
public final class OreProfilesScreen extends Screen {
    private final Screen parent;
    private final Consumer<Map<OreKind, OreRule>> apply;
    private final Map<OreKind, OreRule> rules = new EnumMap<>(OreKind.class);
    private OreKind ore = OreKind.DIAMOND;
    private boolean custom;
    private String low, high, radius, expression;
    private String viewLow = "-1200", viewHigh = "128";
    private EditBox minField, maxField, sizeField, formulaField;
    private Button mode, done;
    private CycleButton<OreKind> selector;
    private String error = "";
    private HeightGraph graph;
    private int left, panel;

    public OreProfilesScreen(Screen parent, Map<OreKind, OreRule> initial, Consumer<Map<OreKind, OreRule>> apply) {
        super(tr("title"));
        this.parent = parent;
        this.apply = apply;
        rules.putAll(initial);
        loadDraft();
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("cubicchunks.ores." + key, args);
    }

    private void loadDraft() {
        custom = rules.containsKey(ore);
        read(rules.getOrDefault(ore, OreRule.DEFAULT));
    }

    private void read(OreRule rule) {
        low = Integer.toString(rule.minY());
        high = Integer.toString(rule.maxY());
        radius = Integer.toString(rule.radius());
        expression = rule.formula().text();
    }

    @Override protected void init() {
        panel = Math.min(470, width - 20);
        left = (width - panel) / 2;
        graph = new HeightGraph(left, panel, 131, height - 65, 0, 100, "%");
        int half = (panel - 4) / 2;
        selector = addRenderableWidget(CycleButton.<OreKind>builder(kind -> kind.stone().getName(), ore).withValues(OreKind.values()).create(left, 17,
                half, 18, tr("ore"), (button, value) -> {
                    ore = value;
                    loadDraft();
                    refresh();
                }));
        mode = button(left + half + 4, 17, panel - half - 4, tr("default"), () -> {
            custom = !custom;
            validateDraft();
        });
        int part = panel / 3;
        minField = field(left, 47, part - 4, "min", low, value -> low = value);
        maxField = field(left + part, 47, part - 4, "max", high, value -> high = value);
        sizeField = field(left + 2 * part, 47, panel - 2 * part, "radius", radius, value -> radius = value);
        formulaField = field(left, 79, panel - 26, "formula", expression, value -> expression = value);
        button(left + panel - 22, 78, 22, Component.literal("?"), () -> minecraft.gui.setScreen(new Help(this)));
        button(left, 101, part - 3, tr("constant"), () -> preset(0));
        button(left + part, 101, part - 3, tr("cutoff"), () -> preset(1));
        button(left + 2 * part, 101, panel - 2 * part, tr("plateau"), () -> preset(2));
        int viewWidth = Math.min(86, panel / 4);
        field(left + panel - 2 * viewWidth - 4, height - 44, viewWidth, "viewLow", viewLow, value -> viewLow = value);
        field(left + panel - viewWidth, height - 44, viewWidth, "viewHigh", viewHigh, value -> viewHigh = value);
        done = button(left, height - 22, half, CommonComponents.GUI_DONE, () -> {
            apply.accept(Map.copyOf(rules));
            onClose();
        });
        button(left + half + 4, height - 22, panel - half - 4, CommonComponents.GUI_CANCEL, this::onClose);
        validateDraft();
    }

    private Button button(int x, int y, int w, Component label, Runnable press) {
        return addRenderableWidget(Button.builder(label, button -> press.run()).bounds(x, y, w, 18).build());
    }

    private EditBox field(int x, int y, int w, String key, String initial, Consumer<String> change) {
        var field = addRenderableWidget(new EditBox(font, x, y, w, 16, tr(key)));
        field.setMaxLength(HeightFormula.MAX_LENGTH);
        field.setValue(initial);
        field.setResponder(value -> {
            change.accept(value);
            validateDraft();
        });
        return field;
    }

    private void preset(int type) {
        custom = true;
        var min = type == 1 ? -1000 : CubicBlockBounds.MIN;
        read(new OreRule(min, CubicBlockBounds.MAX, OreRule.DEFAULT.radius(),
                type == 0 ? OreRule.DEFAULT.formula() : HeightFormula.parse(OreRule.GROWTH)));
        refresh();
    }

    private void refresh() {
        minField.setValue(low);
        maxField.setValue(high);
        sizeField.setValue(radius);
        formulaField.setValue(expression);
        validateDraft();
    }

    private void validateDraft() {
        if (done == null) {
            return;
        }
        mode.setMessage(tr(custom ? "custom" : "default"));
        minField.setEditable(custom);
        maxField.setEditable(custom);
        sizeField.setEditable(custom);
        formulaField.setEditable(custom);
        OreRule preview = null;
        error = "";
        try {
            if (custom) {
                var rule = new OreRule(Integer.parseInt(low), Integer.parseInt(high), Integer.parseInt(radius), HeightFormula.parse(expression));
                preview = rule;
                rules.put(ore, rule);
            } else {
                rules.remove(ore);
            }
            graph.update(viewLow, viewHigh, preview == null ? null : preview::percent);
        } catch (NumberFormatException e) {
            error = tr("numberError").getString();
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
        }
        done.active = error.isEmpty();
        selector.active = done.active;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
        super.extractRenderState(g, mouseX, mouseY, tick);
        g.centeredText(font, title, width / 2, 4, -1);
        g.text(font, tr("min"), left, 37, -1);
        g.text(font, tr("max"), left + panel / 3, 37, -1);
        g.text(font, tr("radius"), left + 2 * (panel / 3), 37, -1);
        g.text(font, tr("formula"), left, 68, -1);
        g.text(font, tr("view"), left, height - 40, -1);
        graph.draw(g, font, mouseX, mouseY, tr("chance"), tr("nativeInfo"), error);
    }

    @Override public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private static final class Help extends Screen {
        private final Screen parent;

        private Help(Screen parent) {
            super(tr("helpTitle"));
            this.parent = parent;
        }

        @Override protected void init() {
            addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose()).bounds(width / 2 - 60, height - 24, 120, 20).build());
        }

        @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
            super.extractRenderState(g, mx, my, tick);
            int w = Math.min(430, width - 24);
            g.centeredText(font, title, width / 2, 8, -1);
            g.textWithWordWrap(font, tr("help"), (width - w) / 2, 26, w, -1);
        }

        @Override public void onClose() {
            minecraft.gui.setScreen(parent);
        }
    }
}
