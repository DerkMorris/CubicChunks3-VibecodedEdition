package io.github.opencubicchunks.cubicchunks.test.client;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.renderer.CubicLevelRenderer;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

class TestCubicRenderGraph extends BaseTest {
    @Test
    void arrivalsUsePartialUpdatesAndOnlyResidentRemovalsInvalidateTheGraph() {
        ViewArea area = mock();
        when(((CanBeCubic) area).cc_isCubic()).thenReturn(true);
        when(area.getCameraSectionPos()).thenReturn(SectionPos.of(0, -25, 0));
        when(area.getViewDistance()).thenReturn(3);
        var graph = spy(new SectionOcclusionGraph());
        graph.waitAndReset(area);
        clearInvocations(graph);
        var renderer = (CubicLevelRenderer) graph;
        var first = CubePos.of(0, -13, 0);
        var second = CubePos.of(1, -13, 0);
        var empty = new LongOpenHashSet();
        renderer.cc_onCubeReadyToRender(first);
        graph.updateLoadedChunks(empty, empty);
        renderer.cc_onCubeReadyToRender(first);
        renderer.cc_onCubeReadyToRender(second);
        renderer.cc_onCubeUnloaded(CubePos.of(15, 15, 15));
        graph.updateLoadedChunks(empty, empty);
        verify(graph, never()).invalidate();
        renderer.cc_onCubeUnloaded(first);
        graph.updateLoadedChunks(empty, empty);
        renderer.cc_onCubeUnloaded(first);
        graph.updateLoadedChunks(empty, empty);
        verify(graph).invalidate();
    }
}
