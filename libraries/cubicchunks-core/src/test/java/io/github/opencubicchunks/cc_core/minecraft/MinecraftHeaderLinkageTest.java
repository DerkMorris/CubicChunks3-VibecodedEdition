package io.github.opencubicchunks.cc_core.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.opencubicchunks.cc_core.api.CubePos;
import org.junit.jupiter.api.Test;

/** These tests must run after JavaHeaders links the test JAR to the real game. */
public class MinecraftHeaderLinkageTest {
    @Test
    public void cubeCoordinatesInheritTheVanillaVectorImplementation() {
        assertEquals("net.minecraft.core.Vec3i", CubePos.class.getSuperclass().getName());
        CubePos position = CubePos.of(-17, 32, 4096);
        assertEquals(-17, position.getX());
        assertEquals(32, position.getY());
        assertEquals(4096, position.getZ());
    }

    @Test
    public void chunkCoordinatesLinkToRecordAccessorsAndPackingMethods() {
        assertTrue(MCChunkPos.class.isRecord());
        MCChunkPos position = new MCChunkPos(-1875000, 1875000);
        assertEquals(-1875000, position.x());
        assertEquals(1875000, position.z());
        assertEquals(position, MCChunkPos.unpack(position.pack()));
        assertEquals(position.pack(), MCChunkPos.pack(position.x(), position.z()));
    }

    @Test
    public void heightBoundsLinkToInclusiveVanillaCoordinates() throws ReflectiveOperationException {
        // The header intentionally contains only the methods Core consumes.
        // Reflection lets this test use the real game's factory after linking.
        MCLevelHeightAccessor bounds = (MCLevelHeightAccessor) MCLevelHeightAccessor.class
            .getMethod("create", int.class, int.class).invoke(null, -64, 384);
        assertEquals(-64, bounds.getMinY());
        assertEquals(319, bounds.getMaxY());
    }

    @Test
    public void heightStorageLinksToVanillaBitStorage() {
        assertEquals("net.minecraft.util.SimpleBitStorage", MCBitStorage.class.getName());
        MCBitStorage storage = new MCBitStorage(6, 256);
        storage.set(0, 63);
        storage.set(255, 32);
        assertEquals(63, storage.get(0));
        assertEquals(32, storage.get(255));
        assertEquals(0, storage.get(254));
    }
}
