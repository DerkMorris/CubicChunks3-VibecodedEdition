package io.github.opencubicchunks.cubicchunks.test.world.level.cube.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ImposterProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStorage;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.SerializableCubeData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exhaustive registry storage checks. These do not claim to exercise every block or item's gameplay behavior. */
class TestCubeRegistryStorage extends BaseTest {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private static final int VOLUME = DIAMETER * DIAMETER * DIAMETER;
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = { 32000, -32000 })
    void everyRegisteredBlockStateAndItsFluidSurviveCubeDiskRoundTrip(int y) throws Exception {
        ServerLevel level = level();
        List<BlockState> states = new ArrayList<>();
        BuiltInRegistries.BLOCK.forEach(block -> states.addAll(block.getStateDefinition().getPossibleStates()));
        assertTrue(states.size() > 1000, "Registry sweep must not run against an uninitialized registry");
        for (int start = 0; start < states.size(); start += VOLUME) {
            CubePos key = CubePos.of(-1, Coords.blockToCube(y) + start / VOLUME, 1);
            var cube = new ProtoCube(key, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
            int count = Math.min(VOLUME, states.size() - start);
            for (int offset = 0; offset < count; offset++) {
                put(cube, position(key, offset), states.get(start + offset));
            }
            var reopened = roundTrip(level, cube);
            for (int offset = 0; offset < count; offset++) {
                BlockPos pos = position(key, offset);
                BlockState expected = states.get(start + offset);
                assertEquals(expected, reopened.getBlockState(pos), "State at " + pos);
                assertEquals(expected.getFluidState(), reopened.getFluidState(pos), "Fluid at " + pos);
            }
        }
        System.out.println("CC_BLOCK_REGISTRY_STORAGE_PASSED height=" + y + " blocks=" + BuiltInRegistries.BLOCK.size() + " states=" + states.size());
    }

    @ParameterizedTest
    @ValueSource(ints = { 32000, -32000 })
    void everyRegisteredItemRetainsIdentityAndDefaultComponentsInsideSavedCubeChests(int y) throws Exception {
        ServerLevel level = level();
        List<ItemStack> items = new ArrayList<>();
        BuiltInRegistries.ITEM.forEach(item -> {
            if (item != Items.AIR) {
                items.add(new ItemStack(item));
            }
        });
        assertTrue(items.size() > 1000, "Registry sweep must not run against an uninitialized registry");
        CubePos key = CubePos.of(1, Coords.blockToCube(y), -1);
        var cube = new LevelCube(level, key);
        int slots = new ChestBlockEntity(position(key, 0), Blocks.CHEST.defaultBlockState()).getContainerSize();
        assertTrue((items.size() + slots - 1) / slots <= VOLUME);
        for (int start = 0; start < items.size(); start += slots) {
            BlockPos pos = position(key, start / slots);
            put(cube, pos, Blocks.CHEST.defaultBlockState());
            var chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
            for (int slot = 0; slot < slots && start + slot < items.size(); slot++) {
                chest.setItem(slot, items.get(start + slot).copy());
            }
            cube.setBlockEntity(chest);
        }
        var restored = assertInstanceOf(ImposterProtoCube.class, roundTrip(level, cube));
        var full = (LevelCube) restored.cc_getWrappedClo();
        full.runPostLoad();
        for (int index = 0; index < items.size(); index++) {
            var chest = assertInstanceOf(ChestBlockEntity.class, full.getBlockEntity(position(key, index / slots)));
            ItemStack expected = items.get(index);
            ItemStack actual = chest.getItem(index % slots);
            assertTrue(ItemStack.isSameItemSameComponents(expected, actual),
                    "Item lost identity or components: " + BuiltInRegistries.ITEM.getKey(expected.getItem()));
            assertEquals(expected.getCount(), actual.getCount());
        }
        System.out.println("CC_ITEM_REGISTRY_STORAGE_PASSED height=" + y + " nonAirItems=" + items.size());
    }

    private CubeAccess roundTrip(ServerLevel level, CubeAccess cube) throws Exception {
        CubePos key = cube.cc_getCubePos();
        try (var storage = new CubeStorage(directory)) {
            storage.write(key, SerializableCubeData.copyOf(level, cube).write()).join();
        }
        try (var storage = new CubeStorage(directory)) {
            return SerializableCubeData.parse(storage.read(key).join().orElseThrow(), key).read(level);
        }
    }

    private static ServerLevel level() {
        ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
        Misc.setupLevelHeight(level, -64, 384);
        when(level.getServer().registryAccess()).thenReturn(VanillaRegistriesExtension.shared());
        return level;
    }

    private static BlockPos position(CubePos cube, int index) {
        return new BlockPos(Coords.cubeToMinBlock(cube.getX()) + index % DIAMETER, Coords.cubeToMinBlock(cube.getY()) + index / DIAMETER % DIAMETER,
                Coords.cubeToMinBlock(cube.getZ()) + index / (DIAMETER * DIAMETER));
    }

    private static void put(CubeAccess cube, BlockPos pos, BlockState state) {
        cube.getSection(Coords.blockToIndex(pos)).setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
    }
}
