package io.github.opencubicchunks.smoke;

import java.util.ArrayList;
import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Dedicated/integrated server fixture. Native ticks, block events and item use perform every observed transition. */
public final class BlockMechanicsChecks implements AutoCloseable {
    private final ServerLevel level;
    private final ServerPlayer player;
    private final BlockPos pivot;
    private final List<BlockPos> readiness = new ArrayList<>();
    private final ItemStack originalHand;
    private int ticks;
    private int stage;
    private int stageStarted;
    private boolean observerPulse;
    private Minecart cart;
    private PrimedTnt tnt;
    private double cartStartX;
    private boolean cartMoved;
    private boolean fuseProgressed;
    private boolean complete;

    public BlockMechanicsChecks(ServerLevel level, ServerPlayer player, BlockPos origin) {
        if (CubicChunks.IS_IN_TEST) {
            throw new IllegalStateException("Block mechanics require production ticks");
        }
        this.level = level;
        this.player = player;
        this.pivot = new BlockPos((origin.getX() & ~31) + 31, (origin.getY() & ~31) - 1, (origin.getZ() & ~31) + 31);
        this.originalHand = player.getMainHandItem().copy();
        BlockPos min = at(-16, -3, -12);
        BlockPos max = at(18, 7, 16);
        CubePos first = CubePos.from(min);
        CubePos last = CubePos.from(max);
        for (int x = first.getX() - 1; x <= last.getX() + 1; x++) {
            for (int y = first.getY() - 1; y <= last.getY() + 1; y++) {
                for (int z = first.getZ() - 1; z <= last.getZ() + 1; z++) {
                    ((CubicLevelReader) level).cc_getCube(x, y, z, ChunkStatus.FULL, true);
                }
            }
        }
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            boolean shell = pos.getY() == min.getY() || pos.getY() == max.getY() || pos.getX() == min.getX() || pos.getX() == max.getX()
                    || pos.getZ() == min.getZ() || pos.getZ() == max.getZ();
            level.setBlock(pos, (shell || pos.getY() == pivot.getY() - 1 ? Blocks.BEDROCK : Blocks.AIR).defaultBlockState(), 2);
        }
        for (int x = first.getX(); x <= last.getX(); x++) {
            for (int y = first.getY(); y <= last.getY(); y++) {
                for (int z = first.getZ(); z <= last.getZ(); z++) {
                    readiness.add(new BlockPos(x * 32 + 16, y * 32 + 16, z * 32 + 16));
                }
            }
        }
        player.teleportTo(pivot.getX() - 10.5, pivot.getY(), pivot.getZ() + .5);
    }

    /** Called once after each ordinary server tick. Returns true only after all phases pass. */
    public boolean tick() {
        if (complete) {
            return true;
        }
        if (++ticks > 1600) {
            throw new IllegalStateException("Block mechanics timeout " + diagnostic());
        }
        if (ticks % 100 == 0) {
            System.out.println("CC_BLOCK_MECHANICS_PROGRESS " + diagnostic());
        }
        if (stage == 0) {
            if (readiness.stream().allMatch(level::isPositionEntityTicking)) {
                setupRedstone();
                advance();
            }
            return false;
        }
        int elapsed = ticks - stageStarted;
        if (stage == 1 && elapsed >= 10) {
            powerPistons(true);
            fillChest(true);
            observerPulse = false;
            put(at(0, 0, 1), Blocks.DIRT.defaultBlockState());
            advance();
        } else if (stage == 2) {
            observerPulse |= state(at(0, 0, 0)).getValue(BlockStateProperties.POWERED);
            if (elapsed >= 12) {
                require(state(at(-1, 0, -6)).getValue(BlockStateProperties.EXTENDED), "normal piston failed to extend");
                require(state(at(1, 0, -6)).is(Blocks.STONE), "normal piston failed to move block across X cube seam");
                require(state(at(5, -1, 3)).getValue(BlockStateProperties.EXTENDED), "sticky piston failed to extend");
                require(state(at(5, 1, 3)).is(Blocks.GOLD_BLOCK), "sticky piston failed to move block across Y cube seam");
                require(observerPulse, "observer did not emit its scheduled pulse across Z seam");
                require(output() == 15 && state(at(11, 0, -5)).getValue(BlockStateProperties.LIT), "comparator full inventory output");
                powerPistons(false);
                fillChest(false);
                advance();
            }
        } else if (stage == 3 && elapsed >= 12) {
            require(!state(at(-1, 0, -6)).getValue(BlockStateProperties.EXTENDED), "normal piston failed to retract");
            require(state(at(1, 0, -6)).is(Blocks.STONE), "normal piston must leave its pushed block");
            require(state(at(5, 0, 3)).is(Blocks.GOLD_BLOCK) && state(at(5, 1, 3)).isAir(), "sticky piston failed to pull block back across seam");
            require(!state(at(0, 0, 0)).getValue(BlockStateProperties.POWERED), "observer pulse never ended");
            require(output() == 0 && !state(at(11, 0, -5)).getValue(BlockStateProperties.LIT), "comparator did not clear after inventory removal");
            setupFluids();
            advance();
        } else if (stage == 4 && elapsed >= 25) {
            require(state(at(-10, 0, 5)).is(Blocks.OBSIDIAN), "water must convert lava source into obsidian");
            require(state(at(-6, 0, 5)).is(Blocks.COBBLESTONE), "water must convert flowing lava into cobblestone");
            require(level.getFluidState(at(-13, 0, 8)).is(Fluids.FLOWING_WATER), "native water flow did not cross Y seam");
            bucket(true);
            require(state(at(-10, 0, -4)).getValue(BlockStateProperties.WATERLOGGED), "water bucket did not waterlog stairs");
            advance();
        } else if (stage == 5 && elapsed >= 10) {
            bucket(false);
            require(!state(at(-10, 0, -4)).getValue(BlockStateProperties.WATERLOGGED), "empty bucket did not drain waterlogged stairs");
            require(player.getMainHandItem().is(Items.WATER_BUCKET), "native bucket pickup did not return filled bucket");
            plants();
            player.setItemInHand(InteractionHand.MAIN_HAND, originalHand.copy());
            player.teleportTo(pivot.getX() - 10.5, pivot.getY(), pivot.getZ() + .5);
            setupEntities();
            advance();
        } else if (stage == 6) {
            cartMoved |= cart != null && cart.isOnRails() && cart.getX() - cartStartX > 4;
            if (tnt == null) {
                tnt = level.getEntitiesOfClass(PrimedTnt.class, new AABB(Vec3.atLowerCornerOf(at(10, -1, 10)), Vec3.atLowerCornerOf(at(15, 4, 15))))
                        .stream().findFirst().orElse(null);
            }
            fuseProgressed |= tnt != null && tnt.getFuse() < 60;
            if (elapsed >= 110) {
                require(cartMoved, "minecart did not travel along native powered rails across X seam");
                require(tnt != null && fuseProgressed && tnt.isRemoved(), "TNT fuse/explosion did not run through entity ticks");
                require(state(at(12, 0, 12)).isAir(), "native TNT explosion did not destroy dirt marker");
                commands();
                complete = true;
                close();
                System.out.println("CC_BLOCK_MECHANICS_PHASE y=" + pivot.getY() + " ticks=" + ticks
                        + " pistonPushRetract=true stickyPull=true observerPulse=true comparatorInventory=true waterLava=true waterloggingBucket=true"
                        + " seedBonemealHarvest=true poweredRailMinecart=true tntFuseExplosion=true nativeCommands=true testBypass=false");
            }
        }
        return complete;
    }

    private void setupRedstone() {
        put(at(-1, 0, -6), Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
        put(at(0, 0, -6), Blocks.STONE.defaultBlockState());
        put(at(5, -1, 3), Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        put(at(5, 0, 3), Blocks.GOLD_BLOCK.defaultBlockState());
        put(at(0, 0, 1), Blocks.STONE.defaultBlockState());
        put(at(0, 0, 0), Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH));
        put(at(0, 0, -1), Blocks.REDSTONE_LAMP.defaultBlockState());
        put(at(9, 0, -5), Blocks.CHEST.defaultBlockState());
        put(at(10, 0, -5), Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        put(at(11, 0, -5), Blocks.REDSTONE_LAMP.defaultBlockState());
    }

    private void powerPistons(boolean on) {
        BlockState power = (on ? Blocks.REDSTONE_BLOCK : Blocks.AIR).defaultBlockState();
        put(at(-2, 0, -6), power);
        put(at(5, -2, 3), power);
    }

    private void fillChest(boolean full) {
        Container chest = (Container) level.getBlockEntity(at(9, 0, -5));
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, full ? new ItemStack(Items.STONE, 64) : ItemStack.EMPTY);
        }
        chest.setChanged();
    }

    private int output() {
        return ((ComparatorBlockEntity) level.getBlockEntity(at(10, 0, -5))).getOutputSignal();
    }

    private void setupFluids() {
        for (int x : new int[] { -10, -6 }) {
            for (int y = 0; y <= 1; y++) {
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    put(at(x, y, 5).relative(side), Blocks.GLASS.defaultBlockState());
                }
            }
            put(at(x, 0, 5), Blocks.LAVA.defaultBlockState().setValue(BlockStateProperties.LEVEL, x == -10 ? 0 : 1));
            put(at(x, 1, 5), Blocks.WATER.defaultBlockState());
        }
        for (int y = 0; y <= 3; y++) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                put(at(-13, y, 8).relative(side), Blocks.GLASS.defaultBlockState());
            }
        }
        put(at(-13, 3, 8), Blocks.WATER.defaultBlockState());
        put(at(-10, 0, -4), Blocks.OAK_STAIRS.defaultBlockState());
        for (Direction side : Direction.Plane.HORIZONTAL) {
            put(at(-10, 0, -4).relative(side), Blocks.GLASS.defaultBlockState());
        }
        put(at(-7, 0, -9), Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7));
        put(at(-8, 1, -9), Blocks.GLOWSTONE.defaultBlockState());
    }

    private void plants() {
        BlockPos soil = at(-7, 0, -9);
        BlockPos crop = soil.above();
        player.teleportTo(soil.getX() + .5, soil.getY() + 1, soil.getZ() + 2.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT_SEEDS, 2));
        System.out.println("CC_BLOCK_PLANT_BEFORE " + plantDiagnostic());
        useOn(soil);
        require(state(crop).is(Blocks.WHEAT) && player.getMainHandItem().getCount() == 1, "native seed placement across Y seam");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BONE_MEAL, 8));
        for (int attempt = 0; attempt < 4 && !((CropBlock) Blocks.WHEAT).isMaxAge(state(crop)); attempt++) {
            useOn(crop);
        }
        require(((CropBlock) Blocks.WHEAT).isMaxAge(state(crop)) && player.getMainHandItem().getCount() < 8,
                "native bone meal growth and consumption");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        require(player.gameMode.destroyBlock(crop) && state(crop).isAir(), "native crop harvest");
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(crop).inflate(2));
        require(drops.stream().anyMatch(item -> item.getItem().is(Items.WHEAT)), "mature crop did not drop wheat");
        drops.forEach(Entity::discard);
    }

    private void useOn(BlockPos target) {
        BlockHitResult hit = new BlockHitResult(Vec3.atBottomCenterOf(target.above()), Direction.UP, target, false);
        var result = player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        require(result.consumesAction(), "native item use on " + target + " result=" + result + " " + plantDiagnostic());
    }

    private String plantDiagnostic() {
        BlockPos crop = at(-7, 1, -9);
        return "soil=" + state(crop.below()) + " crop=" + state(crop) + " light=" + level.getRawBrightness(crop, 0) + " blockLight="
                + level.getBrightness(LightLayer.BLOCK, crop) + " skyLight=" + level.getBrightness(LightLayer.SKY, crop) + " survives="
                + Blocks.WHEAT.defaultBlockState().canSurvive(level, crop) + " player=" + player.position() + " mode="
                + player.gameMode.getGameModeForPlayer() + " hand=" + player.getMainHandItem();
    }

    private void bucket(boolean fill) {
        BlockPos target = at(-10, 0, -4);
        player.teleportTo(target.getX() + .5, target.getY() + 2, target.getZ() + .5);
        player.setYRot(0);
        player.setXRot(90);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(fill ? Items.WATER_BUCKET : Items.BUCKET));
        require(player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND).consumesAction(),
                "native bucket use failed");
    }

    private void setupEntities() {
        for (int x = -8; x <= 8; x++) {
            put(at(x, -1, 9), Blocks.REDSTONE_BLOCK.defaultBlockState());
            put(at(x, 0, 9), Blocks.POWERED_RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, RailShape.EAST_WEST));
        }
        cart = EntityTypes.MINECART.create(level, EntitySpawnReason.COMMAND);
        require(cart != null, "minecart creation");
        cart.setInitialPos(pivot.getX() - 6.5, pivot.getY() + .1, pivot.getZ() + 9.5);
        cart.setDeltaMovement(.2, 0, 0);
        cartStartX = cart.getX();
        require(level.addFreshEntity(cart), "minecart registration");
        put(at(12, 0, 12), Blocks.DIRT.defaultBlockState());
        put(at(12, 1, 12), Blocks.TNT.defaultBlockState());
        put(at(13, 1, 12), Blocks.REDSTONE_BLOCK.defaultBlockState());
    }

    private void commands() {
        BlockPos a = at(-3, 3, -7);
        BlockPos b = a.offset(1, 1, 1);
        BlockPos copy = a.offset(5, 0, 0);
        command("fill " + coordinates(a) + " " + coordinates(b) + " minecraft:diamond_block");
        for (BlockPos cell : BlockPos.betweenClosed(a, b)) {
            require(state(cell).is(Blocks.DIAMOND_BLOCK), "native fill command at full Y");
        }
        command("clone " + coordinates(a) + " " + coordinates(b) + " " + coordinates(copy));
        for (BlockPos cell : BlockPos.betweenClosed(copy, copy.offset(1, 1, 1))) {
            require(state(cell).is(Blocks.DIAMOND_BLOCK), "native clone command at full Y");
        }
        BlockPos marker = at(8, 3, -7);
        command("execute positioned " + coordinates(marker) + " run setblock ~ ~ ~ minecraft:emerald_block");
        require(state(marker).is(Blocks.EMERALD_BLOCK), "execute positioned command at full Y");
        command("summon minecraft:armor_stand " + coordinates(marker.above()) + " {Tags:[\"cc_block_command_probe\"],NoGravity:1b}");
        List<Entity> spawned = level.getEntities((Entity) null, new AABB(marker).inflate(3),
                entity -> entity.entityTags().contains("cc_block_command_probe"));
        require(spawned.size() == 1 && spawned.getFirst().getY() == marker.getY() + 1, "summon command full Y");
        command("tp @e[tag=cc_block_command_probe,limit=1] " + coordinates(marker.above(2)));
        require(spawned.getFirst().getY() == marker.getY() + 2, "tp command full Y");
        spawned.forEach(Entity::discard);
    }

    private void command(String command) {
        var source = level.getServer().createCommandSourceStack().withLevel(level);
        level.getServer().getCommands().performPrefixedCommand(source, command);
    }

    private static String coordinates(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private BlockPos at(int x, int y, int z) {
        return pivot.offset(x, y, z);
    }

    private BlockState state(BlockPos pos) {
        return level.getBlockState(pos);
    }

    private void put(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    private void advance() {
        stage++;
        stageStarted = ticks;
    }

    private String diagnostic() {
        return "pivot=" + pivot + " stage=" + stage + " ticks=" + ticks + " ticking="
                + readiness.stream().filter(level::isPositionEntityTicking).count() + "/" + readiness.size() + " cart="
                + (cart == null ? "none" : cart.position()) + " tnt=" + (tnt == null ? "none" : tnt.getFuse());
    }

    private void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalStateException(reason + ": " + diagnostic());
        }
    }

    @Override public void close() {
        if (cart != null) {
            cart.discard();
        }
        if (tnt != null && !tnt.isRemoved()) {
            tnt.discard();
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, originalHand.copy());
    }
}
