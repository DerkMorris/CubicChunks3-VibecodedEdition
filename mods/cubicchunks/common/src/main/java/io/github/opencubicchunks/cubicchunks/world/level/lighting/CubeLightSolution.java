package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

/** Immutable lighting bounds. Ready values describe these snapshots, not later edits or a persistent world-lighting state. */
public final class CubeLightSolution {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private final CubeLightBounds bounds;
    private final Set<CubePos> knownCubes;
    private final byte[] blockLower;
    private final byte[] blockUpper;
    private final byte[] skyLower;
    private final byte[] skyUpper;
    private final Set<CubePos> blockReady;
    private final Set<CubePos> skyReady;

    CubeLightSolution(CubeLightBounds bounds, Set<CubePos> knownCubes, byte[] blockLower, byte[] blockUpper, byte[] skyLower, byte[] skyUpper) {
        this.bounds = bounds;
        this.knownCubes = Set.copyOf(knownCubes);
        this.blockLower = blockLower.clone();
        this.blockUpper = blockUpper.clone();
        this.skyLower = skyLower.clone();
        this.skyUpper = skyUpper.clone();
        this.blockReady = findReady(this.blockLower, this.blockUpper);
        this.skyReady = findReady(this.skyLower, this.skyUpper);
    }

    public CubeLightBounds bounds() {
        return this.bounds;
    }

    /** Extract once when publishing; subsequent engine and render reads do not retain the full bounded solve. */
    public Optional<CubeLightView> cubeView(CubePos pos) {
        Objects.requireNonNull(pos, "pos");
        if (!this.knownCubes.contains(pos)) {
            return Optional.empty();
        }
        return Optional.of(new CubeLightView(pos, copyCube(pos, this.blockLower), copyCube(pos, this.blockUpper), copyCube(pos, this.skyLower),
                copyCube(pos, this.skyUpper)));
    }

    public Optional<LightRange> sample(LightLayer layer, BlockPos pos) {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(pos, "pos");
        CubePos cube = CubePos.of(Coords.blockToCube(pos.getX()), Coords.blockToCube(pos.getY()), Coords.blockToCube(pos.getZ()));
        if (!this.bounds.contains(pos) || !this.knownCubes.contains(cube)) {
            return Optional.empty();
        }
        int index = this.bounds.index(pos);
        return Optional.of(layer == LightLayer.BLOCK ? new LightRange(this.blockLower[index], this.blockUpper[index])
                : new LightRange(this.skyLower[index], this.skyUpper[index]));
    }

    public boolean isReady(CubePos pos, LightLayer layer) {
        Objects.requireNonNull(layer, "layer");
        return (layer == LightLayer.BLOCK ? this.blockReady : this.skyReady).contains(pos);
    }

    public boolean isReady(CubePos pos) {
        return this.blockReady.contains(pos) && this.skyReady.contains(pos);
    }

    private Set<CubePos> findReady(byte[] lower, byte[] upper) {
        Set<CubePos> ready = new HashSet<>();
        for (CubePos pos : this.knownCubes) {
            if (allEqual(pos, lower, upper)) {
                ready.add(pos);
            }
        }
        return Set.copyOf(ready);
    }

    private byte[] copyCube(CubePos pos, byte[] values) {
        byte[] result = new byte[DIAMETER * DIAMETER * DIAMETER];
        int minX = pos.minCubeX() - this.bounds.minBlockX();
        int minY = pos.minCubeY() - this.bounds.minBlockY();
        int minZ = pos.minCubeZ() - this.bounds.minBlockZ();
        for (int y = 0; y < DIAMETER; y++) {
            for (int z = 0; z < DIAMETER; z++) {
                System.arraycopy(values, this.bounds.index(minX, minY + y, minZ + z), result, DIAMETER * (z + DIAMETER * y), DIAMETER);
            }
        }
        return result;
    }

    private boolean allEqual(CubePos pos, byte[] lower, byte[] upper) {
        int minX = pos.minCubeX() - this.bounds.minBlockX();
        int minY = pos.minCubeY() - this.bounds.minBlockY();
        int minZ = pos.minCubeZ() - this.bounds.minBlockZ();
        for (int y = 0; y < DIAMETER; y++) {
            for (int z = 0; z < DIAMETER; z++) {
                for (int x = 0; x < DIAMETER; x++) {
                    int index = this.bounds.index(minX + x, minY + y, minZ + z);
                    if (lower[index] != upper[index]) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
