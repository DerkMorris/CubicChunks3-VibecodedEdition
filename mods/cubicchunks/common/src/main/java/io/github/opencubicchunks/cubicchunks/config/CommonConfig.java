package io.github.opencubicchunks.cubicchunks.config;

import java.io.File;
import java.util.EnumMap;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.CaveParameter;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.DeepCaveSettings;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.HeightFormula;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.OreKind;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.OreRule;
import io.github.opencubicchunks.cubicchunks.platform.Platform;

public class CommonConfig extends BaseConfig {
    private static final String FILE_NAME = "cubicchunks_common.toml";
    // Note that this relies on IS_IN_TEST being set before this class is classloaded
    private static final File FILE_PATH = CubicChunks.IS_IN_TEST ? null : Platform.get().configDirectory().resolve(FILE_NAME).toFile();

    private static final String KEY_GENERAL = "general";
    private static final String KEY_VERTICAL_VIEW_DISTANCE = KEY_GENERAL + ".verticalViewDistance";
    private static final int DEFAULT_VERTICAL_VIEW_DISTANCE = 8;
    private static final String KEY_GENERATE_NEW_WORLDS_AS_CC = KEY_GENERAL + ".generateNewWorldsAsCC";

    private static final String CAVES = "generation.deepCaves";
    private static final String SCALE = "generation.caveScale";
    private static final String OPENNESS = "generation.caveOpenness";
    private static final String ORES = "generation.oreFrequency";

    private final CommentedConfig config;

    private CommonConfig(CommentedConfig config) {
        this.config = config;
    }

    private static CommentedConfig createDefaultConfig() {
        Config.setInsertionOrderPreserved(true);
        var config = CommentedConfig.inMemory();
        config.set(KEY_VERTICAL_VIEW_DISTANCE, DEFAULT_VERTICAL_VIEW_DISTANCE);
        // TODO more detailed config comment?
        config.setComment(KEY_VERTICAL_VIEW_DISTANCE, """
                 The vertical view distance for players in Cubic Chunks dimensions (similar to vanilla render distance for the horizontal axes).\
                """);
        config.set(KEY_GENERATE_NEW_WORLDS_AS_CC, false);
        config.setComment(KEY_GENERATE_NEW_WORLDS_AS_CC, """
                         Create new Overworlds using cubic storage.\
                """);
        var defaults = DeepCaveSettings.CLASSIC;
        config.set(CAVES, defaults.enabled());
        config.set(SCALE, defaults.scale());
        config.set(OPENNESS, defaults.openness());
        config.set(ORES, defaults.oreFrequency());
        config.setComment(CAVES, "Generate caves below the native terrain floor in new cubic worlds.");
        config.setComment(SCALE, "Cave noise scale in blocks (24 to 160).");
        config.setComment(OPENNESS, "Cave openness (0 to 100).");
        for (var parameter : CaveParameter.values()) {
            config.set(curveKey(parameter), "");
            config.setComment(curveKey(parameter), "Optional formula in absolute block y. Leave empty to use the slider.");
        }
        config.setComment(ORES, "Frequency of default deep ore veins (0 to 100). Individual ore formulas override this setting.");
        for (var ore : OreKind.values()) {
            writeOre(config, ore, null);
        }
        config.setComment("generation.ores", "Per-ore overrides for new cubic Overworlds. Formula is a percentage per 12x12x12 cell at absolute y."
                + " minY and maxY are inclusive, radius is 1 to 8. Example: curve(-1000,40,-64,0).");
        return config;
    }

    // TODO save on game exit, etc, instead of every time config is marked dirty
    public void markDirty() {
        write(FILE_PATH, config);
    }

    // TODO do we want config values in fields on this class, instead of doing a get() each time?
    public int getVerticalViewDistance() {
        return config.getInt(KEY_VERTICAL_VIEW_DISTANCE);
    }

    public boolean shouldGenerateNewWorldsAsCC() {
        return config.get(KEY_GENERATE_NEW_WORLDS_AS_CC);
    }

