package io.github.opencubicchunks.cubicchunks.test.world.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Lifecycle;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldData;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldMode;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.DeepCaveSettings;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.junit.jupiter.api.Test;

class TestCubicWorldMode extends BaseTest {
    private static final LevelSettings SETTINGS = new LevelSettings("mode", GameType.CREATIVE,
            new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);

    @Test
    void terrainSettingsAreOwnedByTheSaveAndLegacyCubicWorldsKeepTheirOldFloor() {
        var config = CubicChunks.config();
        boolean previous = config.shouldGenerateNewWorldsAsCC();
        var previousSettings = config.deepCaveSettings();
        try {
            var custom = new DeepCaveSettings(true, 112, 78, 0);
            config.setGenerateNewWorldsAsCC(true);
            config.setDeepCaveSettings(custom);
            var tag = new PrimaryLevelData(SETTINGS, PrimaryLevelData.SpecialWorldProperty.NONE, Lifecycle.stable()).createTag(null);
            config.setDeepCaveSettings(DeepCaveSettings.CLASSIC);
            var restored = PrimaryLevelData.parse(new Dynamic<>(NbtOps.INSTANCE, tag), SETTINGS, PrimaryLevelData.SpecialWorldProperty.NONE,
                    Lifecycle.stable());
            assertEquals(custom, ((CubicWorldData) restored).cc_deepCaveSettings());
            tag.remove(CubicWorldData.DEEP_CAVES_KEY);
            config.setDeepCaveSettings(custom);
            var legacy = PrimaryLevelData.parse(new Dynamic<>(NbtOps.INSTANCE, tag), SETTINGS, PrimaryLevelData.SpecialWorldProperty.NONE,
                    Lifecycle.stable());
            assertEquals(DeepCaveSettings.CLASSIC, ((CubicWorldData) legacy).cc_deepCaveSettings());
        } finally {
            config.setDeepCaveSettings(previousSettings);
            config.setGenerateNewWorldsAsCC(previous);
        }
    }

    @Test
    void savedModeSurvivesChangingNewWorldPreferenceAndOnlyAppliesToOverworld() {
        boolean previous = CubicChunks.config().shouldGenerateNewWorldsAsCC();
        boolean testing = CubicChunks.IS_IN_TEST;
        try {
            CubicChunks.config().setGenerateNewWorldsAsCC(true);
            PrimaryLevelData data = new PrimaryLevelData(SETTINGS, PrimaryLevelData.SpecialWorldProperty.NONE, Lifecycle.stable());
            var tag = data.createTag(null);
            assertEquals(1, tag.getIntOr(CubicWorldData.FORMAT_KEY, 0));
            CubicChunks.config().setGenerateNewWorldsAsCC(false);
            var restored = PrimaryLevelData.parse(new Dynamic<>(NbtOps.INSTANCE, tag), SETTINGS, PrimaryLevelData.SpecialWorldProperty.NONE,
                    Lifecycle.stable());
            CubicChunks.IS_IN_TEST = false;
            assertTrue(CubicWorldMode.isCubic(restored, Level.OVERWORLD, false));
            assertFalse(CubicWorldMode.isCubic(restored, Level.NETHER, false));
            assertFalse(CubicWorldMode.isCubic(restored, Level.END, false));
        } finally {
            CubicChunks.config().setGenerateNewWorldsAsCC(previous);
            CubicChunks.IS_IN_TEST = testing;
        }
    }

    @Test
    void unmarkedExistingSaveStaysVanillaEvenWhenNewWorldPreferenceIsCubic() {
        boolean previous = CubicChunks.config().shouldGenerateNewWorldsAsCC();
        try {
            CubicChunks.config().setGenerateNewWorldsAsCC(true);
            var data = new PrimaryLevelData(SETTINGS, PrimaryLevelData.SpecialWorldProperty.NONE, Lifecycle.stable());
            var tag = data.createTag(null);
            tag.remove(CubicWorldData.FORMAT_KEY);
            var restored = PrimaryLevelData.parse(new Dynamic<>(NbtOps.INSTANCE, tag), SETTINGS, PrimaryLevelData.SpecialWorldProperty.NONE,
                    Lifecycle.stable());
            assertFalse(((CubicWorldData) restored).cc_isCubicWorld());
            tag.putInt(CubicWorldData.FORMAT_KEY, 99);
            assertThrows(IllegalArgumentException.class, () -> PrimaryLevelData.parse(new Dynamic<>(NbtOps.INSTANCE, tag), SETTINGS,
                    PrimaryLevelData.SpecialWorldProperty.NONE, Lifecycle.stable()));
        } finally {
            CubicChunks.config().setGenerateNewWorldsAsCC(previous);
        }
    }

    @Test
    void clientUsesNegotiatedModeAndIgnoresLocalNewWorldPreference() {
        boolean testing = CubicChunks.IS_IN_TEST;
        boolean client = CubicWorldMode.clientOverworld;
        try {
            CubicChunks.IS_IN_TEST = false;
            CubicWorldMode.clientOverworld = false;
            assertFalse(CubicWorldMode.isCubic(null, Level.OVERWORLD, true));
            CubicWorldMode.clientOverworld = true;
            assertTrue(CubicWorldMode.isCubic(null, Level.OVERWORLD, true));
            assertFalse(CubicWorldMode.isCubic(null, Level.NETHER, true));
        } finally {
            CubicWorldMode.clientOverworld = client;
            CubicChunks.IS_IN_TEST = testing;
        }
    }
}
