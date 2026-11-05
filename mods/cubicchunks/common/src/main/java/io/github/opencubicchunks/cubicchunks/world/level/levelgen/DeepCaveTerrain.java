package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.Arrays;
import java.util.stream.DoubleStream;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/** Seeded 3D galleries and chambers on a world-aligned lattice. The cache owns its sections; callers only read or copy them. */
final class DeepCaveTerrain {
    private static final int SIDE = SectionPos.SECTION_SIZE;
    private static final int STEP = 4;
    private static final int GRID = SIDE / STEP + 1;
    private static final int CACHED_SECTIONS = 512;
    private static final int VEIN_CELL = 12;
    private static final int VEIN_RADIUS = 4;
    private static final int BLEND_HEIGHT = 12;
    // Crossfade fixed noise frequencies so changing scale does not slide caves sideways far from the origin.
    private static final double[] SCALE_BANDS = { DeepCaveSettings.MIN_SCALE, 32, 48, 64, 96, 128, DeepCaveSettings.MAX_SCALE };
    private final long seed;
    final OreDistribution ores;
    private final DeepCaveSettings settings;
    private final double[] scaleBands;
    private final PalettedContainerFactory palettes;
    private final PerlinNoise rooms;
    private final PerlinNoise tunnels;
    private final PerlinNoise cross;
    private final PerlinNoise strata;
    private final LoadingCache<SectionPos, LevelChunkSection> sections;

    DeepCaveTerrain(long seed, DeepCaveSettings settings, PalettedContainerFactory palettes) {
        this.seed = seed;
        ores = new OreDistribution(seed, settings.oreRules());
        this.settings = settings;
        var scale = settings.caveCurves().get(CaveParameter.SCALE);
        scaleBands = scale == null ? new double[] { settings.scale() }
                : DoubleStream
                        .concat(Arrays.stream(SCALE_BANDS), DoubleStream.of(CaveParameter.SCALE.sample(scale, settings.scale(), CubicBlockBounds.MIN),
                                CaveParameter.SCALE.sample(scale, settings.scale(), CubicBlockBounds.MAX)))
                        .sorted().distinct().toArray();
        this.palettes = palettes;
        var random = new XoroshiroRandomSource(seed);
        rooms = new PerlinNoise(random);
        tunnels = new PerlinNoise(random);
        cross = new PerlinNoise(random);
        strata = new PerlinNoise(random);
        sections = CacheBuilder.newBuilder().maximumSize(CACHED_SECTIONS).build(CacheLoader.from(this::generate));
    }

    LevelChunkSection section(int sx, int sy, int sz) {
        // SectionPos.asLong truncates extreme Y. The object key retains all three coordinates.
        return sections.getUnchecked(SectionPos.of(sx, sy, sz));
    }

    private LevelChunkSection generate(SectionPos pos) {
        var section = new LevelChunkSection(palettes);
        if (!settings.enabled()) {
            NativeBiomeTerrain.fillStone(section);
        } else {
            fillCaves(section, pos);
        }
        ores.place(section, pos.minBlockX(), pos.minBlockY(), pos.minBlockZ());
        return section;
    }

    private void fillCaves(LevelChunkSection section, SectionPos pos) {
        var shape = densityGrid(pos.minBlockX(), pos.minBlockY(), pos.minBlockZ());
        for (int y = 0; y < SIDE; y++) {
            for (int z = 0; z < SIDE; z++) {
                for (int x = 0; x < SIDE; x++) {
                    if (interpolate(shape, x, y, z) <= 0) {
                        var block = rock(pos.minBlockX() + x, pos.minBlockY() + y, pos.minBlockZ() + z);
                        section.setBlockState(x, y, z, block.defaultBlockState(), false);
                    }
                }
            }
        }
        if (settings.oreFrequency() > 0) {
            placeVeins(section, pos.minBlockX(), pos.minBlockY(), pos.minBlockZ());
        }
    }

