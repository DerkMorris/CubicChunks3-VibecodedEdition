package io.github.opencubicchunks.cubicchunks.test.server.level;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.setupServerLevel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.opencubicchunks.cubicchunks.MarkableAsCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CloseableReference;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * This test class is for testing {@link io.github.opencubicchunks.cubicchunks.mixin.core.common.server.level.MixinServerLevel}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestCubicServerLevel extends BaseTest {
    private static final AttachmentType<String> TEST_ATTACHMENT = AttachmentRegistry
            .create(Identifier.fromNamespaceAndPath("cubicchunks_test", "server_level"));

    // TODO: Phase 3 - This needs a more rigorous test down the line when we actually care about entities
    @Test
    public void testVanillaSpawningAllowed() throws Exception {
        try (CloseableReference<ServerLevel> serverLevelReference = setupServerLevel()) {
            assertFalse(serverLevelReference.value().areEntitiesActuallyLoadedAndTicking(new ChunkPos(0, 0)));
        }
    }

    @Test
    public void testFabricAttachmentsRemainAvailableInCubicLevel() throws Exception {
        try (CloseableReference<ServerLevel> serverLevelReference = setupServerLevel()) {
            var level = serverLevelReference.value();
            var target = (AttachmentTarget) level;
            assertNull(target.setAttached(TEST_ATTACHMENT, "before-cubic"));
            ((MarkableAsCubic) level).cc_setCubic();
            assertEquals("before-cubic", target.getAttached(TEST_ATTACHMENT));
            assertEquals("before-cubic", target.setAttached(TEST_ATTACHMENT, "after-cubic"));
            assertTrue(target.hasAttached(TEST_ATTACHMENT));
            assertEquals("after-cubic", target.removeAttached(TEST_ATTACHMENT));
            assertFalse(target.hasAttached(TEST_ATTACHMENT));
        }
    }

    // TODO: Stub.
    @Test
    public void testVanillaTickChunk() throws Exception {
        try (CloseableReference<ServerLevel> serverLevelReference = setupServerLevel()) {
            serverLevelReference.value().tickChunk(new LevelChunk(serverLevelReference.value(), new ChunkPos(0, 0)), 10);
        }
    }

    // TODO: Stub. This test hangs. Maybe due to ForcedChunksSavedData?
    @Test
    @Disabled
    public void testVanillaSetChunkForced() throws Exception {
        try (CloseableReference<ServerLevel> serverLevelReference = setupServerLevel()) {
            serverLevelReference.value().setChunkForced(0, 0, true);
        }
    }

    // TODO: Stub.
    @Test
    public void testVanillaIsPositionEntityTicking() throws Exception {
        try (CloseableReference<ServerLevel> serverLevelReference = setupServerLevel()) {
            assertFalse(serverLevelReference.value().isPositionEntityTicking(BlockPos.ZERO));
        }
    }
}
