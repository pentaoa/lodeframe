package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.Lodeframe;
import io.github.pentaoa.lodeframe.client.shader.LodeframeShaderPacks;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackException;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackReport;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderStage;
import io.github.pentaoa.lodeframe.shaders.translate.LegacyFullscreenTransformer;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Executes the shader pack's final program as the first real pack-backed Metal pass.
 * The complete Iris-compatible graph will feed this pass once gbuffer and composite
 * programs are available.
 */
@Environment(EnvType.CLIENT)
final class ShaderPackPostProcessor implements AutoCloseable {
    private static final String UNIFORM_BLOCK = LegacyFullscreenTransformer.uniformBlockName(ShaderStage.FRAGMENT);
    private static final String INPUT_SAMPLER = "colortex1";

    private final MetalDevice device;
    private final MetalCommandEncoder commandEncoder;
    private final long startNanos = System.nanoTime();
    private final ByteBuffer uniformData = ByteBuffer.allocateDirect(4 * Float.BYTES).order(ByteOrder.nativeOrder());

    private long loadedRevision = Long.MIN_VALUE;
    private boolean failed;
    private boolean announced;
    private @Nullable ShaderPackProgramSet programSet;
    private ShaderPackProgramLoader.@Nullable PreparedProgram program;
    @Nullable
    private RenderPipeline pipeline;
    @Nullable
    private GpuTexture targetTexture;
    @Nullable
    private GpuTextureView targetView;
    @Nullable
    private GpuSampler sampler;
    @Nullable
    private GpuBuffer uniformBuffer;
    private int targetWidth;
    private int targetHeight;
    @Nullable
    private GpuFormat targetFormat;
    @Nullable
    private GpuFormat pipelineFormat;

    ShaderPackPostProcessor(final MetalDevice device, final MetalCommandEncoder commandEncoder) {
        this.device = device;
        this.commandEncoder = commandEncoder;
    }

    GpuTextureView process(final GpuTextureView inputView) {
        LodeframeShaderPacks shaderPacks = LodeframeShaderPacks.getInstance();
        long revision = shaderPacks.revision();
        if (revision != this.loadedRevision) {
            reload(shaderPacks, revision);
        }
        if (this.program == null || this.failed) {
            return inputView;
        }

        try {
            ensurePipeline(inputView.texture().getFormat());
            ensureResources(inputView);
            updateUniforms(inputView.getWidth(0), inputView.getHeight(0));

            CompiledRenderPipeline compiled = this.device.precompilePipeline(this.pipeline, this.program.shaderSource());
            if (!compiled.isValid()) {
                throw new IllegalStateException("Metal rejected the BSL final pipeline");
            }

            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Lodeframe shader pack final")
                    .withColorAttachment(this.targetView)
                    .withRenderArea(new RenderPass.RenderArea(0, 0, this.targetWidth, this.targetHeight));
            MetalRenderPass pass = (MetalRenderPass) this.commandEncoder.createRenderPass(descriptor);
            try {
                pass.setPipeline(this.pipeline);
                pass.bindTexture(INPUT_SAMPLER, inputView, this.sampler);
                pass.setUniform(UNIFORM_BLOCK, this.uniformBuffer);
                pass.draw(3, 1, 0, 0);
            } finally {
                this.commandEncoder.submitRenderPass();
            }

            if (!this.announced) {
                this.announced = true;
                Lodeframe.LOGGER.info(
                        "Shader pack final program is rendering through Metal: {} / {}",
                        this.program.vertexPath(),
                        this.program.fragmentPath()
                );
            }
            return this.targetView;
        } catch (RuntimeException exception) {
            this.failed = true;
            Lodeframe.LOGGER.error(
                    "Shader pack final program failed for revision {}; presenting the unmodified frame",
                    this.loadedRevision,
                    exception
            );
            return inputView;
        }
    }

