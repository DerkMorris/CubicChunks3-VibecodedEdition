package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.google.gson.JsonParser;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.mojang.serialization.JsonOps;
import io.github.opencubicchunks.cubicchunks.config.CommonConfig;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import org.junit.jupiter.api.Test;

class TestWorldHeightSettings extends BaseTest {
    @Test
    void existingWorldsWithoutHeightFieldsReceiveStableDefaults() {
        var old = JsonParser.parseString("{\"enabled\":true,\"scale\":64,\"openness\":45,\"ore_frequency\":35}");
        var decoded = DeepCaveSettings.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();
        assertEquals(new DeepCaveSettings(true, 64, 45, 35), decoded);
        assertEquals(WorldHeightSettings.DEFAULT, decoded.heights());
    }

    @Test
    void partialHeightSettingsRetainDefaultsForMissingFields() {
        var encoded = JsonParser.parseString("{\"cloud_height\":-32000}");
        var decoded = WorldHeightSettings.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(new WorldHeightSettings(-32000, 8, 0), decoded);
    }

    @Test
    void independentHeightsAndCaveProfilesRoundTripWhenCavesAreDisabled() {
        var custom = new DeepCaveSettings(false, 48, 24, 26, Map.of(OreKind.COAL, OreRule.DEFAULT),
                Map.of(CaveParameter.SCALE, HeightFormula.parse("48")), new WorldHeightSettings(-32000000, 32000000, -100));
        var encoded = DeepCaveSettings.CODEC.encodeStart(JsonOps.INSTANCE, custom).getOrThrow();
        assertEquals(custom, DeepCaveSettings.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        assertFalse(custom.enabled());
        var config = CommonConfig.getConfig();
        config.setDeepCaveSettings(custom);
        assertEquals(custom, config.deepCaveSettings());
        assertEquals(WorldHeightSettings.DEFAULT, CommonConfig.getConfig().deepCaveSettings().heights());
    }

    @Test
    void coordinateBoundsAreAcceptedAndOutOfRangeValuesCannotReachGeneration() {
        var limits = new WorldHeightSettings(CubicBlockBounds.MIN, CubicBlockBounds.MAX, CubicBlockBounds.MIN);
        var encoded = WorldHeightSettings.CODEC.encodeStart(JsonOps.INSTANCE, limits).getOrThrow();
        assertEquals(limits, WorldHeightSettings.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        assertThrows(IllegalArgumentException.class, () -> new WorldHeightSettings(Integer.MAX_VALUE, 8, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorldHeightSettings(192, Integer.MIN_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorldHeightSettings(192, 8, Integer.MAX_VALUE));
        for (var field : new String[] { "cloud_height", "deepslate_start_y", "sky_fade_start_y" }) {
            var invalid = JsonParser.parseString("{\"" + field + "\":2147483647}");
            assertTrue(WorldHeightSettings.CODEC.parse(JsonOps.INSTANCE, invalid).error().isPresent());
        }
    }

    @Test
    void malformedCreationHeightsCannotWrapIntoValidCoordinatesOrResetCaveProfiles() throws ReflectiveOperationException {
        var config = CommonConfig.getConfig();
        var caveSettings = new DeepCaveSettings(true, 48, 24, 26, Map.of(OreKind.COAL, OreRule.DEFAULT));
        config.setDeepCaveSettings(caveSettings);
        var field = CommonConfig.class.getDeclaredField("config");
        field.setAccessible(true);
        var raw = (CommentedConfig) field.get(config);
        for (var key : new String[] { "generation.cloudHeight", "generation.deepslateStartY", "generation.skyFadeStartY" }) {
            for (var invalid : new Object[] { "bad", 4_294_967_488L, 3.5, Double.NaN, Double.POSITIVE_INFINITY }) {
                raw.set(key, invalid);
                assertEquals(caveSettings, config.deepCaveSettings());
                assertEquals(caveSettings, config.deepCaveSettings());
            }
        }
    }
}
