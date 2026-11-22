package io.github.opencubicchunks.cubicchunks.client.gui.screens;

import java.util.function.Consumer;

import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.WorldHeightSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Draft values survive resize; neither Escape nor Cancel commits them to the parent. */
@SuppressWarnings("checkstyle:MagicNumber") // Screen geometry, text lengths and colors.
public final class WorldHeightSettingsScreen extends Screen {
    private static final String[] KEYS = { "cloud", "deepslate", "skyFade" };
    private final Screen parent;
    private final Consumer<WorldHeightSettings> apply;
    private final String[] values;
    private WorldHeightSettings preview;
    private Button done;
    private boolean valid;
    private int left, top, panel;

    WorldHeightSettingsScreen(Screen parent, WorldHeightSettings settings, Consumer<WorldHeightSettings> apply) {
        super(tr("title"));
        this.parent = parent;
        this.apply = apply;
        preview = settings;
        values = new String[] { Integer.toString(settings.cloudHeight()), Integer.toString(settings.deepslateStartY()),
                Integer.toString(settings.skyFadeStartY()) };
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("cubicchunks.heights." + key, args);
    }

    @Override protected void init() {
        panel = Math.min(320, width - 24);
        left = (width - panel) / 2;
        top = Math.max(32, height / 2 - 76);
        for (int index = 0; index < KEYS.length; index++) {
            addField(index);
        }
        int half = (panel - 4) / 2;
        done = addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> {
            apply.accept(preview);
            onClose();
        }).bounds(left, height - 26, half, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(left + half + 4, height - 26, panel - half - 4, 20).build());
        validateDraft();
    }

    private void addField(int index) {
        var field = addRenderableWidget(new EditBox(font, left, top + index * 42 + 12, panel, 20, tr(KEYS[index])));
        field.setMaxLength(12);
        field.setValue(values[index]);
        field.setTooltip(Tooltip.create(tr(KEYS[index] + ".info", WorldHeightSettings.SKY_FADE_DISTANCE)));
        field.setResponder(value -> {
            values[index] = value;
            validateDraft();
        });
    }

    private void validateDraft() {
        try {
            preview = new WorldHeightSettings(Integer.parseInt(values[0].trim()), Integer.parseInt(values[1].trim()),
                    Integer.parseInt(values[2].trim()));
            valid = true;
        } catch (IllegalArgumentException exception) {
            valid = false;
        }
        if (done != null) {
            done.active = valid;
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, top - 24, -1);
        for (int index = 0; index < KEYS.length; index++) {
            graphics.text(font, tr(KEYS[index]), left, top + index * 42, -1);
        }
        if (!valid) {
            graphics.centeredText(font, tr("invalid", CubicBlockBounds.MIN, CubicBlockBounds.MAX), width / 2, height - 42, 0xFFFF5555);
        }
    }

    @Override public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
