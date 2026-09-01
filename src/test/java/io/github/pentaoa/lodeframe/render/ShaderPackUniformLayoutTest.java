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
        public int integerComponent(final String name, final int component) {
            return 0;
        }

        @Override
        public float floatComponent(final String name, final int component) {
            return switch (name) {
                case "viewWidth" -> component == 0 ? 1920.0F : 0.0F;
                case "sunVec" -> component + 1.0F;
                case "rainStrength" -> component == 0 ? 0.5F : 0.0F;
                default -> 0.0F;
            };
        }

        @Override
        public float matrixComponent(final String name, final int columns, final int column, final int row) {
            return column == row ? 1.0F : 0.0F;
        }
    }
}
