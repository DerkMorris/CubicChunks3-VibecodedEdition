package io.github.opencubicchunks.cubicchunks.test.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import io.github.opencubicchunks.cubicchunks.MarkableAsCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

class TestVerticalRenderWindow extends BaseTest {
    private static final int RADIUS = 2;

    @Test
    void crossingOneVerticalBoundaryOnlyRecyclesOneLayer() {
        for (int centerY : new int[] { -60000, -1, 0, 60000 }) {
            var storage = new RotatingSectionStorage<>(RADIUS, -RADIUS, RADIUS, Slot::new);
            ((MarkableAsCubic) storage).cc_setCubic();
            var center = SectionPos.of(-4, centerY, 17);
            storage.repositionCenter(center);
            checkMove(storage, center.offset(0, 1, 0), 25);
            checkMove(storage, center, 25);
            var diagonal = center.offset(1, -1, 1);
            checkMove(storage, diagonal, 125 - 64);
            checkMove(storage, diagonal.offset(0, 11, 0), 125);
        }
    }

    private static void checkMove(RotatingSectionStorage<Slot> storage, SectionPos next, int expectedResets) {
        Map<Long, Slot> before = new HashMap<>();
        storage.forEach(slot -> {
            before.put(slot.node, slot);
            slot.resets = 0;
        });
        assertTrue(storage.repositionCenter(next));
        int resets = 0;
        assertEquals(storage.size(), before.size());
        for (Slot slot : before.values()) {
            assertSame(slot, storage.getValue(slot.node));
            Slot shared = before.get(slot.node);
            if (shared != null) {
                assertSame(shared, slot);
                assertEquals(0, slot.resets);
            }
            resets += slot.resets;
        }
        assertEquals(expectedResets, resets);
        assertEquals(next.y() - RADIUS, storage.minY());
        assertEquals(next.y() + RADIUS, storage.maxY());
        assertNull(storage.getValue(next.x(), storage.minY() - 1, next.z()));
        assertNull(storage.getValue(next.x(), storage.maxY() + 1, next.z()));
        assertFalse(storage.repositionCenter(next));
    }

    @Test
    void vanillaWindowsStayAtTheirDimensionHeight() {
        var storage = new RotatingSectionStorage<>(RADIUS, -4, 19, Slot::new);
        storage.repositionCenter(SectionPos.of(0, 0, 0));
        var floor = storage.getValue(0, -4, 0);
        storage.repositionCenter(SectionPos.of(0, 15, 0));
        assertSame(floor, storage.getValue(0, -4, 0));
        assertEquals(-4, storage.minY());
        assertEquals(19, storage.maxY());
    }

    private static final class Slot implements RotatingSectionStorage.Value {
        private long node;
        private int resets;

        private Slot(int index, long node) {
            this.node = node;
        }

        @Override public void setSectionNode(long value) {
            node = value;
            resets++;
        }

        @Override public long getSectionNode() {
            return node;
        }
    }
}
