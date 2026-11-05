package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.Locale;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public enum OreKind implements StringRepresentable {
    DIAMOND(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE), IRON(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
    GOLD(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE), REDSTONE(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
    LAPIS(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE), COPPER(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
    COAL(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE), EMERALD(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);

    public static final Codec<OreKind> CODEC = StringRepresentable.fromEnum(OreKind::values);
    private final Block stone;
    private final Block slate;

    OreKind(Block stone, Block slate) {
        this.stone = stone;
        this.slate = slate;
    }

    public Block stone() {
        return stone;
    }

    public Block slate() {
        return slate;
    }

    @Override public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
