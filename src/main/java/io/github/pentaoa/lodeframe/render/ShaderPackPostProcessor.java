package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.Lodeframe;
import io.github.pentaoa.lodeframe.client.shader.LodeframeShaderPacks;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackException;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackReport;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Executes the shader pack's final program as the first real pack-backed Metal pass.
 * The complete Iris-compatible graph will feed this pass once gbuffer and composite
 * programs are available.
 */
@Environment(EnvType.CLIENT)
final class ShaderPackPostProcessor implements AutoCloseable {
    private final MetalDevice device;
    private final MetalCommandEncoder commandEncoder;

    private long loadedRevision = Long.MIN_VALUE;
    private boolean failed;
    private boolean announced;
    private @Nullable ShaderPackProgramSet programSet;
    private ShaderPackProgramLoader.@Nullable PreparedProgram program;
    private @Nullable ShaderPackRenderGraph renderGraph;

    ShaderPackPostProcessor(final MetalDevice device, final MetalCommandEncoder commandEncoder) {
        this.device = device;
        this.commandEncoder = commandEncoder;
    }

    void captureWorldDepth(final GpuTextureView depthView) {
        if (!prepareActiveGraph()) {
            return;
        }
        try {
            this.renderGraph.captureWorldDepth(depthView);
        } catch (RuntimeException exception) {
            failGraph(exception);
        }
    }

    void processWorld(final GpuTextureView colorView, final ShaderPackFrameContext frameContext) {
        GpuTextureView result = process(colorView, frameContext);
        if (result == colorView) {
            return;
        }
        this.commandEncoder.copyTextureToTexture(
                result.texture(),
                colorView.texture(),
                0,
                0,
                0,
                0,
                0,
                colorView.getWidth(0),
                colorView.getHeight(0)
        );
    }

    private GpuTextureView process(
            final GpuTextureView inputView,
            final ShaderPackFrameContext frameContext
    ) {
        if (!prepareActiveGraph()) {
            return inputView;
        }

        try {
            GpuTextureView result = this.renderGraph.process(inputView, frameContext);

            if (!this.announced) {
                this.announced = true;
                Lodeframe.LOGGER.info(
                        "Executing {} fullscreen shader-pack programs through Metal",
                        this.programSet.fullscreenPrograms().size()
                );
            }
            return result;
        } catch (RuntimeException exception) {
            failGraph(exception);
            return inputView;
        }
    }

    private boolean prepareActiveGraph() {
        LodeframeShaderPacks shaderPacks = LodeframeShaderPacks.getInstance();
        long revision = shaderPacks.revision();
        if (revision != this.loadedRevision) {
            reload(shaderPacks, revision);
        }
        if (this.program == null || this.failed) {
            return false;
        }
        if (this.renderGraph == null) {
            this.renderGraph = new ShaderPackRenderGraph(this.device, this.commandEncoder, this.programSet);
        }
        return true;
    }

    private void failGraph(final RuntimeException exception) {
        this.failed = true;
        Lodeframe.LOGGER.error(
                "Shader pack render graph failed for revision {}; keeping the unmodified world frame",
                this.loadedRevision,
                exception
        );
    }

    private void reload(final LodeframeShaderPacks shaderPacks, final long revision) {
        releaseResources();
        this.loadedRevision = revision;
        this.failed = false;
        this.announced = false;
        this.program = null;
        this.programSet = null;

        Optional<Path> source = shaderPacks.activeSource();
        Optional<ShaderPackReport> report = shaderPacks.activeReport();
        if (source.isEmpty() || report.isEmpty()) {
            return;
        }

        try {
            this.programSet = ShaderPackProgramSet.load(source.get(), report.get(), "world0", revision);
            this.program = this.programSet.finalProgram();
            Lodeframe.LOGGER.info(
                    "Prepared {} fullscreen shader-pack programs for Metal",
                    this.programSet.fullscreenPrograms().size()
            );
        } catch (IOException | ShaderPackException | RuntimeException exception) {
            this.failed = true;
            Lodeframe.LOGGER.error("Unable to prepare the shader pack final program", exception);
        }
    }

    private void releaseResources() {
        if (this.renderGraph != null) {
            this.renderGraph.close();
            this.renderGraph = null;
        }
    }

    @Override
    public void close() {
        releaseResources();
    }
}
