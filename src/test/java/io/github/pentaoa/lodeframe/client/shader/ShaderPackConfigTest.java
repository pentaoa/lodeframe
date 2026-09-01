package io.github.pentaoa.lodeframe.client.shader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShaderPackConfigTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndLoadsSelection() throws Exception {
        Path path = this.temporaryDirectory.resolve("config/lodeframe-shaders.properties");
        ShaderPackConfig config = ShaderPackConfig.load(path);

        assertFalse(config.enabled());
        assertEquals("", config.selectedPack());

        config.update(true, "BSL_v10.1.3.zip");
        config.save();

        ShaderPackConfig reloaded = ShaderPackConfig.load(path);
        assertTrue(reloaded.enabled());
        assertEquals("BSL_v10.1.3.zip", reloaded.selectedPack());
    }

    @Test
    void discoversZipAndUnpackedShaderPacksInDisplayOrder() throws Exception {
        Path directory = this.temporaryDirectory.resolve("shaderpacks");
        Files.createDirectories(directory.resolve("Complementary/shaders"));
        Files.createFile(directory.resolve("BSL_v10.1.3.zip"));
        Files.createFile(directory.resolve("notes.txt"));
        Files.createDirectories(directory.resolve("not-a-pack"));

        List<ShaderPackSelection> packs = ShaderPackDiscovery.discover(directory);

        assertEquals(List.of("BSL_v10.1.3.zip", "Complementary"), packs.stream()
                .map(ShaderPackSelection::fileName)
                .toList());
        assertEquals(List.of("BSL_v10.1.3", "Complementary"), packs.stream()
                .map(ShaderPackSelection::displayName)
                .toList());
    }
}
