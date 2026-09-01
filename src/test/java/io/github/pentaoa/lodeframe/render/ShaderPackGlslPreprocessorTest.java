package io.github.pentaoa.lodeframe.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShaderPackGlslPreprocessorTest {
    @Test
    void resolvesTheActiveConditionalDrawBuffersDirective() {
        ShaderPackGlslPreprocessor.FragmentSource result = ShaderPackGlslPreprocessor.preprocessFragment("""
                #version 120
                #define HIGH_QUALITY
                #ifdef HIGH_QUALITY
                /* DRAWBUFFERS:0195 */
                #else
                /* DRAWBUFFERS:01 */
                #endif
                void main() { gl_FragColor = vec4(1.0); }
                """);

        assertEquals(List.of(0, 1, 9, 5), result.renderTargets().orElseThrow().buffers());
        assertFalse(result.source().contains("lodeframeRenderTargetsMarker"));
        assertTrue(result.source().contains("gl_FragColor"));
    }

    @Test
    void exposesMinecraftIrisAndAppleMacrosDuringPreprocessing() {
        ShaderPackGlslPreprocessor.FragmentSource result = ShaderPackGlslPreprocessor.preprocessFragment("""
                #version 120
                #if MC_VERSION >= 12109 && defined(IS_IRIS) && defined(MC_OS_MAC) && defined(MC_GL_RENDERER_APPLE)
                /* RENDERTARGETS: 3, 7 */
                #endif
                void main() { gl_FragColor = vec4(1.0); }
                """);

        assertEquals(List.of(3, 7), result.renderTargets().orElseThrow().buffers());
    }
}
