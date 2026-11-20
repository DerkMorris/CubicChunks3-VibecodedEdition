package io.github.opencubicchunks.smoke;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Native survival actions shared by the dedicated and integrated runtime harnesses. */
public final class SurvivalChecks {
    private static final int ORIGIN = 32;
    private static final int LAST_STAGE = 11;
    private static final int STAGE_TIMEOUT = 1600;

    private SurvivalChecks() {}

    private static BlockPos pos(int y, int x, int z) {
        return new BlockPos(ORIGIN + x, y, ORIGIN + z);
    }

    private static AABB bounds(BlockPos min, BlockPos max) {
        return new AABB(Vec3.atLowerCornerOf(min), Vec3.atLowerCornerOf(max));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Survival probe: " + message);
        }
    }

    private static void signal(Path directory, String name) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(name), "ready\n");
        } catch (IOException e) {
            throw new IllegalStateException("Survival control signal " + name, e);
        }
    }

    private static int count(Container inventory, Item item) {
        int amount = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) {
                amount += stack.getCount();
            }
        }
        return amount;
    }

    private static boolean recoveredInventory(Container inventory) {
        return count(inventory, Items.DIAMOND) == 3 && count(inventory, Items.COBBLESTONE) == 1 && count(inventory, Items.OAK_PLANKS) == 4
                && count(inventory, Items.IRON_INGOT) == 1 && count(inventory, Items.BREAD) == 1 && count(inventory, Items.ARROW) == 2
                && damagedItem(inventory, Items.DIAMOND_PICKAXE, 1) && damagedItem(inventory, Items.DIAMOND_SWORD, -1)
                && damagedItem(inventory, Items.BOW, 1) && damagedItem(inventory, Items.IRON_CHESTPLATE, -1);
    }

    private static boolean damagedItem(Container inventory, Item item, int expectedDamage) {
        if (count(inventory, item) != 1) {
            return false;
        }
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) {
                return expectedDamage < 0 ? stack.getDamageValue() > 0 : stack.getDamageValue() == expectedDamage;
            }
        }
        return false;
    }

    /** Runs only on the server thread; always resolves the replacement player after native respawn. */
    public static final class ServerChecks {
        private final ServerLevel level;
        private final UUID identity;
        private final Path directory;
        private final int y;
        private final AABB room;
        private int stage;
        private int age;
        private boolean observedDrop;
        private boolean observedArrow;
        private boolean observedSleep;
        private boolean observedDeath;
        private boolean observedDeathXp;
        private boolean observedDeathItems;
        private boolean damageApplied;
        private Mob target;
        private float healthBefore;
        private ServerPlayer deadPlayer;
        private long sleepClock;
        private boolean done;

        public ServerChecks(ServerLevel level, ServerPlayer player, Path directory, int baseY) {
            this.level = level;
            this.identity = player.getUUID();
            this.directory = directory;
            this.y = baseY;
            this.room = bounds(pos(y - 2, -8, -8), pos(y + 9, 9, 17));
            require(player.level() == level, "player must be in the fixture level");
            level.getGameRules().set(GameRules.SPAWN_MOBS, false, level.getServer());
            level.getGameRules().set(GameRules.NATURAL_HEALTH_REGENERATION, false, level.getServer());
            level.getGameRules().set(GameRules.KEEP_INVENTORY, false, level.getServer());
            level.getGameRules().set(GameRules.IMMEDIATE_RESPAWN, false, level.getServer());
            level.getGameRules().set(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 50, level.getServer());
            for (BlockPos cursor : BlockPos.betweenClosed(pos(y - 1, -8, -8), pos(y + 7, 8, 16))) {
                level.setBlock(cursor, cursor.getY() == y - 1 ? Blocks.BEDROCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            }
            level.setBlock(pos(y, 0, 3), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(pos(y, 2, 0), Blocks.CHEST.defaultBlockState(), 3);
            level.setBlock(pos(y, 2, 1), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
            level.setBlock(pos(y, 2, 2), Blocks.FURNACE.defaultBlockState(), 3);
            var bed = Blocks.BED.white().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
            level.setBlock(pos(y, -2, -2), bed.setValue(AbstractBedBlock.PART, BedPart.FOOT), 3);
            level.setBlock(pos(y, -2, -3), bed.setValue(AbstractBedBlock.PART, BedPart.HEAD), 3);
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            for (EquipmentSlot equipment : EquipmentSlot.values()) {
                player.setItemSlot(equipment, ItemStack.EMPTY);
            }
            player.setHealth(20);
            player.getFoodData().setFoodLevel(10);
            player.getFoodData().setSaturation(0);
            give(player, 0, Items.DIAMOND_PICKAXE, 1);
            give(player, 1, Items.BREAD, 2);
            give(player, 2, Items.IRON_CHESTPLATE, 1);
            give(player, 3, Items.DIAMOND_SWORD, 1);
            give(player, 4, Items.BOW, 1);
            give(player, 5, Items.OAK_LOG, 1);
            give(player, 6, Items.RAW_IRON, 1);
            give(player, 7, Items.COAL, 1);
            give(player, 9, Items.ARROW, 3);
            ((Container) level.getBlockEntity(pos(y, 2, 0))).setItem(0, new ItemStack(Items.EMERALD, 5));
            move(player, 0, 0);
            publish(player);
        }

        public void tick() {
            if (done) {
                return;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(identity);
            require(player != null, "primary player disconnected during stage " + stage);
            require(++age < STAGE_TIMEOUT,
                    "server timeout y=" + y + " stage=" + stage + " position=" + player.position() + " menu="
                            + player.containerMenu.getClass().getSimpleName() + " health=" + player.getHealth() + " food="
                            + player.getFoodData().getFoodLevel() + " inventory=" + player.getInventory().getNonEquipmentItems());
            if (age % 200 == 0) {
                System.out.println("CC_SURVIVAL_PROGRESS side=server y=" + y + " stage=" + stage + " age=" + age + " health=" + player.getHealth()
                        + " chest=" + player.getItemBySlot(EquipmentSlot.CHEST) + " chestDamage="
                        + player.getItemBySlot(EquipmentSlot.CHEST).getDamageValue() + " damageApplied=" + damageApplied);
            }
            switch (stage) {
                case 0 -> mine(player);
                case 1 -> {
                    if (ack() && count(player.getInventory(), Items.BREAD) == 1) {
                        require(player.getFoodData().getFoodLevel() == 15 && player.getFoodData().getSaturationLevel() > 0,
                                "native bread nutrition/saturation");
                        next(player);
                    }
                }
                case 2 -> armor(player);
                case 3 -> melee(player);
                case 4 -> bow(player);
                case 5 -> {
                    Container chest = (Container) level.getBlockEntity(pos(y, 2, 0));
                    if (ack()) {
                        require(count(chest, Items.EMERALD) == 5 && count(player.getInventory(), Items.EMERALD) == 0,
                                "chest round trip must conserve all five emeralds");
                        next(player);
                    }
                }
                case 6 -> {
                    if (ack()) {
                        require(count(player.getInventory(), Items.OAK_PLANKS) == 4 && count(player.getInventory(), Items.OAK_LOG) == 0,
                                "native crafting must consume one log and produce four planks");
                        next(player);
                    }
                }
                case 7 -> {
                    if (ack()) {
                        Container furnace = (Container) level.getBlockEntity(pos(y, 2, 2));
                        require(count(player.getInventory(), Items.IRON_INGOT) == 1 && count(player.getInventory(), Items.RAW_IRON) == 0
                                && count(player.getInventory(), Items.COAL) == 0 && furnace.getItem(0).isEmpty() && furnace.getItem(2).isEmpty(),
                                "native furnace cook, fuel consumption and output extraction");
                        next(player);
                    }
                }
                case 8 -> sleep(player);
                case 9 -> death(player);
                case 10 -> recovery(player);
                default -> throw new IllegalStateException("Unknown survival server stage " + stage);
            }
        }

        public boolean isDone() {
            return done;
        }

        private void mine(ServerPlayer player) {
            observedDrop |= !level.getEntitiesOfClass(ItemEntity.class, room, item -> item.getItem().is(Items.COBBLESTONE)).isEmpty();
            if (observedDrop && count(player.getInventory(), Items.COBBLESTONE) == 0) {
                move(player, 0, 2.5);
            }
            if (ack()) {
                require(observedDrop && level.getBlockState(pos(y, 0, 3)).isAir() && count(player.getInventory(), Items.COBBLESTONE) == 1,
                        "stone must produce a real item entity which is picked up natively");
                require(player.getInventory().getItem(0).getDamageValue() == 1, "pickaxe must lose one durability");
                next(player);
            }
        }

        private void armor(ServerPlayer player) {
            ItemStack armor = player.getItemBySlot(EquipmentSlot.CHEST);
            if (armor.is(Items.IRON_CHESTPLATE) && !damageApplied && age > 30) {
                require(player.getInventory().getItem(2).isEmpty() && count(player.getInventory(), Items.IRON_CHESTPLATE) == 1,
                        "native equip must move the chestplate from its hotbar slot to the equipment slot without duplication");
                healthBefore = player.getHealth();
                // minecraft:generic is explicitly in BYPASSES_ARMOR. Use a real controlled mob source instead.
                Mob attacker = EntityTypes.HUSK.create(level, EntitySpawnReason.TRIGGERED);
                require(attacker != null, "controlled armor attacker creation");
                attacker.setNoAi(true);
                attacker.setPos(ORIGIN - 1.5, y, ORIGIN + 0.5);
                require(level.addFreshEntity(attacker), "controlled armor attacker registration");
                damageApplied = player.hurtServer(level, level.damageSources().mobAttack(attacker), 8);
                attacker.discard();
                require(damageApplied, "controlled armor damage must be accepted");
                require(player.getHealth() < healthBefore && player.getHealth() > healthBefore - 8 && armor.getDamageValue() > 0,
                        "native mob damage must be reduced by armor and damage it");
            }
            if (ack()) {
                require(damageApplied && player.getHealth() < healthBefore && player.getHealth() > healthBefore - 8 && armor.getDamageValue() > 0,
                        "armor must reduce native damage and lose durability");
                next(player);
            }
        }

        private void melee(ServerPlayer player) {
            if (!target.isAlive() && ack()) {
                require(player.getInventory().getItem(3).getDamageValue() > 0, "native melee must consume sword durability");
                require(!level.getEntitiesOfClass(ItemEntity.class, room, item -> item.getItem().is(Items.BEEF)).isEmpty()
                        || count(player.getInventory(), Items.BEEF) > 0, "player melee kill must produce native cow loot");
                next(player);
            }
        }

        private void bow(ServerPlayer player) {
            observedArrow |= !level.getEntitiesOfClass(Arrow.class, room, arrow -> arrow.getOwner() == player).isEmpty();
            if (target.getHealth() < healthBefore && ack()) {
                require(observedArrow && player.getInventory().getItem(4).getDamageValue() == 1 && count(player.getInventory(), Items.ARROW) == 2,
                        "native bow must spawn an owned arrow, consume one arrow and damage the target");
                target.discard();
                next(player);
            }
        }

        private void sleep(ServerPlayer player) {
            observedSleep |= player.isSleeping();
            if (observedSleep && !player.isSleeping() && ack()) {
                require(level.getOverworldClockTime() > sleepClock + 1000, "native sleep must skip the night");
                require(player.getRespawnConfig() != null, "bed interaction must establish a respawn position");
                next(player);
            }
        }

        private void death(ServerPlayer player) {
            if (!observedDeath && age > 30 && Files.exists(directory.resolve("death-client-ready"))) {
                deadPlayer = player;
                require(player.hurtServer(level, level.damageSources().genericKill(), Float.MAX_VALUE), "native fatal damage must be accepted");
                observedDeath = !player.isAlive();
                require(observedDeath, "player must die from fatal native damage");
                observedDeathItems = !level.getEntitiesOfClass(ItemEntity.class, room, item -> item.getItem().is(Items.DIAMOND)).isEmpty();
                observedDeathXp = !level.getEntitiesOfClass(ExperienceOrb.class, room).isEmpty();
                require(observedDeathItems && observedDeathXp, "death must drop the inventory marker and experience orbs");
                signal(directory, "death-observed");
            }
            if (observedDeath && player != deadPlayer && player.isAlive() && ack()) {
                require(Math.abs(player.getY() - y) < 3 && player.blockPosition().distSqr(pos(y, -2, -3)) < 36,
                        "native respawn must use the high/low bed position");
                require(count(player.getInventory(), Items.DIAMOND) == 0 && player.totalExperience == 0 && player.experienceLevel == 0,
                        "keepInventory=false must clear inventory and XP on the new player");
                require(player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "respawn must retain survival mode");
                require(player.getLastDeathLocation().equals(java.util.Optional.of(GlobalPos.of(level.dimension(), pos(y, 0, 6)))),
                        "replacement server player must retain the full death position");
                next(player);
            }
        }

        private void recovery(ServerPlayer player) {
            if (age % 10 == 0) {
                // Native death throws items in different directions. Visit the actual entities, leaving pickup delay,
                // collisions, inventory insertion and orb attraction to normal server ticks.
                var items = level.getEntitiesOfClass(ItemEntity.class, room);
                var orbs = level.getEntitiesOfClass(ExperienceOrb.class, room);
                if (!items.isEmpty()) {
                    var item = items.getFirst();
                    player.teleportTo(item.getX(), y, item.getZ());
                } else if (!orbs.isEmpty()) {
                    var orb = orbs.getFirst();
                    player.teleportTo(orb.getX(), y, orb.getZ());
                }
            }
            if (ack()) {
                require(count(player.getInventory(), Items.DIAMOND) == 3 && player.totalExperience >= 35,
                        "respawned player must natively recover inventory and experience entities");
                require(recoveredInventory(player.getInventory()),
                        "all consumed/produced items and damaged equipment must survive death as native drops without duplication");
                done = true;
                stage = LAST_STAGE;
                publish(player);
                System.out.println("CC_SURVIVAL_SERVER_COMPLETE y=" + y
                        + " mining=true drops=true pickup=true durability=true food=true armor=true melee=true bow=true"
                        + " chest=true crafting=true furnace=true sleep=true bedRespawn=true deathInventory=true experience=true testBypass=false");
            }
        }

        private void next(ServerPlayer player) {
            stage++;
            age = 0;
            switch (stage) {
                case 1, 2, 5, 6, 7 -> move(player, 0, 0);
                case 3 -> {
                    move(player, 0, 0);
                    target = EntityTypes.COW.create(level, EntitySpawnReason.TRIGGERED);
                    spawnTarget(0, 2.5);
                }
                case 4 -> {
                    move(player, 0, 0);
                    target = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.TRIGGERED);
                    spawnTarget(0, 10);
                    healthBefore = target.getHealth();
                }
                case 8 -> {
                    move(player, -1, -2);
                    var clock = level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
                    level.getServer().clockManager().setTotalTicks(clock, 18000);
                    sleepClock = level.getOverworldClockTime();
                }
                case 9 -> {
                    player.closeContainer();
                    move(player, 0, 6);
                    player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
                    player.setExperienceLevels(5);
                    player.setExperiencePoints(0);
                }
                case 10 -> {
                    // The new native player is first checked at the bed before being moved to the dropped items.
                }
                default -> throw new IllegalStateException("Unknown survival transition " + stage);
            }
            publish(player);
        }

        private void spawnTarget(double x, double z) {
            require(target != null, "native target creation");
            target.setNoAi(true);
            target.setPersistenceRequired();
            target.setPos(ORIGIN + x + 0.5, y, ORIGIN + z + 0.5);
            require(level.addFreshEntity(target), "native target registration");
        }

        private void move(ServerPlayer player, double x, double z) {
            player.teleportTo(ORIGIN + x + 0.5, y, ORIGIN + z + 0.5);
        }

        private void give(ServerPlayer player, int slot, Item item, int amount) {
            player.getInventory().setItem(slot, new ItemStack(item, amount));
        }

        private boolean ack() {
            return Files.exists(directory.resolve("client-" + stage));
        }

        private void publish(ServerPlayer player) {
            player.inventoryMenu.broadcastChanges();
            signal(directory, "server-" + stage);
            System.out.println("CC_SURVIVAL_STAGE side=server y=" + y + " stage=" + stage);
        }
    }

    /** Runs on the real client thread, including death screens; never accesses an integrated server. */
    public static final class ClientChecks {
        private final Path directory;
        private final int y;
        private int stage = -1;
        private int age;
        private int action;
        private boolean observedFoodUse;
        private boolean observedTarget;
        private boolean observedSleeping;
        private boolean observedDeathScreen;
        private boolean observedFurnaceLit;
        private boolean done;

        public ClientChecks(Path directory, int baseY) {
            this.directory = directory;
            this.y = baseY;
        }

        public void tick(Minecraft client) {
            if (done) {
                return;
            }
            client.options.pauseOnLostFocus = false;
            while (Files.exists(directory.resolve("server-" + (stage + 1)))) {
                stage++;
                age = 0;
                action = 0;
                client.options.keyUse.setDown(false);
                System.out.println("CC_SURVIVAL_STAGE side=client y=" + y + " stage=" + stage);
            }
            require(++age < STAGE_TIMEOUT, "client timeout y=" + y + " stage=" + stage + " action=" + action + " screen=" + client.gui.screen());
            if (stage == LAST_STAGE) {
                require(client.player != null && Math.abs(client.player.getY() - y) < 4, "final client height");
                done = true;
                System.out.println("CC_SURVIVAL_CLIENT_COMPLETE y=" + y + " nativeActions=true menus=true deathRespawn=true testBypass=false");
                return;
            }
            if (stage < 0 || client.player == null || client.level == null || client.gameMode == null) {
                return;
            }
            if (age % 200 == 0) {
                System.out.println("CC_SURVIVAL_PROGRESS side=client y=" + y + " stage=" + stage + " action=" + action + " age=" + age + " position="
                        + client.player.position() + " menu=" + client.player.containerMenu.getClass().getSimpleName());
            }
            if (stage != 9 && (Math.abs(client.player.getY() - y) > 4 || client.gameMode.getPlayerMode() != GameType.SURVIVAL)) {
                return;
            }
            switch (stage) {
                case 0 -> mine(client);
                case 1 -> eat(client);
                case 2 -> equip(client);
                case 3 -> melee(client);
                case 4 -> bow(client);
                case 5 -> chest(client);
                case 6 -> craft(client);
                case 7 -> furnace(client);
                case 8 -> sleep(client);
                case 9 -> death(client);
                case 10 -> {
                    if (recoveredInventory(client.player.getInventory()) && client.player.totalExperience >= 35) {
                        ack();
                    }
                }
                default -> throw new IllegalStateException("Unknown survival client stage " + stage);
            }
        }

        public boolean isDone() {
            return done;
        }

        private void mine(Minecraft client) {
            if (Files.exists(directory.resolve("client-0"))) {
                return;
            }
            client.player.getInventory().setSelectedSlot(0);
            if (client.level.getBlockState(pos(y, 0, 3)).is(Blocks.STONE)) {
                if (action++ == 0) {
                    client.gameMode.startDestroyBlock(pos(y, 0, 3), Direction.NORTH);
                } else {
                    client.gameMode.continueDestroyBlock(pos(y, 0, 3), Direction.NORTH);
                }
            } else if (action > 0 && count(client.player.getInventory(), Items.COBBLESTONE) == 1
                    && client.player.getInventory().getItem(0).getDamageValue() == 1) {
                client.gameMode.stopDestroyBlock();
                ack();
            }
        }

        private void eat(Minecraft client) {
            if (action == 0 && count(client.player.getInventory(), Items.BREAD) == 2) {
                client.player.getInventory().setSelectedSlot(1);
                client.options.keyUse.setDown(true);
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                action++;
            }
            observedFoodUse |= client.player.isUsingItem();
            if (count(client.player.getInventory(), Items.BREAD) == 1) {
                client.options.keyUse.setDown(false);
                client.gameMode.releaseUsingItem(client.player);
                require(observedFoodUse, "client must observe native food use animation");
                if (client.player.getFoodData().getFoodLevel() == 15) {
                    ack();
                }
            }
        }

        private void equip(Minecraft client) {
            if (action == 0 && client.player.getInventory().getItem(2).is(Items.IRON_CHESTPLATE)) {
                client.player.getInventory().setSelectedSlot(2);
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                action++;
            }
            if (client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE)
                    && client.player.getItemBySlot(EquipmentSlot.CHEST).getDamageValue() > 0 && client.player.getHealth() < 20) {
                ack();
            }
        }

        private void melee(Minecraft client) {
            client.player.getInventory().setSelectedSlot(3);
            var targets = client.level.getEntitiesOfClass(Mob.class, bounds(pos(y - 1, -2, 0), pos(y + 4, 3, 5)),
                    mob -> mob.getType() == EntityTypes.COW && mob.isAlive());
            if (!targets.isEmpty()) {
                observedTarget = true;
                if (age % 25 == 0) {
                    client.gameMode.attack(client.player, targets.getFirst());
                }
            } else if (observedTarget && client.player.getInventory().getItem(3).getDamageValue() > 0) {
                ack();
            }
        }

        private void bow(Minecraft client) {
            client.player.getInventory().setSelectedSlot(4);
            client.player.setYRot(0);
            client.player.setXRot(0);
            if (action == 0 && age > 15 && !client.level
                    .getEntitiesOfClass(Mob.class, bounds(pos(y - 1, -2, 8), pos(y + 4, 3, 13)), mob -> mob.getType() == EntityTypes.IRON_GOLEM)
                    .isEmpty()) {
                client.options.keyUse.setDown(true);
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                action = age;
            } else if (action > 0 && age - action >= 25) {
                client.options.keyUse.setDown(false);
                client.gameMode.releaseUsingItem(client.player);
                action = -1;
            }
            if (action == -1 && count(client.player.getInventory(), Items.ARROW) == 2
                    && client.player.getInventory().getItem(4).getDamageValue() == 1) {
                ack();
            }
        }

        private void chest(Minecraft client) {
            if (!(client.player.containerMenu instanceof ChestMenu menu)) {
                if (action == 0 && age % 20 == 0) {
                    open(client, pos(y, 2, 0));
                }
                return;
            }
            if (action == 0 && menu.getSlot(0).getItem().is(Items.EMERALD)) {
                click(client, 0, ContainerInput.QUICK_MOVE);
                action = 1;
            } else if (action == 1 && menu.getSlot(0).getItem().isEmpty() && count(client.player.getInventory(), Items.EMERALD) == 5) {
                int source = findPlayerSlot(client, Items.EMERALD);
                if (source >= 0) {
                    click(client, source, ContainerInput.QUICK_MOVE);
                    action = 2;
                }
            } else if (action == 2 && count(client.player.getInventory(), Items.EMERALD) == 0 && menu.getSlot(0).getItem().getCount() == 5) {
                client.player.closeContainer();
                ack();
            }
        }

        private void craft(Minecraft client) {
            if (!(client.player.containerMenu instanceof CraftingMenu menu)) {
                if (action == 0 && age % 20 == 0) {
                    open(client, pos(y, 2, 1));
                }
                return;
            }
            if (action == 0) {
                int source = findPlayerSlot(client, Items.OAK_LOG);
                if (source >= 0) {
                    click(client, source, ContainerInput.PICKUP);
                    click(client, 1, ContainerInput.PICKUP);
                    action = 1;
                }
            } else if (action == 1 && menu.getSlot(0).getItem().is(Items.OAK_PLANKS) && menu.getSlot(0).getItem().getCount() == 4) {
                click(client, 0, ContainerInput.QUICK_MOVE);
                action = 2;
            } else if (action == 2 && count(client.player.getInventory(), Items.OAK_PLANKS) == 4 && menu.getSlot(1).getItem().isEmpty()) {
                client.player.closeContainer();
                ack();
            }
        }

        private void furnace(Minecraft client) {
            if (!(client.player.containerMenu instanceof FurnaceMenu menu)) {
                if (action == 0 && age % 20 == 0) {
                    open(client, pos(y, 2, 2));
                }
                return;
            }
            observedFurnaceLit |= menu.isLit();
            if (action == 0) {
                int source = findPlayerSlot(client, Items.RAW_IRON);
                if (source >= 0) {
                    click(client, source, ContainerInput.PICKUP);
                    click(client, 0, ContainerInput.PICKUP);
                    action = 1;
                }
            } else if (action == 1 && menu.getSlot(0).getItem().is(Items.RAW_IRON)) {
                int source = findPlayerSlot(client, Items.COAL);
                if (source >= 0) {
                    click(client, source, ContainerInput.PICKUP);
                    click(client, 1, ContainerInput.PICKUP);
                    action = 2;
                }
            } else if (action == 2 && menu.getSlot(2).getItem().is(Items.IRON_INGOT)) {
                require(observedFurnaceLit, "client must observe native furnace burning");
                click(client, 2, ContainerInput.QUICK_MOVE);
                action = 3;
            } else if (action == 3 && menu.getSlot(2).getItem().isEmpty() && count(client.player.getInventory(), Items.IRON_INGOT) == 1) {
                client.player.closeContainer();
                ack();
            }
        }

        private void sleep(Minecraft client) {
            observedSleeping |= client.player.isSleeping();
            if (!observedSleeping && age % 30 == 0) {
                client.player.getInventory().setSelectedSlot(8);
                open(client, pos(y, -2, -2));
            }
            if (observedSleeping && !client.player.isSleeping()) {
                ack();
            }
        }

        private void death(Minecraft client) {
            if (!Files.exists(directory.resolve("death-client-ready")) && count(client.player.getInventory(), Items.DIAMOND) == 3) {
                signal(directory, "death-client-ready");
            }
            if (client.player.isDeadOrDying()) {
                observedDeathScreen |= client.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen;
                if (observedDeathScreen && action == 0 && Files.exists(directory.resolve("death-observed"))) {
                    client.player.respawn();
                    action = 1;
                }
            } else if (observedDeathScreen && action == 1 && Math.abs(client.player.getY() - y) < 3
                    && client.player.blockPosition().distSqr(pos(y, -2, -3)) < 36 && count(client.player.getInventory(), Items.DIAMOND) == 0
                    && client.player.totalExperience == 0 && client.player.experienceLevel == 0 && client.player.getHealth() == 20) {
                require(client.player.getLastDeathLocation().equals(java.util.Optional.of(GlobalPos.of(client.level.dimension(), pos(y, 0, 6)))),
                        "native respawn packet must preserve the full death position used by the recovery compass");
                ack();
            }
        }

        private void open(Minecraft client, BlockPos block) {
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(block), Direction.UP, block, false));
        }

        private void click(Minecraft client, int slot, ContainerInput input) {
            client.gameMode.handleContainerInput(client.player.containerMenu.containerId, slot, 0, input, client.player);
        }

        private int findPlayerSlot(Minecraft client, Item item) {
            AbstractContainerMenu menu = client.player.containerMenu;
            for (int slot = 0; slot < menu.slots.size(); slot++) {
                if (menu.getSlot(slot).container == client.player.getInventory() && menu.getSlot(slot).getItem().is(item)) {
                    return slot;
                }
            }
            return -1;
        }

        private void ack() {
            if (!Files.exists(directory.resolve("client-" + stage))) {
                signal(directory, "client-" + stage);
            }
        }
    }
}
