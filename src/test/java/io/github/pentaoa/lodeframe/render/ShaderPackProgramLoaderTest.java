package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.shaders.pack.ShaderPack;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackReport;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackScanner;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderProgram;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShaderPackProgramLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preparesAnActiveLegacyFullscreenMrtProgramForTheMetalCompiler() throws Exception {
        Path shaders = this.temporaryDirectory.resolve("Pack/shaders/world0");
        Files.createDirectories(shaders);
        Files.writeString(shaders.resolve("composite.vsh"), """
                #version 120
                varying vec2 texCoord;
                void main() {
                    texCoord = gl_MultiTexCoord0.xy;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                #define QUALITY
                varying vec2 texCoord;
                uniform sampler2D colortex0;
                uniform float viewWidth;
                #ifdef QUALITY
                /* DRAWBUFFERS:04 */
                #else
                /* DRAWBUFFERS:0 */
                #endif
                void main() {
                    gl_FragData[0] = texture2D(colortex0, texCoord);
                    gl_FragData[1] = vec4(viewWidth);
                }
                """);

        try (ShaderPack pack = ShaderPack.open(this.temporaryDirectory.resolve("Pack"))) {
            ShaderPackReport report = new ShaderPackScanner().scan(pack);
            ShaderProgram program = report.programs().getFirst();
            ShaderPackProgramLoader.PreparedProgram prepared = ShaderPackProgramLoader.loadFullscreen(pack, program, 7L);

            assertEquals(List.of(0, 4), prepared.renderTargets().orElseThrow().buffers());
            assertTrue(prepared.fragment().source().contains("layout(location = 1) out vec4 lodeframeFragData1"));
            assertTrue(prepared.fragment().source().contains("uniform LodeframeFragmentUniforms"));
            assertEquals("viewWidth", prepared.fragment().uniforms().getFirst().name());
            assertFalse(prepared.fragment().source().contains("gl_FragData"));
            assertEquals("world0/composite.fsh", prepared.fragmentPath());
        }
    }
}
