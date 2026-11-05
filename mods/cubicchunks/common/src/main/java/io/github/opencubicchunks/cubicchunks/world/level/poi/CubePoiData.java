package io.github.opencubicchunks.cubicchunks.world.level.poi;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.ai.village.poi.PoiSection;

/** Independent, strictly versioned cube POI files; native record codecs preserve full BlockPos and ticket counts. */
public final class CubePoiData {
    private static final String FORMAT = "cubicchunks:poi";
    private static final int VERSION = 1;
    private static final int AXES = 3;
    private static final int MAX_RECORDS = 4096;

    private CubePoiData() {}

    public static CompoundTag save(RegistryAccess registries, CubePos pos, List<PoiSection.Packed> sections) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Format", FORMAT);
        tag.putInt("Version", VERSION);
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        tag.putInt("CubeDiameter", CubicConstants.DIAMETER_IN_SECTIONS);
        tag.putIntArray("Pos", new int[] { pos.getX(), pos.getY(), pos.getZ() });
        ListTag list = new ListTag();
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        sections.forEach(section -> list.add(PoiSection.Packed.CODEC.encodeStart(ops, section).getOrThrow()));
        tag.put("Sections", list);
        load(registries, pos, tag);
        return tag;
    }

    public static List<PoiSection.Packed> load(RegistryAccess registries, CubePos pos, CompoundTag tag) {
        require(FORMAT.equals(tag.getString("Format").orElse(null)) && tag.getInt("Version").orElse(-1) == VERSION, "Unknown POI format");
        require(tag.getInt("DataVersion").orElse(-1) == SharedConstants.getCurrentVersion().dataVersion().version(), "Unsupported POI DataVersion");
        require(tag.getInt("CubeDiameter").orElse(-1) == CubicConstants.DIAMETER_IN_SECTIONS, "Wrong POI cube size");
        int[] coordinates = tag.getIntArray("Pos").orElseThrow();
        require(coordinates.length == AXES && CubePos.of(coordinates[0], coordinates[1], coordinates[2]).equals(pos), "Wrong POI cube position");
        Tag raw = tag.get("Sections");
        require(raw instanceof ListTag, "Missing POI sections");
        ListTag list = (ListTag) raw;
        require(list.size() == CubicConstants.SECTION_COUNT, "Wrong POI section count");
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        var sections = list.stream().map(value -> PoiSection.Packed.CODEC.parse(ops, value).getOrThrow()).toList();
        for (int index = 0; index < sections.size(); index++) {
            validate(pos, index, sections.get(index));
        }
        return sections;
    }

    private static void validate(CubePos cube, int index, PoiSection.Packed section) {
        require(section.records().size() <= MAX_RECORDS, "Too many POI records");
        Set<net.minecraft.core.BlockPos> positions = new HashSet<>();
        for (var record : section.records()) {
            require(CubePos.from(record.pos()).equals(cube) && Coords.blockToIndex(record.pos()) == index, "POI outside its section");
            require(positions.add(record.pos()), "Duplicate POI position");
            require(record.freeTickets() >= 0 && record.freeTickets() <= record.poiType().value().maxTickets(), "Invalid POI ticket count");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
