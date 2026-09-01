package io.github.pentaoa.lodeframe.render.sodium;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.minecraft.util.Mth;

public final class ShaderPackTerrainVertexType implements ChunkVertexType {
    public static final ShaderPackTerrainVertexType INSTANCE = new ShaderPackTerrainVertexType();
    public static final int STRIDE = 36;
    public static final int NORMAL_OFFSET = 20;
    public static final int MID_TEX_COORD_OFFSET = 24;
    public static final int TANGENT_OFFSET = 28;
    public static final int BLOCK_ID_OFFSET = 32;

    private static final int POSITION_MAX_COORD = 1 << 20;
    private static final int POSITION_MAX_VALUE = POSITION_MAX_COORD - 1;
    private static final int TEXTURE_MAX_COORD = 1 << 15;
    private static final int TEXTURE_MAX_VALUE = TEXTURE_MAX_COORD - 1;
    private static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
            .addAttribute("a_Position", GpuFormat.RG32_UINT)
            .addAttribute("a_Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("a_TexCoord", GpuFormat.RG16_UINT)
            .addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT)
            .addAttribute("a_Normal", GpuFormat.RGBA8_SNORM)
            .addAttribute("a_MidTexCoord", GpuFormat.RG16_UNORM)
            .addAttribute("a_Tangent", GpuFormat.RGBA8_SNORM)
            .addAttribute("a_BlockId", GpuFormat.R32_SINT)
            .build();

    private ShaderPackTerrainVertexType() {
    }

    @Override
    public VertexFormat getVertexFormat() {
        return VERTEX_FORMAT;
    }

    @Override
    public ChunkVertexEncoder getEncoder() {
        return ShaderPackTerrainVertexType::writeQuad;
    }

    private static long writeQuad(
            long pointer,
            final int material,
            final ChunkVertexEncoder.Vertex[] vertices,
            final int drawId
    ) {
        float midU = 0.0F;
        float midV = 0.0F;
        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            midU += vertex.u;
            midV += vertex.v;
        }
        midU *= 0.25F;
        midV *= 0.25F;

        int packedNormal = packNormal(vertices);
        int packedTangent = packTangent(vertices, packedNormal);
        int packedMidTexCoord = packMidTexCoord(midU, midV);
        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            int positionX = quantizePosition(vertex.x);
            int positionY = quantizePosition(vertex.y);
            int positionZ = quantizePosition(vertex.z);
            int textureU = encodeTexture(midU, vertex.u);
            int textureV = encodeTexture(midV, vertex.v);
            int light = encodeLight(vertex.light);

