package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.shaders.translate.LegacyFullscreenTransformer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ShaderPackUniformLayoutTest {
    @Test
    void packsScalarVectorAndMatrixMembersUsingStd140() {
        ShaderPackUniformLayout layout = ShaderPackUniformLayout.of(List.of(
                new LegacyFullscreenTransformer.UniformField("float", "viewWidth"),
                new LegacyFullscreenTransformer.UniformField("vec3", "sunVec"),
                new LegacyFullscreenTransformer.UniformField("float", "rainStrength"),
                new LegacyFullscreenTransformer.UniformField("mat4", "projection")
        ));
        ByteBuffer data = ByteBuffer.allocateDirect(layout.size()).order(ByteOrder.nativeOrder());
        layout.write(data, new Values());

        assertEquals(96, layout.size());
        assertEquals(1920.0F, data.getFloat(0));
        assertEquals(1.0F, data.getFloat(16));
        assertEquals(3.0F, data.getFloat(24));
        assertEquals(0.5F, data.getFloat(28));
        assertEquals(1.0F, data.getFloat(32));
        assertEquals(1.0F, data.getFloat(32 + 5 * Float.BYTES));
        assertEquals(1.0F, data.getFloat(32 + 15 * Float.BYTES));
    }

    private static final class Values implements ShaderPackUniformLayout.FrameValues {
        @Override
        public int integer(final String name) {
            return 0;
        }

        @Override
        public int[] integerVector(final String name, final int components) {
            return new int[components];
        }

        @Override
        public float[] floatVector(final String name, final int components) {
            return switch (name) {
                case "viewWidth" -> new float[]{1920.0F};
                case "sunVec" -> new float[]{1.0F, 2.0F, 3.0F};
                case "rainStrength" -> new float[]{0.5F};
                default -> new float[components];
            };
        }

        @Override
        public float[] matrix(final String name, final int columns) {
            float[] result = new float[columns * columns];
            for (int index = 0; index < columns; index++) {
                result[index * columns + index] = 1.0F;
            }
            return result;
        }
    }
}
