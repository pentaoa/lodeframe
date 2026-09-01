package io.github.pentaoa.lodeframe.client.shader;

import io.github.pentaoa.lodeframe.Lodeframe;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPack;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackReport;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackScanner;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class LodeframeShaderPacks {
    private final Path directory;
    private final ShaderPackConfig config;
    private ShaderPackReport activeReport;
    private Path activeSource;
    private String activationError = "";
    private long revision;

    LodeframeShaderPacks(final Path directory, final ShaderPackConfig config) {
        this.directory = directory;
        this.config = config;
    }

    public static LodeframeShaderPacks getInstance() {
        return Holder.INSTANCE;
    }

    public Path directory() {
        return this.directory;
    }

    public boolean enabled() {
        return this.config.enabled();
    }

    public Optional<ShaderPackReport> activeReport() {
        return Optional.ofNullable(this.activeReport);
    }

    public Optional<Path> activeSource() {
        return Optional.ofNullable(this.activeSource);
    }

    public long revision() {
        return this.revision;
    }

    public String activationError() {
        return this.activationError;
    }

    public List<ShaderPackSelection> discover() {
        try {
            List<ShaderPackSelection> packs = ShaderPackDiscovery.discover(this.directory);
            if (this.config.enabled() && packs.stream().noneMatch(this::isSelected)) {
                this.config.update(false, "");
                deactivate();
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
        boolean active = !this.config.enabled() || activate(selection);
        this.config.update(this.config.enabled() && active && selection.isPresent(), selection.fileName());
        return save() && active;
    }

    public boolean setEnabled(final boolean enabled, final ShaderPackSelection selection) {
        this.activationError = "";
        boolean active = !enabled || activate(selection);
        if (!enabled) {
            deactivate();
        }
        this.config.update(enabled && active && selection.isPresent(), selection.fileName());
        return save() && active;
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

    private boolean activate(final ShaderPackSelection selection) {
        if (!selection.isPresent()) {
            deactivate();
            this.activationError = "No shader pack is selected";
            return false;
        }

        Path source = this.directory.resolve(selection.fileName());
        try (ShaderPack pack = ShaderPack.open(source)) {
            ShaderPackReport report = new ShaderPackScanner().scan(pack);
            if (report.hasErrors()) {
                deactivate();
                this.activationError = report.diagnostics().getFirst().message();
                Lodeframe.LOGGER.error("Shader pack {} failed validation: {}", source, this.activationError);
                return false;
            }
            this.activeReport = report;
            this.activeSource = source;
            this.revision++;
            this.activationError = "";
            Lodeframe.LOGGER.info(
                    "Loaded shader pack {}: {} programs, {}/{} stages resolved",
                    report.name(),
                    report.programCount(),
                    report.resolvedStageCount(),
                    report.stageEntries().size()
            );
            return true;
        } catch (IOException | IllegalArgumentException exception) {
            deactivate();
            this.activationError = exception.getMessage();
            Lodeframe.LOGGER.error("Unable to load shader pack {}", source, exception);
            return false;
        }
    }

    private void deactivate() {
        if (this.activeReport != null || this.activeSource != null) {
            this.revision++;
        }
        this.activeReport = null;
        this.activeSource = null;
    }

    private void restoreConfiguredPack() {
        if (!this.config.enabled()) {
            return;
        }
        List<ShaderPackSelection> packs = discover();
        ShaderPackSelection selected = selectedFrom(packs);
        if (!selected.isPresent() || !activate(selected)) {
            this.config.update(false, selected.fileName());
            save();
        }
    }

    private static LodeframeShaderPacks createDefault() {
        FabricLoader loader = FabricLoader.getInstance();
        Path directory = loader.getGameDir().resolve("shaderpacks");
        Path configPath = loader.getConfigDir().resolve("lodeframe-shaders.properties");
        try {
            LodeframeShaderPacks result = new LodeframeShaderPacks(directory, ShaderPackConfig.load(configPath));
            result.restoreConfiguredPack();
            return result;
        } catch (IOException exception) {
            Lodeframe.LOGGER.error("Unable to load shader-pack settings", exception);
            return new LodeframeShaderPacks(directory, ShaderPackConfig.defaults(configPath));
        }
    }

    private static final class Holder {
        private static final LodeframeShaderPacks INSTANCE = createDefault();
    }
}
