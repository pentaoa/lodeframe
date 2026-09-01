package io.github.pentaoa.lodeframe.client.shader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ShaderPackConfig {
    private static final String ENABLED_KEY = "enabled";
    private static final String SELECTED_PACK_KEY = "shaderPack";

    private final Path path;
    private boolean enabled;
    private String selectedPack;

    private ShaderPackConfig(final Path path, final boolean enabled, final String selectedPack) {
        this.path = path;
        this.enabled = enabled;
        this.selectedPack = selectedPack;
    }

    public static ShaderPackConfig load(final Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return defaults(path);
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return new ShaderPackConfig(
                path,
                Boolean.parseBoolean(properties.getProperty(ENABLED_KEY, "false")),
                properties.getProperty(SELECTED_PACK_KEY, "")
        );
    }

    static ShaderPackConfig defaults(final Path path) {
        return new ShaderPackConfig(path, false, "");
    }

    public boolean enabled() {
        return this.enabled;
    }

    public String selectedPack() {
        return this.selectedPack;
    }

    public void update(final boolean enabled, final String selectedPack) {
        this.enabled = enabled;
        this.selectedPack = selectedPack;
    }

    public void save() throws IOException {
        Files.createDirectories(this.path.getParent());
        Properties properties = new Properties();
        properties.setProperty(ENABLED_KEY, Boolean.toString(this.enabled));
        properties.setProperty(SELECTED_PACK_KEY, this.selectedPack);
        try (Writer writer = Files.newBufferedWriter(this.path, StandardCharsets.UTF_8)) {
            properties.store(writer, "Lodeframe shader-pack settings");
        }
    }
}