            MemoryIntrinsics.putInt(pointer, packPositionHi(positionX, positionY, positionZ));
            MemoryIntrinsics.putInt(pointer + 4L, packPositionLo(positionX, positionY, positionZ));
            MemoryIntrinsics.putInt(pointer + 8L, ColorARGB.mulRGB(vertex.color, vertex.ao));
            MemoryIntrinsics.putInt(pointer + 12L, packTexture(textureU, textureV));
            MemoryIntrinsics.putInt(pointer + 16L, packLightAndData(light, material, drawId));
            MemoryIntrinsics.putInt(pointer + NORMAL_OFFSET, packedNormal);
            MemoryIntrinsics.putInt(pointer + MID_TEX_COORD_OFFSET, packedMidTexCoord);
            MemoryIntrinsics.putInt(pointer + TANGENT_OFFSET, packedTangent);
            MemoryIntrinsics.putInt(
                    pointer + BLOCK_ID_OFFSET,
                    vertex instanceof ShaderPackChunkVertex shaderVertex
                            ? shaderVertex.lodeframe$getBlockId()
                            : 0
            );
            pointer += STRIDE;
        }
        return pointer;
    }

    private static int packNormal(final ChunkVertexEncoder.Vertex[] vertices) {
        ChunkVertexEncoder.Vertex first = vertices[0];
        ChunkVertexEncoder.Vertex second = vertices[1];
        ChunkVertexEncoder.Vertex third = vertices[2];
        ChunkVertexEncoder.Vertex fourth = vertices[3];
        float firstEdgeX = third.x - first.x;
        float firstEdgeY = third.y - first.y;
        float firstEdgeZ = third.z - first.z;
        float secondEdgeX = fourth.x - second.x;
        float secondEdgeY = fourth.y - second.y;
        float secondEdgeZ = fourth.z - second.z;
        float normalX = firstEdgeY * secondEdgeZ - firstEdgeZ * secondEdgeY;
        float normalY = firstEdgeZ * secondEdgeX - firstEdgeX * secondEdgeZ;
        float normalZ = firstEdgeX * secondEdgeY - firstEdgeY * secondEdgeX;
        float inverseLength = Mth.invSqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
        return packSnorm8(normalX * inverseLength)
                | packSnorm8(normalY * inverseLength) << 8
                | packSnorm8(normalZ * inverseLength) << 16
                | 0x7F000000;
    }

    private static int packSnorm8(final float value) {
        return Math.round(Math.clamp(value, -1.0F, 1.0F) * 127.0F) & 0xFF;
    }

    private static int packTangent(final ChunkVertexEncoder.Vertex[] vertices, final int packedNormal) {
        ChunkVertexEncoder.Vertex first = vertices[0];
        ChunkVertexEncoder.Vertex second = vertices[1];
        ChunkVertexEncoder.Vertex third = vertices[2];
        float edge1X = second.x - first.x;
        float edge1Y = second.y - first.y;
        float edge1Z = second.z - first.z;
        float edge2X = third.x - first.x;
        float edge2Y = third.y - first.y;
        float edge2Z = third.z - first.z;
        float du1 = second.u - first.u;
        float dv1 = second.v - first.v;
        float du2 = third.u - first.u;
        float dv2 = third.v - first.v;
        float determinant = du1 * dv2 - du2 * dv1;

        float tangentX;
        float tangentY;
        float tangentZ;
        float bitangentX;
        float bitangentY;
        float bitangentZ;
        if (Math.abs(determinant) > 1.0E-8F) {
            float inverse = 1.0F / determinant;
            tangentX = (edge1X * dv2 - edge2X * dv1) * inverse;
            tangentY = (edge1Y * dv2 - edge2Y * dv1) * inverse;
            tangentZ = (edge1Z * dv2 - edge2Z * dv1) * inverse;
            bitangentX = (edge2X * du1 - edge1X * du2) * inverse;
            bitangentY = (edge2Y * du1 - edge1Y * du2) * inverse;
            bitangentZ = (edge2Z * du1 - edge1Z * du2) * inverse;
        } else {
            float normalX = unpackSnorm8(packedNormal);
            float normalY = unpackSnorm8(packedNormal >>> 8);
            float normalZ = unpackSnorm8(packedNormal >>> 16);
            float axisX = Math.abs(normalY) < 0.999F ? 0.0F : 1.0F;
            float axisY = Math.abs(normalY) < 0.999F ? 1.0F : 0.0F;
            tangentX = axisY * normalZ;
            tangentY = -axisX * normalZ;
            tangentZ = axisX * normalY - axisY * normalX;
            bitangentX = normalY * tangentZ - normalZ * tangentY;
            bitangentY = normalZ * tangentX - normalX * tangentZ;
            bitangentZ = normalX * tangentY - normalY * tangentX;
        }
        float tangentInverseLength = Mth.invSqrt(tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ);
        tangentX *= tangentInverseLength;
        tangentY *= tangentInverseLength;
        tangentZ *= tangentInverseLength;

        float normalX = unpackSnorm8(packedNormal);
        float normalY = unpackSnorm8(packedNormal >>> 8);
        float normalZ = unpackSnorm8(packedNormal >>> 16);
        float crossX = normalY * tangentZ - normalZ * tangentY;
        float crossY = normalZ * tangentX - normalX * tangentZ;
        float crossZ = normalX * tangentY - normalY * tangentX;
        float handedness = crossX * bitangentX + crossY * bitangentY + crossZ * bitangentZ < 0.0F
                ? -1.0F
                : 1.0F;
        return packSnorm8(tangentX)
                | packSnorm8(tangentY) << 8
                | packSnorm8(tangentZ) << 16
                | packSnorm8(handedness) << 24;
    }

    private static float unpackSnorm8(final int packed) {
        return (byte) packed / 127.0F;
    }

    private static int packMidTexCoord(final float u, final float v) {
        int encodedU = Math.round(Math.clamp(u, 0.0F, 1.0F) * 65535.0F);
        int encodedV = Math.round(Math.clamp(v, 0.0F, 1.0F) * 65535.0F);
        return encodedU | encodedV << 16;
    }

    private static int quantizePosition(final float position) {
        return (int) (((8.0F + position) / 32.0F) * POSITION_MAX_COORD) & POSITION_MAX_VALUE;
    }

    private static int packPositionHi(final int x, final int y, final int z) {
        return (x >>> 10 & 0x3FF) | (y >>> 10 & 0x3FF) << 10 | (z >>> 10 & 0x3FF) << 20;
    }

    private static int packPositionLo(final int x, final int y, final int z) {
        return (x & 0x3FF) | (y & 0x3FF) << 10 | (z & 0x3FF) << 20;
    }

    private static int encodeTexture(final float midpoint, final float coordinate) {
        int direction = coordinate < midpoint ? 1 : -1;
        int encoded = Math.round(coordinate * TEXTURE_MAX_COORD) + direction;
        return encoded & TEXTURE_MAX_VALUE | (direction >>> 31) << 15;
    }

    private static int packTexture(final int u, final int v) {
        return u & 0xFFFF | (v & 0xFFFF) << 16;
    }

    private static int encodeLight(final int light) {
        int block = Mth.clamp((light & 0xFF) + 8, 8, 248);
        int sky = Mth.clamp((light >>> 16 & 0xFF) + 8, 8, 248);
        return block | sky << 8;
    }

    private static int packLightAndData(final int light, final int material, final int drawId) {
        return light & 0xFFFF | (material & 0xFF) << 16 | (drawId & 0xFF) << 24;
    }
}
