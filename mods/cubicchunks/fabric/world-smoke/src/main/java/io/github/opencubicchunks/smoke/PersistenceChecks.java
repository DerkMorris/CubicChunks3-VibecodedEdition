package io.github.opencubicchunks.smoke;

import java.util.UUID;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;

/** Save fixtures are installed without ticking. A different server process must restore and resume native mechanics. */
public final class PersistenceChecks {
    private static final int ORIGIN = 512;
    private static final int COOKED = 73;
    private static final int COOK_TOTAL = 200;
    private static final int LIT_REMAINING = 900;
    private static final int LIT_TOTAL = 1600;
    private static final int HOPPER_COOLDOWN = 23;
    private static final int FLUID_DELAY = 80;
    private static final int TOOL_DAMAGE = 37;
    private static final int TIMEOUT = 1400;
    private final ServerLevel level;
    private final Snapshot snapshot;
    private int ticks;
    private int activeTicks;
    private boolean itemVerified;
    private boolean furnaceAdvanced;
    private boolean hopperTransferred;
    private boolean fluidAdvanced;
    private boolean done;

    public record Snapshot(int y, UUID itemId) {}

    public PersistenceChecks(ServerLevel level, Snapshot snapshot) {
        this.level = level;
        this.snapshot = snapshot;
    }

    public static String encode(Snapshot snapshot) {
        return "1:" + snapshot.y + ":" + snapshot.itemId;
    }

    public static Snapshot decode(String encoded) {
        String[] fields = encoded.split(":", -1);
        require(fields.length == 3 && fields[0].equals("1"), "Unsupported persistence snapshot");
        return new Snapshot(Integer.parseInt(fields[1]), UUID.fromString(fields[2]));
    }

