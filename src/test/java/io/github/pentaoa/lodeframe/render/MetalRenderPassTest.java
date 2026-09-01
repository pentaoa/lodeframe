package io.github.pentaoa.lodeframe.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MetalRenderPassTest {
    @Test
    void triangleFanWithFewerThanThreeVerticesIsEmpty() {
        assertEquals(0, MetalRenderPass.triangleFanIndexCount(0));
        assertEquals(0, MetalRenderPass.triangleFanIndexCount(1));
        assertEquals(0, MetalRenderPass.triangleFanIndexCount(2));
    }

    @Test
    void triangleFanExpandsToIndependentTriangles() {
        assertEquals(3, MetalRenderPass.triangleFanIndexCount(3));
        assertEquals(9, MetalRenderPass.triangleFanIndexCount(5));
    }
}
