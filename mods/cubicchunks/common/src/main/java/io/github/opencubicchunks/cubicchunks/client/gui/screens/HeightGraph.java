package io.github.opencubicchunks.cubicchunks.client.gui.screens;

import java.util.Locale;
import java.util.function.IntToDoubleFunction;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Height is horizontal in all generation editors. Resample when editing, not every frame. */
@SuppressWarnings("checkstyle:MagicNumber") // Pixel layout and colors.
final class HeightGraph {
    private final int left, top, bottom, x, width, min, max;
    private final String unit;
    private final int[] samples;
    private int from, to;
    private @Nullable IntToDoubleFunction function;

    HeightGraph(int left, int panel, int top, int bottom, int min, int max, String unit) {
        this.left = left;
        this.x = left + 29;
        this.width = panel - 33;
        this.top = top;
        this.bottom = Math.max(top + 12, bottom);
        this.min = min;
        this.max = max;
        this.unit = unit;
        samples = new int[width];
    }

    void update(String low, String high, @Nullable IntToDoubleFunction function) {
        from = Integer.parseInt(low);
        to = Integer.parseInt(high);
        if (from >= to || !CubicBlockBounds.contains(from) || !CubicBlockBounds.contains(to)) {
            throw new IllegalArgumentException(Component.translatable("cubicchunks.ores.viewError").getString());
        }
        this.function = function;
        if (function != null) {
            for (int i = 0; i < width; i++) {
                double fraction = (function.applyAsDouble(heightAt(i)) - min) / (max - min);
                samples[i] = bottom - (int) Math.round((bottom - top) * fraction);
            }
        }
    }

    private int heightAt(int pixel) {
        return from + (int) Math.round((double) pixel * (to - from) / (width - 1));
    }

    void draw(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY, Component caption, Component inactive, String error) {
        g.fill(x, top, x + width, bottom + 1, 0xFF172028);
        for (int i = 0; i <= 2; i++) {
            int y = bottom - (bottom - top) * i / 2;
            g.horizontalLine(x, x + width - 1, y, 0xFF44505B);
            g.text(font, (min + (max - min) * i / 2) + unit, left, y - 4, 0xFFAEBCC5);
        }
        if (!error.isEmpty()) {
            g.text(font, font.plainSubstrByWidth(error, width + x - left), left, top - 12, 0xFFFF9988);
            return;
        }
        g.text(font, "Y " + from, x, bottom + 3, -1);
        String right = "Y " + to;
        g.text(font, right, x + width - font.width(right), bottom + 3, -1);
        if (function == null) {
            g.centeredText(font, inactive, x + width / 2, (bottom + top) / 2 - 3, -1);
            return;
        }
        g.fill(x, samples[0], x + 1, samples[0] + 1, 0xFF78D8BC);
        for (int i = 1; i < width; i++) {
            g.fill(x + i, Math.min(samples[i - 1], samples[i]), x + i + 1, Math.max(samples[i - 1], samples[i]) + 1, 0xFF78D8BC);
        }
        Component label = caption;
        if (mouseX >= x && mouseX < x + width && mouseY >= top && mouseY <= bottom) {
            int y = heightAt(mouseX - x);
            g.verticalLine(mouseX, top, bottom, 0xFFBDCFDB);
            label = Component.literal(String.format(Locale.ROOT, "Y %d    %.4g%s", y, function.applyAsDouble(y), unit));
        }
        g.text(font, label, left, top - 12, -1);
    }
}
