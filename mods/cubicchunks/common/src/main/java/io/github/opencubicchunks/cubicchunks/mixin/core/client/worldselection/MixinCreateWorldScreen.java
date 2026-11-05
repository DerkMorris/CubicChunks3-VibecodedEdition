package io.github.opencubicchunks.cubicchunks.mixin.core.client.worldselection;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.gui.screens.DeepCaveSettingsScreen;
import io.github.opencubicchunks.cubicchunks.config.CommonConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SwitchGrid;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public class MixinCreateWorldScreen {

    @Unique
    private static final int cc_OPTIONS_WIDTH = 210;
    private static final Component CUBIC_CHUNKS = Component.translatable("selectWorld.cubicChunks");

    private boolean cc_cubicChunks;
    @Unique
    private Button cc_generationOptions;

    private boolean cc_isCubicChunks() {
        return cc_cubicChunks;
    }

    private void cc_setCubicChunks(boolean cubicChunks) {
        this.cc_cubicChunks = cubicChunks;
        CommonConfig config = CubicChunks.config();
        config.setGenerateNewWorldsAsCC(cubicChunks);
        config.markDirty();
        if (cc_generationOptions != null) {
            cc_generationOptions.active = cubicChunks;
        }
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE_ASSIGN", unsafe = true, target = "Lnet/minecraft/client/gui/screens/worldselection/SwitchGrid$Builder;addSwitch(Lnet/minecraft/network/chat/Component;Ljava/util/function/BooleanSupplier;Ljava/util/function/Consumer;)Lnet/minecraft/client/gui/screens/worldselection/SwitchGrid$SwitchBuilder;", ordinal = 1))
    private void cc_addCubicWorldGenButtonToCreateWorldScreen(CreateWorldScreen screen, CallbackInfo ci, @Local SwitchGrid.Builder builder) {
        cc_cubicChunks = CubicChunks.config().shouldGenerateNewWorldsAsCC();
        builder.addSwitch(CUBIC_CHUNKS, this::cc_isCubicChunks, this::cc_setCubicChunks)
                .withInfo(Component.translatable("selectWorld.cubicChunks.experimental.info"));
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cc_addGenerationOptions(CreateWorldScreen screen, CallbackInfo ci, @Local GridLayout.RowHelper rows) {
        CommonConfig config = CubicChunks.config();
        cc_generationOptions = Button.builder(cc_optionsLabel(),
                button -> Minecraft.getInstance().gui.setScreen(new DeepCaveSettingsScreen(screen, config.deepCaveSettings(), settings -> {
                    config.setDeepCaveSettings(settings);
                    config.markDirty();
                    button.setMessage(cc_optionsLabel());
                }))).width(cc_OPTIONS_WIDTH).build();
        cc_generationOptions.active = cc_cubicChunks;
        rows.addChild(cc_generationOptions, 2);
    }

    @Unique
    private static Component cc_optionsLabel() {
        return Component.translatable("cubicchunks.generation.options",
                DeepCaveSettingsScreen.modeName(CubicChunks.config().deepCaveSettings().enabled()));
    }
}
