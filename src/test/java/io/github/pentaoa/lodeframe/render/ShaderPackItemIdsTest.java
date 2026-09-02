package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.shaders.pack.ShaderPack;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ShaderPackItemIdsTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearIds() {
        ShaderPackItemIds.clear();
    }

    @Test
    void mapsHeldItemIdentifiers() throws Exception {
        Path shaders = this.temporaryDirectory.resolve("Pack/shaders");
        Files.createDirectories(shaders);
        Files.writeString(shaders.resolve("item.properties"), """
                item.139=minecraft:torch
                item.144=minecraft:soul_torch
                """);
        try (ShaderPack pack = ShaderPack.open(shaders.getParent())) {
            ShaderPackItemIds.load(pack);
        }

        assertEquals(139, ShaderPackItemIds.id(Identifier.parse("minecraft:torch")));
        assertEquals(144, ShaderPackItemIds.id(Identifier.parse("minecraft:soul_torch")));
        assertEquals(-1, ShaderPackItemIds.id(Identifier.parse("minecraft:stone")));
    }
}
