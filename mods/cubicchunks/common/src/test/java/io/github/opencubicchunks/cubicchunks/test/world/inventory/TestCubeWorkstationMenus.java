package io.github.opencubicchunks.cubicchunks.test.world.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevel;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.UseCooldown;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native block interaction, menu creation and range checks over real cube storage. Recipe execution is not simulated here. */
class TestCubeWorkstationMenus extends BaseTest {
    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void craftingAndStonecuttingUseFullBlockPosition(int y) throws Exception {
        World world = new World(y, true);
        world.check(Blocks.CRAFTING_TABLE, MenuType.CRAFTING);
        world.check(Blocks.STONECUTTER, MenuType.STONECUTTER);
        world.noColumnLookup();
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void equipmentWorkstationsPreserveNativeRangeAndBlockValidation(int y) throws Exception {
        World world = new World(y, true);
        world.check(Blocks.ANVIL, MenuType.ANVIL);
        world.check(Blocks.GRINDSTONE, MenuType.GRINDSTONE);
        world.check(Blocks.SMITHING_TABLE, MenuType.SMITHING);
        world.noColumnLookup();
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void loomAndCartographyOpenThroughNativeBlockInteraction(int y) throws Exception {
        World world = new World(y, true);
        world.check(Blocks.LOOM, MenuType.LOOM);
        world.check(Blocks.CARTOGRAPHY_TABLE, MenuType.CARTOGRAPHY_TABLE);
        world.noColumnLookup();
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void enchantingAndBeaconMenusResolveTheirFullHeightBlockEntities(int y) throws Exception {
        World world = new World(y, true);
        world.check(Blocks.ENCHANTING_TABLE, MenuType.ENCHANTMENT);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                world.put(world.pos.offset(x, -1, z), Blocks.IRON_BLOCK);
            }
        }
        var base = BeaconBlockEntity.class.getDeclaredMethod("updateBase", Level.class, int.class, int.class, int.class);
        base.setAccessible(true);
        assertEquals(1, base.invoke(null, world.level, world.pos.getX(), world.pos.getY(), world.pos.getZ()), "Native base validation at full Y");
        world.check(Blocks.BEACON, MenuType.BEACON);
        world.noColumnLookup();
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void brewingMenuRejectsDistanceRemovalAndReplacementContainerIdentity(int y) throws Exception {
        World world = new World(y, true);
        world.check(Blocks.BREWING_STAND, MenuType.BREWING_STAND);
        AbstractContainerMenu oldMenu = world.open(Blocks.BREWING_STAND);
        BlockEntity oldEntity = world.level.getBlockEntity(world.pos);
        world.cube(world.pos).removeBlockEntity(world.pos);
        world.cube(world.pos).setBlockEntity(new BrewingStandBlockEntity(world.pos, Blocks.BREWING_STAND.defaultBlockState()));
        assertTrue(world.level.getBlockEntity(world.pos) != oldEntity);
        assertFalse(oldMenu.stillValid(world.player), "Replacing the stand at the same coordinate must invalidate its old inventory menu");
        assertTrue(world.open(Blocks.BREWING_STAND).stillValid(world.player));
        world.noColumnLookup();
    }

    @Test
    void ordinaryWorldKeepsNativeChunkLookupAndRejectsOutOfHeightWorkstations() throws Exception {
        World world = new World(96, false);
        world.check(Blocks.CRAFTING_TABLE, MenuType.CRAFTING);
        verify(world.level, atLeastOnce()).getChunk(1, 1);
        verify((CubicLevel) world.level, never()).cc_getCubeAt(any());
        assertTrue(world.level.getBlockState(new BlockPos(31, 32000, 31)).isAir());
    }

    @Test
    void nativeDurabilityBreaksExactlyAtLimitAndPreservesNamedComponentsInBreakCallback() throws Exception {
        World world = new World(32000, true);
        ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
        Component name = Component.literal("Cube seam pickaxe");
        tool.set(DataComponents.CUSTOM_NAME, name);
        tool.setDamageValue(tool.getMaxDamage() - 2);
        List<ItemStack> broken = new ArrayList<>();
        tool.hurtAndBreak(1, world.level, null, broken::add);
        assertFalse(tool.isEmpty());
        assertEquals(tool.getMaxDamage() - 1, tool.getDamageValue());
        assertTrue(broken.isEmpty());
        tool.hurtAndBreak(1, world.level, null, broken::add);
        assertTrue(tool.isEmpty());
        assertEquals(1, broken.size());
        assertEquals(name, broken.getFirst().get(DataComponents.CUSTOM_NAME));
        assertEquals(broken.getFirst().getMaxDamage(), broken.getFirst().getDamageValue());
    }

    @Test
    void nativeCooldownComponentsShareOnlyTheirGroupAndExpireOnTick() {
        ItemCooldowns cooldowns = new ItemCooldowns();
        Identifier shared = Identifier.fromNamespaceAndPath("cubicchunks", "menu_test");
        UseCooldown component = new UseCooldown(0.25f, Optional.of(shared));
        ItemStack pearl = new ItemStack(Items.ENDER_PEARL);
        ItemStack apple = new ItemStack(Items.APPLE);
        pearl.set(DataComponents.USE_COOLDOWN, component);
        apple.set(DataComponents.USE_COOLDOWN, component);
        cooldowns.addCooldown(pearl, component.ticks());
        assertTrue(cooldowns.isOnCooldown(apple));
        assertFalse(cooldowns.isOnCooldown(new ItemStack(Items.STONE)));
        for (int tick = 0; tick < component.ticks() - 1; tick++) {
            cooldowns.tick();
        }
        assertTrue(cooldowns.isOnCooldown(pearl));
        cooldowns.tick();
        assertFalse(cooldowns.isOnCooldown(pearl));
        assertFalse(cooldowns.isOnCooldown(apple));
    }

    @Test
    void nativeInventoryDoesNotMergeDistinctComponentsAndSplitRetainsThem() throws Exception {
        World world = new World(-32000, true);
        ItemStack first = new ItemStack(Items.STONE, 10);
        first.set(DataComponents.CUSTOM_NAME, Component.literal("upper"));
        world.inventory.setItem(0, first);
        ItemStack matching = first.copyWithCount(5);
        assertTrue(world.inventory.add(matching));
        assertEquals(15, world.inventory.getItem(0).getCount());
        ItemStack other = new ItemStack(Items.STONE, 4);
        other.set(DataComponents.CUSTOM_NAME, Component.literal("lower"));
        assertTrue(world.inventory.add(other));
        assertEquals(15, world.inventory.getItem(0).getCount());
        assertEquals(4, world.inventory.getItem(1).getCount());
        ItemStack split = world.inventory.removeItem(1, 2);
        assertEquals(2, split.getCount());
        assertEquals(Component.literal("lower"), split.get(DataComponents.CUSTOM_NAME));
        assertEquals(2, world.inventory.getItem(1).getCount());
    }

    private static final class World {
        final ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
        final Player player = mock();
        final Inventory inventory = new Inventory(player, new EntityEquipment());
        final Map<CubePos, LevelCube> cubes = new HashMap<>();
        final Map<ChunkPos, LevelChunk> chunks = new HashMap<>();
        final BlockPos pos;
        final boolean cubic;
        Vec3 eyes;
        AbstractContainerMenu opened;

        World(int y, boolean cubic) throws Exception {
            this.cubic = cubic;
            pos = new BlockPos(31, y - 1, 31);
            Misc.setupLevelHeight(level, -64, 384);
            CubeMobTestWorld.setField(Level.class, level, "cc_isCubic", cubic);
            CubeMobTestWorld.setField(Level.class, level, "thread", Thread.currentThread());
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(cubic);
            doCallRealMethod().when(level).isInsideBuildHeight(anyInt());
            doCallRealMethod().when(level).isInsideBuildHeight(any(BlockPos.class));
            doCallRealMethod().when(level).isOutsideBuildHeight(anyInt());
            doCallRealMethod().when(level).isOutsideBuildHeight(any(BlockPos.class));
            doCallRealMethod().when(level).isInValidBounds(any());
            when(((CubicLevel) level).cc_getCubeAt(any())).thenAnswer(call -> cube(call.getArgument(0)));
            when(level.getChunk(anyInt(), anyInt())).thenAnswer(call -> chunk(new ChunkPos(call.getArgument(0), call.getArgument(1))));
            doCallRealMethod().when(level).getBlockState(any());
            doCallRealMethod().when(level).getBlockEntity(any());
            var recipes = level.recipeAccess();
            when(recipes.propertySet(any())).thenReturn(RecipePropertySet.EMPTY);
            var registries = level.registryAccess();
            when(player.registryAccess()).thenReturn(registries);
            when(player.level()).thenReturn(level);
            when(player.getInventory()).thenReturn(inventory);
            when(player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
            when(player.getOffhandItem()).thenReturn(ItemStack.EMPTY);
            when(player.blockInteractionRange()).thenReturn(4.5);
            when(player.getEyePosition()).thenAnswer(call -> eyes);
            doCallRealMethod().when(player).isWithinBlockInteractionRange(any(), anyDouble());
            when(player.openMenu(any())).thenAnswer(call -> {
                MenuProvider provider = call.getArgument(0);
                opened = provider.createMenu(7, inventory, player);
                return OptionalInt.of(7);
            });
            near();
        }

        LevelCube cube(BlockPos target) {
            return cubes.computeIfAbsent(CubePos.from(target), key -> {
                LevelCube cube = new LevelCube(level, key);
                cube.setLoaded(true);
                return cube;
            });
        }

        LevelChunk chunk(ChunkPos target) {
            return chunks.computeIfAbsent(target, key -> new LevelChunk(level, key));
        }

        void put(BlockPos target, Block block) {
            BlockState state = block.defaultBlockState();
            if (cubic) {
                LevelCube cube = cube(target);
                cube.removeBlockEntity(target);
                cube.getSection(Coords.blockToIndex(target)).setBlockState(target.getX() & 15, target.getY() & 15, target.getZ() & 15, state);
                if (block instanceof EntityBlock entityBlock) {
                    cube.setBlockEntity(entityBlock.newBlockEntity(target, state));
                }
            } else {
                LevelChunk chunk = chunk(ChunkPos.containing(target));
                chunk.getSection(chunk.getSectionIndex(target.getY())).setBlockState(target.getX() & 15, target.getY() & 15, target.getZ() & 15,
                        state);
            }
        }

        AbstractContainerMenu open(Block block) {
            put(pos, block);
            near();
            opened = null;
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            BlockState state = level.getBlockState(pos);
            assertTrue(state.is(block), "Native block routing must read the installed workstation before interaction");
            assertTrue(state.useWithoutItem(level, player, hit).consumesAction(), block.toString());
            assertNotNull(opened, "Native block interaction must invoke the menu provider: " + block);
            return opened;
        }

        void check(Block block, MenuType<?> type) {
            AbstractContainerMenu menu = open(block);
            assertSame(type, menu.getType());
            assertTrue(menu.stillValid(player), "A nearby player across XYZ cube seams must retain " + type);
            eyes = Vec3.atCenterOf(pos).add(0, 16, 0);
            assertFalse(menu.stillValid(player), "Full-Y distance must not collapse to local cube Y");
            eyes = Vec3.atCenterOf(pos).add(16, 0, 0);
            assertFalse(menu.stillValid(player), "Native horizontal interaction range must remain enforced");
            near();
            assertTrue(menu.stillValid(player));
            put(pos, Blocks.AIR);
            assertFalse(menu.stillValid(player), "Removing the workstation must invalidate its menu");
        }

        void near() {
            eyes = new Vec3(32.1, pos.getY() + 1.5, 32.1);
        }

        void noColumnLookup() {
            verify(level, never()).getChunk(anyInt(), anyInt());
        }
    }
}
