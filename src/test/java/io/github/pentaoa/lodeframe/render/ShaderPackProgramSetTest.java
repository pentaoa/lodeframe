package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.shaders.pack.ShaderPack;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackReport;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackScanner;
import com.mojang.blaze3d.GpuFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ShaderPackProgramSetTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsTheOrderedFullscreenSequenceAndMetalBufferFormats() throws Exception {
        Path root = this.temporaryDirectory.resolve("Pack");
        writePair(root, "world0/composite", """
                const int colortex0Format = R11F_G11F_B10F;
                const int colortex1Format = RGB8;
                /* DRAWBUFFERS:01 */
                gl_FragData[0] = vec4(1.0);
                gl_FragData[1] = vec4(1.0);
                """);
        writePair(root, "world0/final", "gl_FragColor = vec4(1.0);\n");

        ShaderPackReport report;
        try (ShaderPack pack = ShaderPack.open(root)) {
            report = new ShaderPackScanner().scan(pack);
        }
        ShaderPackProgramSet set = ShaderPackProgramSet.load(root, report, "world0", 2L);

        assertEquals(2, set.fullscreenPrograms().size());
        assertEquals("composite", set.fullscreenPrograms().getFirst().program().name());
        assertEquals("final", set.finalProgram().program().name());
        assertEquals(GpuFormat.RG11B10_FLOAT, set.bufferFormats().get(0));
        assertEquals(GpuFormat.RGBA8_UNORM, set.bufferFormats().get(1));
    }

    private static void writePair(final Path root, final String program, final String fragmentBody) throws Exception {
        Path vertex = root.resolve("shaders/" + program + ".vsh");
        Path fragment = root.resolve("shaders/" + program + ".fsh");
        Files.createDirectories(vertex.getParent());
        Files.writeString(vertex, """
                #version 120
                void main() { gl_Position = ftransform(); }
                """);
        Files.writeString(fragment, "#version 120\nvoid main() {\n" + fragmentBody + "}\n");
    }
}