    /** Called at the end of a server tick immediately before save/stop; no native tick methods are invoked here. */
    public static Snapshot prepare(ServerLevel level, int y) {
        load(level, y);
        for (BlockPos cursor : BlockPos.betweenClosed(at(y, 0, -1, 0), at(y, 16, 6, 16))) {
            boolean shell = cursor.getY() == y - 1 || cursor.getY() == y + 6 || cursor.getX() == ORIGIN || cursor.getX() == ORIGIN + 16
                    || cursor.getZ() == ORIGIN || cursor.getZ() == ORIGIN + 16;
            if (cursor.getX() == ORIGIN && cursor.getZ() == ORIGIN && cursor.getY() >= y && cursor.getY() <= y + 2) {
                shell = false;
            }
            level.setBlock(cursor, (shell ? Blocks.BEDROCK : Blocks.AIR).defaultBlockState(), 2);
        }
        BlockPos furnacePos = furnace(y);
        level.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.LIT, true), 2);
        var furnace = (FurnaceBlockEntity) level.getBlockEntity(furnacePos);
        require(furnace != null, "Missing prepared furnace");
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, 2));
        furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 3));
        CompoundTag cooking = furnace.saveWithoutMetadata(level.registryAccess());
        cooking.putInt("cooking_time_spent", COOKED);
        cooking.putInt("cooking_total_time", COOK_TOTAL);
        cooking.putInt("lit_time_remaining", LIT_REMAINING);
        cooking.putInt("lit_total_time", LIT_TOTAL);
        furnace.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), cooking));
        furnace.setChanged();

        level.setBlock(chest(y), Blocks.CHEST.defaultBlockState(), 2);
        level.setBlock(hopper(y), Blocks.HOPPER.defaultBlockState(), 2);
        ((Container) level.getBlockEntity(chest(y))).setItem(0, new ItemStack(Items.EMERALD, 2));
        var hopper = (HopperBlockEntity) level.getBlockEntity(hopper(y));
        require(hopper != null, "Missing prepared hopper");
        hopper.setItem(0, new ItemStack(Items.COPPER_INGOT, 5));
        CompoundTag transfer = hopper.saveWithoutMetadata(level.registryAccess());
        transfer.putInt("TransferCooldown", HOPPER_COOLDOWN);
        hopper.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), transfer));
        hopper.setChanged();
        level.getBlockEntity(chest(y)).setChanged();

        // A narrow bedrock shaft gives the scheduled fluid tick an unambiguous initially dry destination.
        BlockPos water = water(y);
        for (BlockPos cursor : BlockPos.betweenClosed(water.offset(-1, -3, -1), water.offset(1, 0, 1))) {
            if (cursor.getX() != water.getX() || cursor.getZ() != water.getZ()) {
                level.setBlock(cursor, Blocks.BEDROCK.defaultBlockState(), 2);
            }
        }
        level.setBlock(water, Blocks.WATER.defaultBlockState(), 2);
        level.getFluidTicks().clearArea(new BoundingBox(ORIGIN, y - 1, ORIGIN, ORIGIN + 16, y + 6, ORIGIN + 16));
        level.scheduleTick(water, Fluids.WATER, FLUID_DELAY);

        ItemStack stack = tool(y);
        var item = new ItemEntity(level, ORIGIN + 14.5, y + 1, ORIGIN + 6.5, stack, 0, 0, 0);
        item.setNoGravity(true);
        item.setNeverPickUp();
        require(level.addFreshEntity(item), "Native persistent item registration");
        Snapshot snapshot = new Snapshot(y, item.getUUID());
        verifySaved(level, snapshot);
        verifyItem(level, snapshot, item);
        require(item.getAge() == 0, "Prepared item must not have ticked before save");
        System.out.println("CC_PERSISTENCE_PREPARED y=" + y + " item=" + item.getUUID() + " cookProgress=" + COOKED + " hopperCooldown="
                + HOPPER_COOLDOWN + " fluidDelay=" + FLUID_DELAY + " ticksExecuted=0");
        return snapshot;
    }

    /** Loads FULL cubes only and checks the snapshot before any player activates this far-away region. */
    public static void verifySaved(ServerLevel level, Snapshot snapshot) {
        int y = snapshot.y;
        load(level, y);
        require(!level.shouldTickBlocksAt(furnace(y)) && !level.shouldTickBlocksAt(hopper(y)),
                "Persistence snapshot must be verified before its region is activated");
        var furnace = level.getBlockEntity(furnace(y));
        var hopper = level.getBlockEntity(hopper(y));
        require(furnace instanceof FurnaceBlockEntity && hopper instanceof HopperBlockEntity, "Saved native block entity types");
        CompoundTag cooking = furnace.saveWithoutMetadata(level.registryAccess());
        require(cooking.getIntOr("cooking_time_spent", -1) == COOKED && cooking.getIntOr("cooking_total_time", -1) == COOK_TOTAL
                && cooking.getIntOr("lit_time_remaining", -1) == LIT_REMAINING && cooking.getIntOr("lit_total_time", -1) == LIT_TOTAL,
                "Saved native furnace progress/fuel changed: " + cooking);
        Container furnaceInventory = (Container) furnace;
        require(count(furnaceInventory, Items.RAW_IRON) == 2 && count(furnaceInventory, Items.IRON_INGOT) == 3
                && furnaceInventory.getItem(1).isEmpty(), "Saved furnace inventory");
        CompoundTag transfer = hopper.saveWithoutMetadata(level.registryAccess());
        require(transfer.getIntOr("TransferCooldown", -1) == HOPPER_COOLDOWN && count((Container) hopper, Items.COPPER_INGOT) == 5,
                "Saved hopper cooldown/inventory changed: " + transfer);
        Container chest = (Container) level.getBlockEntity(chest(y));
        require(chest != null && count(chest, Items.EMERALD) == 2 && count(chest, Items.COPPER_INGOT) == 0, "Saved destination inventory");
        require(level.getFluidState(water(y)).isSource() && level.getFluidState(water(y).below()).isEmpty(),
                "Water ran before snapshot verification");
        require(level.getFluidTicks().hasScheduledTick(water(y), Fluids.WATER), "Saved scheduled water tick is missing");
        LevelCube cube = cube(level, water(y));
        // Before activation native LevelChunkTicks keeps restored entries as SavedTicks, outside getAll()'s active queue.
        // The normal serialization snapshot includes both representations without unpacking or executing either.
        var pending = cube.getTicksForSerialization(level.getGameTime()).fluids().stream()
                .filter(tick -> tick.pos().equals(water(y)) && tick.type() == Fluids.WATER).toList();
        require(pending.size() == 1, "Expected exactly one native pending water tick");
        long remaining = pending.getFirst().delay();
        require(remaining == FLUID_DELAY, "Saved scheduled delay changed: expected=" + FLUID_DELAY + " actual=" + remaining);
        System.out.println("CC_PERSISTENCE_SNAPSHOT_VERIFIED y=" + y + " furnaceProgress=true hopperCooldown=true pendingFluidDelay=" + remaining);
    }

    /** Called once per world tick after the root harness activates this region with a real player. */
    public boolean tick() {
        if (done) {
            return true;
        }
        int y = snapshot.y;
        require(++ticks < TIMEOUT, "Persistence continuation timed out y=" + y + " active=" + activeTicks + " item=" + itemVerified + " furnace="
                + furnaceAdvanced + " hopper=" + hopperTransferred + " fluid=" + fluidAdvanced);
        if (!level.shouldTickBlocksAt(furnace(y)) || !level.shouldTickBlocksAt(hopper(y)) || !level.shouldTickBlocksAt(water(y))) {
            return false;
        }
        activeTicks++;
        if (((CubicServerLevel) level).cc_areCubeEntitiesLoaded(CubePos.from(at(y, 14, 1, 6)))) {
            var entity = level.getEntity(snapshot.itemId);
            require(entity instanceof ItemEntity, "Fresh server did not restore the original item UUID: " + snapshot.itemId);
            verifyItem(level, snapshot, (ItemEntity) entity);
            itemVerified = ((ItemEntity) entity).getAge() > 0;
        }
        var furnace = (FurnaceBlockEntity) level.getBlockEntity(furnace(y));
        var hopper = (HopperBlockEntity) level.getBlockEntity(hopper(y));
        var chest = (Container) level.getBlockEntity(chest(y));
        require(count(furnace, Items.RAW_IRON) + count(furnace, Items.IRON_INGOT) == 5, "Furnace lost/duplicated saved material");
        require(count(hopper, Items.COPPER_INGOT) + count(chest, Items.COPPER_INGOT) == 5 && count(chest, Items.EMERALD) == 2,
                "Hopper lost/duplicated saved contents");
        furnaceAdvanced |= count(furnace, Items.IRON_INGOT) > 3;
        hopperTransferred |= count(chest, Items.COPPER_INGOT) > 0;
        fluidAdvanced |= !level.getFluidState(water(y).below()).isEmpty();
        if (ticks % 100 == 0) {
            System.out.println("CC_PERSISTENCE_PROGRESS y=" + y + " activeTicks=" + activeTicks + " item=" + itemVerified + " iron="
                    + count(furnace, Items.IRON_INGOT) + " copper=" + count(chest, Items.COPPER_INGOT) + " water=" + fluidAdvanced);
        }
        if (itemVerified && furnaceAdvanced && count(furnace, Items.RAW_IRON) == 0 && count(furnace, Items.IRON_INGOT) == 5 && hopperTransferred
                && count(hopper, Items.COPPER_INGOT) == 0 && fluidAdvanced) {
            done = true;
            System.out.println("CC_PERSISTENCE_COMPLETE y=" + y
                    + " freshProcess=true nativeFurnaceResume=true nativeHopperResume=true itemUuidComponents=true scheduledFluidResume=true testBypass=false");
        }
        return done;
    }

    private static ItemStack tool(int y) {
        ItemStack stack = new ItemStack(Items.DIAMOND_PICKAXE);
        stack.setDamageValue(TOOL_DAMAGE);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Persistent pickaxe " + y));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        CompoundTag custom = new CompoundTag();
        custom.putInt("probe_y", y);
        custom.putString("probe", "fresh-process-survival");
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        return stack;
    }

    private static void verifyItem(ServerLevel level, Snapshot snapshot, ItemEntity item) {
        require(item.level() == level && item.getUUID().equals(snapshot.itemId) && Math.abs(item.getY() - (snapshot.y + 1)) < 0.01,
                "Restored item identity/dimension/full Y");
        require(item.getItem().getCount() == 1 && ItemStack.isSameItemSameComponents(tool(snapshot.y), item.getItem()),
                "Restored item lost durability/name/custom data/glint components");
        require(item.isNoGravity() && item.hasPickUpDelay(), "Restored item lifetime/physics flags");
    }

    private static int count(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) {
                count += container.getItem(slot).getCount();
            }
        }
        return count;
    }

    private static void load(ServerLevel level, int y) {
        CubePos first = CubePos.from(at(y, 0, -1, 0));
        CubePos last = CubePos.from(at(y, 16, 6, 16));
        for (int cy = first.getY(); cy <= last.getY(); cy++) {
            ((CubicLevelReader) level).cc_getCube(first.getX(), cy, first.getZ(), ChunkStatus.FULL, true);
        }
    }

    private static LevelCube cube(ServerLevel level, BlockPos pos) {
        CubePos cube = CubePos.from(pos);
        return (LevelCube) ((CubicLevelReader) level).cc_getCube(cube.getX(), cube.getY(), cube.getZ(), ChunkStatus.FULL, true);
    }

    private static BlockPos at(int y, int dx, int dy, int dz) {
        return new BlockPos(ORIGIN + dx, y + dy, ORIGIN + dz);
    }

    private static BlockPos furnace(int y) {
        return at(y, 4, 0, 4);
    }

    private static BlockPos hopper(int y) {
        return at(y, 8, 1, 4);
    }

    private static BlockPos chest(int y) {
        return at(y, 8, 0, 4);
    }

    private static BlockPos water(int y) {
        return at(y, 12, 3, 12);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Persistence probe: " + message);
        }
    }
}
