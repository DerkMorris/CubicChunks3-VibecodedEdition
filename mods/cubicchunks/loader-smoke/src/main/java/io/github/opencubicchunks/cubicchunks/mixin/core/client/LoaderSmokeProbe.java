package io.github.opencubicchunks.cubicchunks.mixin.core.client;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.CubeTerrainGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opt-in development probe, excluded from release builds. Creates its own world and exits. */
@Mixin(Minecraft.class)
public class LoaderSmokeProbe {
    @Unique
    private boolean cc_smokeStarted;
    @Unique
    private boolean cc_smokeFinished;
    @Unique
    private int cc_smokeTicks;
    @Unique
    private long cc_smokeDeadline;
    @Unique
    private static final BlockPos CC_MARKER = new BlockPos(8, CubeTerrainGenerator.surface(246813579L, 8, 8) + 1, 8);

    @Inject(method = "tick", at = @At("RETURN"))
    private void cc_smoke(CallbackInfo ci) {
        if (cc_smokeFinished) {
            return;
        }
        Minecraft client = (Minecraft) (Object) this;
        if (!cc_smokeStarted && client.gui.screen() instanceof TitleScreen) {
            cc_smokeStarted = true;
            cc_smokeDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(4);
            CubicChunks.config().setGenerateNewWorldsAsCC(true);
            CubicChunks.config().setVerticalViewDistance(2);
            client.options.renderDistance().set(2);
            client.options.simulationDistance().set(5);
            client.options.pauseOnLostFocus = false;
            String name = "loader-smoke-" + System.currentTimeMillis();
            System.out.println("CC_LOADER_SMOKE_START " + name);
            client.createWorldOpenFlows().createFreshLevel(name,
                    new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true,
                            WorldDataConfiguration.DEFAULT),
                    new WorldOptions(246813579L, false, false), WorldPresets::createNormalWorldDimensions, client.gui.screen());
        }
        if (cc_smokeStarted && System.nanoTime() > cc_smokeDeadline)
            throw new IllegalStateException("Loader world smoke timed out: " + client.gui.screen());
        if (client.level == null || client.player == null)
            return;
        if (++cc_smokeTicks == 100) {
            client.getSingleplayerServer().execute(() -> {
                if (!((CanBeCubic) client.getSingleplayerServer().overworld()).cc_isCubic())
                    throw new AssertionError("Server is not cubic");
                client.getSingleplayerServer().overworld().setBlockAndUpdate(CC_MARKER, Blocks.GOLD_BLOCK.defaultBlockState());
            });
        }
        if (cc_smokeTicks % 100 == 0)
            System.out.println("CC_LOADER_SMOKE_TICK " + cc_smokeTicks + " screen=" + client.gui.screen());
        if (cc_smokeTicks >= 200 && client.gui.screen() == null && client.levelRenderer.visibleSections().size() > 0
                && !client.level.getBlockState(client.player.blockPosition().below()).isAir()
                && client.level.getBlockState(CC_MARKER).is(Blocks.GOLD_BLOCK)) {
            if (!((CanBeCubic) client.level).cc_isCubic() || CubicChunks.IS_IN_TEST)
                throw new AssertionError("Client is not running real cubic code");
            System.out.println("CC_LOADER_SMOKE_PASSED marker=" + client.level.getBlockState(CC_MARKER));
            cc_smokeFinished = true;
            client.stop();
        }
    }
}