    private void reload(final LodeframeShaderPacks shaderPacks, final long revision) {
        releaseResources();
        this.loadedRevision = revision;
        this.failed = false;
        this.announced = false;
        this.program = null;
        this.programSet = null;
        this.pipeline = null;
        this.pipelineFormat = null;

        Optional<Path> source = shaderPacks.activeSource();
        Optional<ShaderPackReport> report = shaderPacks.activeReport();
        if (source.isEmpty() || report.isEmpty()) {
            return;
        }

        try {
            this.programSet = ShaderPackProgramSet.load(source.get(), report.get(), "world0", revision);
            this.program = this.programSet.finalProgram();
            for (ShaderPackProgramLoader.PreparedProgram prepared : this.programSet.fullscreenPrograms()) {
                if (prepared == this.program) {
                    continue;
                }
                RenderPipeline preparedPipeline = ShaderPackPipelineFactory.create(
                        prepared,
                        this.programSet.bufferFormats(),
                        GpuFormat.RGBA8_UNORM
                );
                CompiledRenderPipeline compiled = this.device.precompilePipeline(
                        preparedPipeline,
                        prepared.shaderSource()
                );
                if (!compiled.isValid()) {
                    throw new IllegalStateException("Metal rejected shader-pack program " + prepared.program().key());
                }
            }
            Lodeframe.LOGGER.info(
                    "Compiled {} fullscreen shader-pack programs for Metal",
                    this.programSet.fullscreenPrograms().size()
            );
        } catch (IOException | ShaderPackException | RuntimeException exception) {
            this.failed = true;
            Lodeframe.LOGGER.error("Unable to prepare the shader pack final program", exception);
        }
    }

    private void ensurePipeline(final GpuFormat format) {
        if (this.pipeline != null && this.pipelineFormat == format) {
            return;
        }
        this.pipelineFormat = format;
        this.pipeline = ShaderPackPipelineFactory.create(this.program, this.programSet.bufferFormats(), format);
    }

    private void ensureResources(final GpuTextureView inputView) {
        int width = inputView.getWidth(0);
        int height = inputView.getHeight(0);
        GpuFormat format = inputView.texture().getFormat();
        if (this.targetTexture != null
                && this.targetWidth == width
                && this.targetHeight == height
                && this.targetFormat == format) {
            return;
        }

        releaseResources();
        this.targetWidth = width;
        this.targetHeight = height;
        this.targetFormat = format;
        this.targetTexture = this.device.createTexture(
                "Lodeframe shader pack final target",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                format,
                width,
                height,
                1,
                1
        );
        this.targetView = this.device.createTextureView(this.targetTexture);
        this.sampler = this.device.createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                1,
                OptionalDouble.of(0.0)
        );
        this.uniformBuffer = this.device.createBuffer(
                () -> "Lodeframe shader pack final uniforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                this.uniformData.capacity()
        );
    }

    private void updateUniforms(final int width, final int height) {
        float frameTime = (System.nanoTime() - this.startNanos) / 1_000_000_000.0F;
        this.uniformData.clear();
        this.uniformData.putFloat(width);
        this.uniformData.putFloat(height);
        this.uniformData.putFloat((float) width / height);
        this.uniformData.putFloat(frameTime);
        this.uniformData.flip();
        this.commandEncoder.writeToBuffer(this.uniformBuffer.slice(), this.uniformData);
    }

    private void releaseResources() {
        if (this.targetView != null) {
            this.targetView.close();
            this.targetView = null;
        }
        if (this.targetTexture != null) {
            this.targetTexture.close();
            this.targetTexture = null;
        }
        if (this.sampler != null) {
            this.sampler.close();
            this.sampler = null;
        }
        if (this.uniformBuffer != null) {
            this.uniformBuffer.close();
            this.uniformBuffer = null;
        }
        this.targetWidth = 0;
        this.targetHeight = 0;
        this.targetFormat = null;
    }

    @Override
    public void close() {
        releaseResources();
    }
}
