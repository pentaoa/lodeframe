package io.github.pentaoa.lodeframe.render.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShaderPackTerrainVertexTypeTest {
    @Test
    void exposesTheExtendedTerrainAttributesAtAStableStride() {
        assertEquals(ShaderPackTerrainVertexType.STRIDE, ShaderPackTerrainVertexType.INSTANCE.getVertexFormat().getVertexSize());
        assertTrue(ShaderPackTerrainVertexType.INSTANCE.getVertexFormat().contains("a_Normal"));
        assertTrue(ShaderPackTerrainVertexType.INSTANCE.getVertexFormat().contains("a_MidTexCoord"));
        assertTrue(ShaderPackTerrainVertexType.INSTANCE.getVertexFormat().contains("a_Tangent"));
        assertTrue(ShaderPackTerrainVertexType.INSTANCE.getVertexFormat().contains("a_BlockId"));
    }

    @Test
    void encodesQuadNormalAndMidpointTextureCoordinates() {
        ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
        set(vertices[0], 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        set(vertices[1], 0.0F, 0.0F, 1.0F, 0.0F, 1.0F);
        set(vertices[2], 1.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        set(vertices[3], 1.0F, 0.0F, 0.0F, 1.0F, 0.0F);

        long memory = MemoryUtil.nmemAlloc(ShaderPackTerrainVertexType.STRIDE * 4L);
        try {
            long end = ShaderPackTerrainVertexType.INSTANCE.getEncoder().write(memory, 0, vertices, 0);
            assertEquals(memory + ShaderPackTerrainVertexType.STRIDE * 4L, end);
            assertEquals(0x7F007F00, MemoryUtil.memGetInt(memory + ShaderPackTerrainVertexType.NORMAL_OFFSET));
            assertEquals(0x80008000, MemoryUtil.memGetInt(memory + ShaderPackTerrainVertexType.MID_TEX_COORD_OFFSET));
            assertEquals(0x8100007F, MemoryUtil.memGetInt(memory + ShaderPackTerrainVertexType.TANGENT_OFFSET));
        } finally {
            MemoryUtil.nmemFree(memory);
        }
    }

    private static void set(
            final ChunkVertexEncoder.Vertex vertex,
            final float x,
            final float y,
            final float z,
            final float u,
            final float v
    ) {
        ChunkVertexEncoder.Vertex.writeVertex(vertex, x, y, z, -1, 1.0F, u, v, 0x00F000F0);
    }
}
