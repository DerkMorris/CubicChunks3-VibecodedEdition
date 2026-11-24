package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.HashMap;
import java.util.Map;

import com.google.common.cache.LoadingCache;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Development-only raw cave inspection: production blocks before decoration/features versus an unmodulated baseline. */
public final class StrongholdCaveBaseline {
    private final DeepCaveTerrain terrain;
    private final LoadingCache<SectionPos, LevelChunkSection> productionRaw;
    private final StructureCaveInfluence influence;
    private final Map<SectionPos, StructureCaveInfluence.Field> fields = new HashMap<>();

    @SuppressWarnings("unchecked")
    public StrongholdCaveBaseline(ServerLevel level) {
        var settings = ((CubicWorldData) level.getLevelData()).cc_deepCaveSettings();
        var production = ((CubicServerLevel) level).cc_nativeBiomeTerrain();
        terrain = new DeepCaveTerrain(level.getSeed(), settings, level.palettedContainerFactory(),
                new DeepslateStrata(level.getSeed(), settings.heights().deepslateStartY()), null, level.getMinY());
        influence = new StructureCaveInfluence(level, production.deepStructures());
        try {
            var deep = NativeBiomeTerrain.class.getDeclaredField("deep");
            deep.setAccessible(true);
            var raw = DeepCaveTerrain.class.getDeclaredField("rawSections");
            raw.setAccessible(true);
            productionRaw = (LoadingCache<SectionPos, LevelChunkSection>) raw.get(deep.get(production));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot inspect production raw cave source", failure);
        }
    }

    public BlockState block(BlockPos pos) {
        return terrain.section(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ())).getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    public BlockState production(BlockPos pos) {
        return productionRaw.getUnchecked(section(pos)).getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    public double weight(BlockPos pos) {
        var key = section(pos);
        return fields.computeIfAbsent(key, at -> influence.forArea(at.minBlockX(), at.minBlockY(), at.minBlockZ(), 16))
                .weight(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Every interpolation corner must be outside all structure influence, including neighboring owners. */
    public boolean unaffected(BlockPos pos) {
        int x = Math.floorDiv(pos.getX(), 4) * 4;
        int y = Math.floorDiv(pos.getY(), 4) * 4;
        int z = Math.floorDiv(pos.getZ(), 4) * 4;
        for (int dx : new int[] { 0, 4 }) {
            for (int dy : new int[] { 0, 4 }) {
                for (int dz : new int[] { 0, 4 }) {
                    if (weight(new BlockPos(x + dx, y + dy, z + dz)) != 0) return false;
                }
            }
        }
        return true;
    }

    private static SectionPos section(BlockPos pos) {
        return SectionPos.of(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()));
    }
}