    /** Taper entrances into native rock before structures and biome features are placed. */
    void blendFloor(ProtoChunk chunk) {
        if (!settings.enabled()) {
            return;
        }
        int x0 = chunk.getPos().getMinBlockX(), y0 = chunk.getMinY(), z0 = chunk.getPos().getMinBlockZ();
        var shape = densityGrid(x0, y0, z0);
        var at = new BlockPos.MutableBlockPos();
        for (int y = 0; y < Math.min(chunk.getHeight(), BLEND_HEIGHT); y++) {
            for (int z = 0; z < SIDE; z++) {
                for (int x = 0; x < SIDE; x++) {
                    if (interpolate(shape, x, y, z) > (double) y / BLEND_HEIGHT) {
                        at.set(x0 + x, y0 + y, z0 + z);
                        var state = chunk.getBlockState(at);
                        if (state.is(Blocks.BEDROCK) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.STONE) || state.is(Blocks.TUFF)) {
                            chunk.setBlockState(at, Blocks.CAVE_AIR.defaultBlockState(), 0);
                        }
                    }
                }
            }
        }
    }

    double[] densityGrid(int x0, int y0, int z0) {
        var grid = new double[GRID * GRID * GRID];
        for (int y = 0; y < GRID; y++) {
            int blockY = y0 + STEP * y;
            var scale = spectrum(CaveParameter.SCALE.sample(settings.caveCurves().get(CaveParameter.SCALE), settings.scale(), blockY));
            double open = CaveParameter.OPENNESS.sample(settings.caveCurves().get(CaveParameter.OPENNESS), settings.openness(), blockY)
                    / DeepCaveSettings.PERCENT;
            for (int z = 0; z < GRID; z++) {
                for (int x = 0; x < GRID; x++) {
                    grid[x + GRID * (z + GRID * y)] = density(x0 + STEP * x, blockY, z0 + STEP * z, scale, open);
                }
            }
        }
        return grid;
    }

    @SuppressWarnings("checkstyle:MagicNumber") // Noise frequencies, amplitudes and carve thresholds for this saved generator version.
    private double density(int x, int y, int z, ScaleBand scale, double open) {
        double chamber = scale.noise(rooms, x, y * 1.6, z) + 0.23 * scale.noise(cross, x * 2.17, y * 2.17, z * 2.17) - (0.45 - 0.52 * open);
        double gallery = 0.07 + 0.16 * open - Math.max(Math.abs(scale.noise(tunnels, x, y, z)), Math.abs(scale.noise(cross, x, y * 1.35, z)));
        return Math.max(chamber, gallery);
    }

    private ScaleBand spectrum(double value) {
        int at = Arrays.binarySearch(scaleBands, value);
        if (at >= 0) {
            return new ScaleBand(value, value, 1, 0);
        }
        int next = -at - 1;
        double low = scaleBands[next - 1], high = scaleBands[next];
        double t = (value - low) / (high - low);
        double length = Math.hypot(1 - t, t);
        return new ScaleBand(low, high, (1 - t) / length, t / length);
    }

    private static double interpolate(double[] grid, int x, int y, int z) {
        int a = x / STEP + GRID * (z / STEP + GRID * (y / STEP));
        int b = a + GRID * GRID;
        return Mth.lerp3((double) (x % STEP) / STEP, (double) (z % STEP) / STEP, (double) (y % STEP) / STEP, grid[a], grid[a + 1], grid[a + GRID],
                grid[a + GRID + 1], grid[b], grid[b + 1], grid[b + GRID], grid[b + GRID + 1]);
    }

    @SuppressWarnings("checkstyle:MagicNumber") // Frequencies and cutoffs for the geological bands.
    private Block rock(int x, int y, int z) {
        double band = strata.get(x * 0.018, y * 0.052, z * 0.018);
        if (band > 0.45) {
            return Blocks.CALCITE;
        }
        return band > 0.17 ? Blocks.TUFF : band < -0.32 ? Blocks.ANDESITE : Blocks.DEEPSLATE;
    }

    private void placeVeins(LevelChunkSection section, int x0, int y0, int z0) {
        for (int y = Math.floorDiv(y0 - VEIN_RADIUS, VEIN_CELL); y <= Math.floorDiv(y0 + SIDE - 1 + VEIN_RADIUS, VEIN_CELL); y++) {
            for (int z = Math.floorDiv(z0 - VEIN_RADIUS, VEIN_CELL); z <= Math.floorDiv(z0 + SIDE - 1 + VEIN_RADIUS, VEIN_CELL); z++) {
                for (int x = Math.floorDiv(x0 - VEIN_RADIUS, VEIN_CELL); x <= Math.floorDiv(x0 + SIDE - 1 + VEIN_RADIUS, VEIN_CELL); x++) {
                    var random = new XoroshiroRandomSource(seed ^ Mth.getSeed(x, y, z));
                    if (random.nextInt(DeepCaveSettings.PERCENT) < settings.oreFrequency()) {
                        vein(section, x * VEIN_CELL - x0 + random.nextInt(VEIN_CELL), y * VEIN_CELL - y0 + random.nextInt(VEIN_CELL),
                                z * VEIN_CELL - z0 + random.nextInt(VEIN_CELL), random);
                    }
                }
            }
        }
    }

    @SuppressWarnings("checkstyle:MagicNumber") // Original weights are part of the saved generator.
    private static Block defaultOre(XoroshiroRandomSource random) {
        return switch (random.nextInt(16)) {
            case 0 -> Blocks.DEEPSLATE_DIAMOND_ORE;
            case 1 -> Blocks.DEEPSLATE_LAPIS_ORE;
            case 2, 3 -> Blocks.DEEPSLATE_GOLD_ORE;
            case 4, 5, 6 -> Blocks.DEEPSLATE_REDSTONE_ORE;
            default -> Blocks.DEEPSLATE_IRON_ORE;
        };
    }

    @SuppressWarnings("checkstyle:MagicNumber") // Relative ore weights and ellipsoid radii.
    private void vein(LevelChunkSection section, int cx, int cy, int cz, XoroshiroRandomSource random) {
        var ore = defaultOre(random);
        if (ores.replaces(ore)) {
            return;
        }
        int rx = 2 + random.nextInt(3), ry = 1 + random.nextInt(2), rz = 2 + random.nextInt(3);
        for (int y = Math.max(0, cy - ry); y < Math.min(SIDE, cy + ry + 1); y++) {
            for (int z = Math.max(0, cz - rz); z < Math.min(SIDE, cz + rz + 1); z++) {
                for (int x = Math.max(0, cx - rx); x < Math.min(SIDE, cx + rx + 1); x++) {
                    if (Mth.square((double) (x - cx) / rx) + Mth.square((double) (y - cy) / ry) + Mth.square((double) (z - cz) / rz) <= 1
                            && !section.getBlockState(x, y, z).isAir()) {
                        section.setBlockState(x, y, z, ore.defaultBlockState(), false);
                    }
                }
            }
        }
    }

    private record ScaleBand(double low, double high, double lowWeight, double highWeight) {
        double noise(PerlinNoise noise, double x, double y, double z) {
            double first = noise.get(x / low, y / low, z / low);
            return highWeight == 0 ? first : lowWeight * first + highWeight * noise.get(x / high, y / high, z / high);
        }
    }
}
