package io.github.pentaoa.lodeframe.client.shader;

import io.github.pentaoa.lodeframe.Lodeframe;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class LodeframeShaderPacks {
    private static final LodeframeShaderPacks INSTANCE = createDefault();

    private final Path directory;
    private final ShaderPackConfig config;

    LodeframeShaderPacks(final Path directory, final ShaderPackConfig config) {
        this.directory = directory;
        this.config = config;
    }

    public static LodeframeShaderPacks getInstance() {
        return INSTANCE;
    }

    public Path directory() {
        return this.directory;
    }

    public boolean enabled() {
        return this.config.enabled();
    }

    public List<ShaderPackSelection> discover() {
        try {
            List<ShaderPackSelection> packs = ShaderPackDiscovery.discover(this.directory);
            if (this.config.enabled() && packs.stream().noneMatch(this::isSelected)) {
                this.config.update(false, "");
                save();
            }
            return packs;
        } catch (IOException exception) {
            Lodeframe.LOGGER.error("Unable to discover shader packs in {}", this.directory, exception);
            return List.of();
        }
    }

    public ShaderPackSelection selectedFrom(final List<ShaderPackSelection> packs) {
        return packs.stream()
                .filter(this::isSelected)
                .findFirst()
                .orElseGet(() -> packs.isEmpty() ? ShaderPackSelection.NONE : packs.getFirst());
    }

    public boolean select(final ShaderPackSelection selection) {
        this.config.update(this.config.enabled() && selection.isPresent(), selection.fileName());
        return save();
    }

    public boolean setEnabled(final boolean enabled, final ShaderPackSelection selection) {
        this.config.update(enabled && selection.isPresent(), selection.fileName());
        return save();
    }

    private boolean isSelected(final ShaderPackSelection selection) {
        return selection.fileName().equals(this.config.selectedPack());
    }

    private boolean save() {
        try {
            this.config.save();
            return true;
        } catch (IOException exception) {
            Lodeframe.LOGGER.error("Unable to save shader-pack settings", exception);
            return false;
        }
    }

    private static LodeframeShaderPacks createDefault() {
        FabricLoader loader = FabricLoader.getInstance();
        Path directory = loader.getGameDir().resolve("shaderpacks");
        Path configPath = loader.getConfigDir().resolve("lodeframe-shaders.properties");
        try {
            return new LodeframeShaderPacks(directory, ShaderPackConfig.load(configPath));
        } catch (IOException exception) {
            Lodeframe.LOGGER.error("Unable to load shader-pack settings", exception);
            return new LodeframeShaderPacks(directory, ShaderPackConfig.defaults(configPath));
        }
    }
}
