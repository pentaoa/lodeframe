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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        writeTerrainPair(root);
        writeChunkPair(root, "gbuffers_water");
        writePositionPair(root, "gbuffers_skybasic");
        writeEntityPair(root);
        writeEntityPair(root, "gbuffers_entities_glowing");
        writeEntityPair(root, "gbuffers_hand");
        writeEntityPair(root, "gbuffers_hand_water");
        writeParticlePair(root, "gbuffers_textured");
        writeParticlePair(root, "gbuffers_weather");
        writeShadowPair(root);

        ShaderPackReport report;
        try (ShaderPack pack = ShaderPack.open(root)) {
            report = new ShaderPackScanner().scan(pack);
        }
        ShaderPackProgramSet set = ShaderPackProgramSet.load(root, report, "world0", 2L);

        assertEquals(2, set.fullscreenPrograms().size());
        assertEquals("composite", set.fullscreenPrograms().getFirst().program().name());
        assertEquals("final", set.finalProgram().program().name());
        assertNotNull(set.terrainProgram());
        assertEquals("gbuffers_terrain", set.terrainProgram().program().name());
        assertNotNull(set.waterProgram());
        assertEquals("gbuffers_water", set.waterProgram().program().name());
        assertNotNull(set.skyBasicProgram());
        assertEquals("gbuffers_skybasic", set.skyBasicProgram().program().name());
        assertNotNull(set.entitiesProgram());
        assertEquals("gbuffers_entities", set.entitiesProgram().program().name());
        assertNotNull(set.entitiesGlowingProgram());
        assertEquals("gbuffers_entities_glowing", set.entitiesGlowingProgram().program().name());
        assertNotNull(set.handProgram());
        assertEquals("gbuffers_hand", set.handProgram().program().name());
        assertNotNull(set.handWaterProgram());
        assertEquals("gbuffers_hand_water", set.handWaterProgram().program().name());
        assertNotNull(set.texturedProgram());
        assertEquals("gbuffers_textured", set.texturedProgram().program().name());
        assertNotNull(set.weatherProgram());
        assertEquals("gbuffers_weather", set.weatherProgram().program().name());
        assertNotNull(set.shadowProgram());
        assertEquals("shadow", set.shadowProgram().program().name());
        assertEquals(2048, set.shadowMapResolution());
        assertEquals(256.0F, set.shadowDistance());
        assertEquals(-40.0F, set.sunPathRotation(), set.shadowProgram().vertex().source());
        assertEquals(GpuFormat.RG11B10_FLOAT, set.bufferFormats().get(0));
        assertEquals(GpuFormat.RGBA8_UNORM, set.bufferFormats().get(1));
    }

    @Test
    void acceptsCompositeOnlyPacksWithoutAFinalProgram() throws Exception {
        Path root = this.temporaryDirectory.resolve("CompositeOnly");
        writePair(root, "world0/composite", "gl_FragColor = vec4(1.0);\n");
        ShaderPackReport report;
        try (ShaderPack pack = ShaderPack.open(root)) {
            report = new ShaderPackScanner().scan(pack);
        }

        ShaderPackProgramSet set = ShaderPackProgramSet.load(root, report, "world0", 3L);

        assertEquals(1, set.fullscreenPrograms().size());
        assertNull(set.finalProgram());
    }

    private static void writeTerrainPair(final Path root) throws Exception {
        writeChunkPair(root, "gbuffers_terrain");
    }

    private static void writeChunkPair(final Path root, final String name) throws Exception {
        Path vertex = root.resolve("shaders/world0/" + name + ".vsh");
        Path fragment = root.resolve("shaders/world0/" + name + ".fsh");
        Files.createDirectories(vertex.getParent());
        Files.writeString(vertex, """
                #version 120
                attribute vec4 mc_Entity;
                varying vec2 texCoord;
                void main() {
                    texCoord = gl_MultiTexCoord0.xy;
                    gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
                }
                """);
        Files.writeString(fragment, """
                #version 120
                varying vec2 texCoord;
                uniform sampler2D texture;
                void main() { gl_FragColor = texture2D(texture, texCoord); }
                """);
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

    private static void writePositionPair(final Path root, final String name) throws Exception {
        Path vertex = root.resolve("shaders/world0/" + name + ".vsh");
        Path fragment = root.resolve("shaders/world0/" + name + ".fsh");
        Files.createDirectories(vertex.getParent());
        Files.writeString(vertex, "#version 120\nvoid main() { gl_Position = ftransform(); }\n");
        Files.writeString(fragment, "#version 120\nvoid main() { gl_FragColor = vec4(0.25); }\n");
    }

    private static void writeEntityPair(final Path root) throws Exception {
        writeEntityPair(root, "gbuffers_entities");
    }

    private static void writeEntityPair(final Path root, final String name) throws Exception {
        Path vertex = root.resolve("shaders/world0/" + name + ".vsh");
        Path fragment = root.resolve("shaders/world0/" + name + ".fsh");
        Files.createDirectories(vertex.getParent());
        Files.writeString(vertex, """
                #version 120
                varying vec2 texCoord;
                void main() {
                    texCoord = gl_MultiTexCoord0.xy;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(fragment, """
                #version 120
                varying vec2 texCoord;
                uniform sampler2D texture;
                void main() { gl_FragColor = texture2D(texture, texCoord); }
                """);
    }

    private static void writeShadowPair(final Path root) throws Exception {
        Path vertex = root.resolve("shaders/world0/shadow.vsh");
        Path fragment = root.resolve("shaders/world0/shadow.fsh");
        Files.createDirectories(vertex.getParent());
        Files.writeString(vertex, """
                #version 120
                const int shadowMapResolution = 2048;
                const float shadowDistance = 256.0;
                const float sunPathRotation = -40.0;
                void main() { gl_Position = ftransform(); }
                """);
        Files.writeString(fragment, "#version 120\nvoid main() { gl_FragColor = vec4(1.0); }\n");
    }

    private static void writeParticlePair(final Path root, final String name) throws Exception {
        Path vertex = root.resolve("shaders/world0/" + name + ".vsh");
        Path fragment = root.resolve("shaders/world0/" + name + ".fsh");
        Files.createDirectories(vertex.getParent());
        Files.writeString(vertex, """
                #version 120
                varying vec2 texCoord;
                void main() {
                    texCoord = gl_MultiTexCoord0.xy;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(fragment, """
                #version 120
                varying vec2 texCoord;
                uniform sampler2D texture;
                void main() { gl_FragColor = texture2D(texture, texCoord); }
                """);
    }
}
