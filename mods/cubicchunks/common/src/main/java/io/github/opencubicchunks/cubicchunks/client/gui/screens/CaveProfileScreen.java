package io.github.opencubicchunks.cubicchunks.client.gui.screens;

import java.util.Map;
import java.util.function.Consumer;

import io.github.opencubicchunks.cubicchunks.world.level.levelgen.CaveParameter;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.HeightFormula;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

@SuppressWarnings("checkstyle:MagicNumber") // Screen geometry and example control points.
public final class CaveProfileScreen extends Screen {
    private final Screen parent;
    private final CaveParameter parameter;
    private final int slider;
    private final Map<CaveParameter, HeightFormula> curves;
    private String expression, low = "-2048", high = "-64", error = "";
    private int left, panel;
    private HeightGraph graph;
    private HeightFormula preview;
    private Button done;

    CaveProfileScreen(Screen parent, CaveParameter parameter, int slider, Map<CaveParameter, HeightFormula> curves) {
        super(tr(parameter.getSerializedName() + ".title"));
        this.parent = parent;
        this.parameter = parameter;
        this.slider = slider;
        this.curves = curves;
        var saved = curves.get(parameter);
        expression = saved == null ? Integer.toString(slider) : saved.text();
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("cubicchunks.caves." + key, args);
    }

    @Override protected void init() {
        panel = Math.min(470, width - 20);
        left = (width - panel) / 2;
        boolean scale = parameter == CaveParameter.SCALE;
        graph = new HeightGraph(left, panel, 95, height - 65, parameter.min, parameter.max, scale ? "" : "%");
        var formula = field(left, 36, panel, tr("formula"), expression, value -> expression = value);
        formula.setTooltip(Tooltip.create(tr("help", slider)));
        int third = (panel - 8) / 3;
        button(left, 58, third, tr("grow"), () -> formula.setValue(scale ? "curve(-1600,144,-640,80,-64,32)" : "curve(-1600,85,-640,50,-64,20)"));
        button(left + third + 4, 58, third, tr("shrink"),
                () -> formula.setValue(scale ? "curve(-1600,32,-640,80,-64,144)" : "curve(-1600,20,-640,50,-64,85)"));
        button(left + 2 * third + 8, 58, panel - 2 * third - 8, tr("slider"), () -> {
            curves.remove(parameter);
            onClose();
        }).setTooltip(Tooltip.create(tr("sliderInfo", slider)));
        int w = Math.min(86, panel / 4);
        field(left + panel - 2 * w - 4, height - 44, w, Component.translatable("cubicchunks.ores.viewLow"), low, value -> low = value);
        field(left + panel - w, height - 44, w, Component.translatable("cubicchunks.ores.viewHigh"), high, value -> high = value);
        int half = (panel - 4) / 2;
        done = button(left, height - 22, half, CommonComponents.GUI_DONE, () -> {
            curves.put(parameter, preview);
            onClose();
        });
        button(left + half + 4, height - 22, panel - half - 4, CommonComponents.GUI_CANCEL, this::onClose);
        validateDraft();
    }

    private Button button(int x, int y, int w, Component label, Runnable action) {
        return addRenderableWidget(Button.builder(label, button -> action.run()).bounds(x, y, w, 18).build());
    }

    private EditBox field(int x, int y, int w, Component label, String initial, Consumer<String> edit) {
        var field = addRenderableWidget(new EditBox(font, x, y, w, 16, label));
        field.setMaxLength(HeightFormula.MAX_LENGTH);
        field.setValue(initial);
        field.setResponder(value -> {
            edit.accept(value);
            validateDraft();
        });
        return field;
    }

    private void validateDraft() {
        error = "";
        try {
            var formula = HeightFormula.parse(expression);
            graph.update(low, high, y -> parameter.sample(formula, slider, y));
            preview = formula;
        } catch (NumberFormatException e) {
            error = tr("heightError").getString();
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
        }
        done.active = error.isEmpty();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
        super.extractRenderState(g, mouseX, mouseY, tick);
        g.centeredText(font, title, width / 2, 6, -1);
        g.text(font, tr("formula"), left, 24, -1);
        g.text(font, Component.translatable("cubicchunks.ores.view"), left, height - 40, -1);
        graph.draw(g, font, mouseX, mouseY, tr(parameter.getSerializedName() + ".axis"), Component.empty(), error);
    }

    @Override public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