    public void setVerticalViewDistance(int verticalViewDistance) {
        config.set(KEY_VERTICAL_VIEW_DISTANCE, verticalViewDistance);
    }

    public void setGenerateNewWorldsAsCC(boolean generateNewWorldsAsCC) {
        config.set(KEY_GENERATE_NEW_WORLDS_AS_CC, generateNewWorldsAsCC);
    }

    public DeepCaveSettings deepCaveSettings() {
        var rules = new EnumMap<OreKind, OreRule>(OreKind.class);
        for (var ore : OreKind.values()) {
            String key = "generation.ores." + ore.getSerializedName() + ".";
            if (Boolean.TRUE.equals(config.get(key + "enabled"))) {
                try {
                    rules.put(ore, new OreRule(config.getInt(key + "minY"), config.getInt(key + "maxY"), config.getInt(key + "radius"),
                            HeightFormula.parse(config.get(key + "formula"))));
                } catch (IllegalArgumentException | ClassCastException e) {
                    CubicChunks.LOGGER.warn("Invalid {} ore rule in {}. Restoring this ore's default distribution.", ore.getSerializedName(),
                            FILE_NAME, e);
                    writeOre(config, ore, null);
                }
            }
        }
        var curves = new EnumMap<CaveParameter, HeightFormula>(CaveParameter.class);
        for (var parameter : CaveParameter.values()) {
            String key = curveKey(parameter);
            try {
                String expression = config.get(key);
                if (!expression.isBlank()) {
                    curves.put(parameter, HeightFormula.parse(expression));
                }
            } catch (IllegalArgumentException | ClassCastException e) {
                CubicChunks.LOGGER.warn("Invalid formula for {} in {}. Using its slider.", key, FILE_NAME, e);
                config.set(key, "");
            }
        }
        return new DeepCaveSettings(config.get(CAVES), config.getInt(SCALE), config.getInt(OPENNESS), config.getInt(ORES), rules, curves);
    }

    private static String curveKey(CaveParameter parameter) {
        return "generation.caveCurves." + parameter.getSerializedName();
    }

    public void setDeepCaveSettings(DeepCaveSettings settings) {
        config.set(CAVES, settings.enabled());
        config.set(SCALE, settings.scale());
        config.set(OPENNESS, settings.openness());
        config.set(ORES, settings.oreFrequency());
        for (var parameter : CaveParameter.values()) {
            var formula = settings.caveCurves().get(parameter);
            config.set(curveKey(parameter), formula == null ? "" : formula.text());
        }
        for (var ore : OreKind.values()) {
            writeOre(config, ore, settings.oreRules().get(ore));
        }
    }

    private static void writeOre(CommentedConfig config, OreKind ore, @javax.annotation.Nullable OreRule rule) {
        String key = "generation.ores." + ore.getSerializedName() + ".";
        var values = rule == null ? OreRule.DEFAULT : rule;
        config.set(key + "enabled", rule != null);
        config.set(key + "minY", values.minY());
        config.set(key + "maxY", values.maxY());
        config.set(key + "radius", values.radius());
        config.set(key + "formula", values.formula().text());
    }

    public static CommonConfig getConfig() {
        var config = createDefaultConfig();
        if (CubicChunks.IS_IN_TEST) {
            // Skip file access when running in a test environment; tests should manually update relevant config values before running game code.
            return new CommonConfig(config);
        }
        // Read existing values to the config
        if (FILE_PATH.exists()) {
            read(FILE_PATH, config);
        }
        var commonConfig = new CommonConfig(config);
        try {
            commonConfig.deepCaveSettings();
        } catch (IllegalArgumentException | ClassCastException exception) {
            CubicChunks.LOGGER.warn("Invalid deep cave creation defaults in {}. Restoring defaults.", FILE_NAME, exception);
            commonConfig.setDeepCaveSettings(DeepCaveSettings.CLASSIC);
        }
        // Write the config again even if we loaded an existing file, in case any keys were missing or invalid
        write(FILE_PATH, config);
        return commonConfig;
    }
}
