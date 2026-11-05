package io.github.opencubicchunks.cubicchunks.testutils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import com.mojang.datafixers.DataFixer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

public final class PoiTestSupport {
    private PoiTestSupport() {}

    public static PoiManager attach(ServerLevel level, Path directory) {
        PoiManager manager = new PoiManager(new RegionStorageInfo("test", level.dimension(), "poi"), directory.resolve("poi"), mock(DataFixer.class),
                false, level.registryAccess(), mock(ChunkIOErrorReporter.class), level);
        when(level.getPoiManager()).thenReturn(manager);
        return manager;
    }
}
