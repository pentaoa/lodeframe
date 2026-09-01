package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.shaders.pack.ShaderDirectives;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderProgramType;
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
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ShaderPackRenderGraph implements AutoCloseable {
    private static final Pattern COLORTEX = Pattern.compile("colortex(\\d+)");
    private static final Map<String, Integer> LEGACY_COLORTEX = Map.of(
            "gcolor", 0,
            "gdepth", 1,
            "gnormal", 2,
            "composite", 3,
            "gaux1", 4,
            "gaux2", 5,
            "gaux3", 6,
            "gaux4", 7
    );

    private final MetalDevice device;
    private final MetalCommandEncoder commandEncoder;
    private final ShaderPackProgramSet programSet;
    private final List<ProgramPass> passes = new ArrayList<>();
    private final Map<Integer, TargetPair> targets = new HashMap<>();
    private final long startNanos = System.nanoTime();
    private final GpuSampler sampler;
    private final GpuSampler comparisonSampler;
    private int width;
    private int height;
    private @Nullable GpuFormat inputFormat;
    private @Nullable GpuTexture neutralColor;
    private @Nullable GpuTextureView neutralColorView;
    private @Nullable GpuTexture neutralDepth;
    private @Nullable GpuTextureView neutralDepthView;
    private @Nullable GpuTexture outputTexture;
    private @Nullable GpuTextureView outputView;
    private @Nullable GpuTextureView frameDepthView;
    private @Nullable GpuTexture worldDepthTexture;
    private @Nullable GpuTextureView worldDepthView;
    private int worldDepthWidth;
    private int worldDepthHeight;
    private int frameCounter;
    private @Nullable float[] previousProjection;
    private @Nullable float[] previousModelView;
    private float previousCameraX;
    private float previousCameraY;
    private float previousCameraZ;

    ShaderPackRenderGraph(
            final MetalDevice device,
            final MetalCommandEncoder commandEncoder,
            final ShaderPackProgramSet programSet
    ) {
        this.device = device;
        this.commandEncoder = commandEncoder;
        this.programSet = programSet;
        this.sampler = device.createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                1,
                OptionalDouble.empty()
        );
        this.comparisonSampler = device.createComparisonSampler();
    }

    GpuTextureView process(final GpuTextureView input, final ShaderPackFrameContext context) {
        ensureResources(input);
        this.frameDepthView = capturedWorldDepth();
        prepareFrame(input);
        float[] priorProjection = this.previousProjection == null ? context.projection() : this.previousProjection;
        float[] priorModelView = this.previousModelView == null ? context.modelView() : this.previousModelView;
        FrameValues values = new FrameValues(
                this.width,
                this.height,
                this.frameCounter++,
                (System.nanoTime() - this.startNanos) / 1_000_000_000.0F,
                context,
                priorProjection,
                priorModelView,
                this.previousProjection == null ? context.cameraX() : this.previousCameraX,
                this.previousProjection == null ? context.cameraY() : this.previousCameraY,
                this.previousProjection == null ? context.cameraZ() : this.previousCameraZ
        );
        for (ProgramPass pass : this.passes) {
            pass.updateUniforms(values);
            if (pass.program().program().type() == ShaderProgramType.FINAL) {
                executeFinal(pass);
            } else {
                executeFullscreen(pass);
            }
        }
        this.previousProjection = context.projection().clone();
        this.previousModelView = context.modelView().clone();
        this.previousCameraX = context.cameraX();
        this.previousCameraY = context.cameraY();
        this.previousCameraZ = context.cameraZ();
        this.frameDepthView = null;
        return this.outputView;
    }

    void captureWorldDepth(final GpuTextureView depth) {
        int capturedWidth = depth.getWidth(0);
        int capturedHeight = depth.getHeight(0);
        if (this.worldDepthView == null
                || this.worldDepthWidth != capturedWidth
                || this.worldDepthHeight != capturedHeight) {
            releaseWorldDepth();
            this.worldDepthWidth = capturedWidth;
            this.worldDepthHeight = capturedHeight;
            this.worldDepthTexture = createTexture(
                    "shader pack world depth",
                    GpuFormat.R32_FLOAT,
                    capturedWidth,
                    capturedHeight
            );
            this.worldDepthView = this.device.createTextureView(this.worldDepthTexture);
        }
        this.commandEncoder.copyReversedDepthToLegacyColor(depth, this.worldDepthView);
    }

    private @Nullable GpuTextureView capturedWorldDepth() {
        if (this.worldDepthWidth == this.width && this.worldDepthHeight == this.height) {
            return this.worldDepthView;
        }
        return null;
    }

    private void ensureResources(final GpuTextureView input) {
        GpuFormat format = input.texture().getFormat();
        if (this.outputView != null
                && this.width == input.getWidth(0)
                && this.height == input.getHeight(0)
                && this.inputFormat == format) {
            return;
        }
        releaseColorResources();
        this.width = input.getWidth(0);
        this.height = input.getHeight(0);
        this.inputFormat = format;

        Map<Integer, GpuFormat> effectiveFormats = new HashMap<>(this.programSet.bufferFormats());
        effectiveFormats.put(0, format);
        effectiveFormats.put(1, format);
        for (int index : usedColorBuffers()) {
            GpuFormat targetFormat = effectiveFormats.getOrDefault(index, GpuFormat.RGBA8_UNORM);
            this.targets.put(index, createPair(index, targetFormat));
        }
        this.neutralColor = createTexture("shader pack neutral color", GpuFormat.R32_FLOAT, 1, 1);
        this.neutralColorView = this.device.createTextureView(this.neutralColor);
        this.neutralDepth = createTexture("shader pack neutral depth", GpuFormat.D32_FLOAT, 1, 1);
        this.neutralDepthView = this.device.createTextureView(this.neutralDepth);
        this.commandEncoder.clearColorTexture(this.neutralColor, new Vector4f(1.0F));
        this.commandEncoder.clearDepthTexture(this.neutralDepth, 1.0);

        this.outputTexture = createTexture("shader pack final output", format, this.width, this.height);
        this.outputView = this.device.createTextureView(this.outputTexture);
        for (ShaderPackProgramLoader.PreparedProgram program : this.programSet.fullscreenPrograms()) {
            RenderPipeline pipeline = ShaderPackPipelineFactory.create(program, effectiveFormats, format);
            CompiledRenderPipeline compiled = this.device.precompilePipeline(pipeline, program.shaderSource());
            if (!compiled.isValid()) {
                throw new IllegalStateException("Metal rejected shader-pack program " + program.program().key());
            }
            this.passes.add(new ProgramPass(program, pipeline));
        }
    }

    private Set<Integer> usedColorBuffers() {
        Set<Integer> result = new LinkedHashSet<>(this.programSet.bufferFormats().keySet());
        result.add(0);
        result.add(1);
        for (ShaderPackProgramLoader.PreparedProgram program : this.programSet.fullscreenPrograms()) {
            program.renderTargets().ifPresent(targets -> result.addAll(targets.buffers()));
            program.vertex().samplers().forEach(sampler -> addSamplerBuffer(result, sampler.name()));
            program.fragment().samplers().forEach(sampler -> addSamplerBuffer(result, sampler.name()));
        }
        return result;
    }

    private static void addSamplerBuffer(final Set<Integer> buffers, final String name) {
        Integer legacy = LEGACY_COLORTEX.get(name);
        if (legacy != null) {
            buffers.add(legacy);
            return;
        }
        Matcher matcher = COLORTEX.matcher(name);
        if (matcher.matches()) {
            buffers.add(Integer.parseInt(matcher.group(1)));
        }
    }

    private void prepareFrame(final GpuTextureView input) {
        for (Map.Entry<Integer, TargetPair> entry : this.targets.entrySet()) {
            TargetPair pair = entry.getValue();
            pair.reset();
            if (this.programSet.bufferClears().getOrDefault(entry.getKey(), true)) {
                ShaderDirectives.ClearColor configured = this.programSet.bufferClearColors().get(entry.getKey());
                Vector4f color = configured == null
                        ? new Vector4f(0.0F)
                        : new Vector4f(configured.red(), configured.green(), configured.blue(), configured.alpha());
                this.commandEncoder.clearColorTexture(pair.first().texture(), color);
                this.commandEncoder.clearColorTexture(pair.second().texture(), color);
            }
        }
        copyInput(input, this.targets.get(0).read());
        copyInput(input, this.targets.get(1).read());
    }

    private void copyInput(final GpuTextureView input, final GpuTextureView destination) {
        this.commandEncoder.copyTextureToTexture(
                input.texture(),
                destination.texture(),
                0,
                0,
                0,
                0,
                0,
                this.width,
                this.height
        );
    }

    private void executeFullscreen(final ProgramPass pass) {
        List<Integer> physicalTargets = pass.program().renderTargets()
                .map(ShaderDirectives.RenderTargets::buffers)
                .orElse(List.of(0));
        Set<Integer> outputs = pass.program().fragment().fragmentOutputLocations();
        int highest = outputs.stream().mapToInt(Integer::intValue).max().orElse(0);
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(
                () -> "Lodeframe " + pass.program().program().key()
        );
        for (int location = 0; location <= highest; location++) {
            if (outputs.contains(location)) {
                descriptor.withColorAttachment(this.targets.get(physicalTargets.get(location)).write());
            } else {
                descriptor.withUnusedColorAttachment();
            }
        }
        descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, this.width, this.height));
        draw(pass, descriptor);
        for (int location : outputs) {
            this.targets.get(physicalTargets.get(location)).flip();
        }
    }

    private void executeFinal(final ProgramPass pass) {
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Lodeframe shader pack final")
                .withColorAttachment(this.outputView)
                .withRenderArea(new RenderPass.RenderArea(0, 0, this.width, this.height));
        draw(pass, descriptor);
    }

    private void draw(final ProgramPass program, final RenderPassDescriptor descriptor) {
        MetalRenderPass pass = (MetalRenderPass) this.commandEncoder.createRenderPass(descriptor);
        try {
            pass.setPipeline(program.pipeline());
            bindSamplers(pass, program.program().vertex().samplers());
            bindSamplers(pass, program.program().fragment().samplers());
            program.bindUniforms(pass);
            pass.draw(3, 1, 0, 0);
        } finally {
            this.commandEncoder.submitRenderPass();
        }
    }

    private void bindSamplers(
            final MetalRenderPass pass,
            final List<LegacyFullscreenTransformer.SamplerField> samplers
    ) {
        for (LegacyFullscreenTransformer.SamplerField samplerField : samplers) {
            GpuTextureView texture = textureForSampler(samplerField.name());
            GpuSampler selectedSampler = samplerField.type().contains("Shadow")
                    ? this.comparisonSampler
                    : this.sampler;
            pass.bindTexture(samplerField.name(), texture, selectedSampler);
        }
    }

    private GpuTextureView textureForSampler(final String name) {
        Integer legacy = LEGACY_COLORTEX.get(name);
        if (legacy != null) {
            return this.targets.get(legacy).read();
        }
        Matcher matcher = COLORTEX.matcher(name);
        if (matcher.matches()) {
            TargetPair target = this.targets.get(Integer.parseInt(matcher.group(1)));
            return target == null ? this.neutralColorView : target.read();
        }
        if (name.startsWith("shadowtex")) {
            return this.neutralDepthView;
        }
        if (name.startsWith("depthtex")) {
            return this.frameDepthView == null ? this.neutralDepthView : this.frameDepthView;
        }
        return this.neutralColorView;
    }

    private TargetPair createPair(final int index, final GpuFormat format) {
        GpuTexture first = createTexture("shader pack colortex" + index + " main", format, this.width, this.height);
        GpuTexture second = createTexture("shader pack colortex" + index + " alternate", format, this.width, this.height);
        return new TargetPair(first, this.device.createTextureView(first), second, this.device.createTextureView(second));
    }

    private GpuTexture createTexture(final String label, final GpuFormat format, final int width, final int height) {
        return this.device.createTexture(
                label,
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                format,
                width,
                height,
                1,
                1
        );
    }

    @Override
    public void close() {
        releaseColorResources();
        releaseWorldDepth();
        this.sampler.close();
        this.comparisonSampler.close();
    }

    private void releaseColorResources() {
        this.passes.forEach(ProgramPass::close);
        this.passes.clear();
        this.targets.values().forEach(TargetPair::close);
        this.targets.clear();
        close(this.neutralColorView);
        close(this.neutralColor);
        close(this.neutralDepthView);
        close(this.neutralDepth);
        close(this.outputView);
        close(this.outputTexture);
        this.neutralColorView = null;
        this.neutralColor = null;
        this.neutralDepthView = null;
        this.neutralDepth = null;
        this.outputView = null;
        this.outputTexture = null;
        this.frameDepthView = null;
        this.previousProjection = null;
        this.previousModelView = null;
    }

    private void releaseWorldDepth() {
        close(this.worldDepthView);
        close(this.worldDepthTexture);
        this.worldDepthView = null;
        this.worldDepthTexture = null;
        this.worldDepthWidth = 0;
        this.worldDepthHeight = 0;
    }

    private static void close(@Nullable final AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to close shader-pack Metal resource", exception);
            }
        }
    }

    private final class ProgramPass implements AutoCloseable {
        private final ShaderPackProgramLoader.PreparedProgram program;
        private final RenderPipeline pipeline;
        private final @Nullable UniformState vertexUniforms;
        private final @Nullable UniformState fragmentUniforms;

        private ProgramPass(
                final ShaderPackProgramLoader.PreparedProgram program,
                final RenderPipeline pipeline
        ) {
            this.program = program;
            this.pipeline = pipeline;
            this.vertexUniforms = program.vertex().uniforms().isEmpty()
                    ? null
                    : new UniformState(program.vertex().uniforms(), "vertex");
            this.fragmentUniforms = program.fragment().uniforms().isEmpty()
                    ? null
                    : new UniformState(program.fragment().uniforms(), "fragment");
        }

        ShaderPackProgramLoader.PreparedProgram program() {
            return this.program;
        }

        RenderPipeline pipeline() {
            return this.pipeline;
        }

        void updateUniforms(final FrameValues values) {
            if (this.vertexUniforms != null) {
                this.vertexUniforms.update(values);
            }
            if (this.fragmentUniforms != null) {
                this.fragmentUniforms.update(values);
            }
        }

        void bindUniforms(final MetalRenderPass pass) {
            if (this.vertexUniforms != null) {
                pass.setUniform(
                        LegacyFullscreenTransformer.uniformBlockName(ShaderStage.VERTEX),
                        this.vertexUniforms.buffer
                );
            }
            if (this.fragmentUniforms != null) {
                pass.setUniform(
                        LegacyFullscreenTransformer.uniformBlockName(ShaderStage.FRAGMENT),
                        this.fragmentUniforms.buffer
                );
            }
        }

        @Override
        public void close() {
            device.forgetPipelineShaderSource(this.pipeline);
            if (this.vertexUniforms != null) {
                this.vertexUniforms.close();
            }
            if (this.fragmentUniforms != null) {
                this.fragmentUniforms.close();
            }
        }

        private final class UniformState implements AutoCloseable {
            private final ShaderPackUniformLayout layout;
            private final ByteBuffer data;
            private final GpuBuffer buffer;

            private UniformState(
                    final List<LegacyFullscreenTransformer.UniformField> fields,
                    final String stage
            ) {
                this.layout = ShaderPackUniformLayout.of(fields);
                this.data = ByteBuffer.allocateDirect(this.layout.size()).order(ByteOrder.nativeOrder());
                this.buffer = device.createBuffer(
                        () -> "shader pack " + ProgramPass.this.program.program().key() + " " + stage + " uniforms",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                        this.layout.size()
                );
            }

            void update(final FrameValues values) {
                this.layout.write(this.data, values);
                commandEncoder.writeToBuffer(this.buffer.slice(), this.data);
            }

            @Override
            public void close() {
                this.buffer.close();
            }
        }
    }

    private static final class TargetPair implements AutoCloseable {
        private final GpuTexture firstTexture;
        private final GpuTextureView first;
        private final GpuTexture secondTexture;
        private final GpuTextureView second;
        private boolean secondIsRead;

        private TargetPair(
                final GpuTexture firstTexture,
                final GpuTextureView first,
                final GpuTexture secondTexture,
                final GpuTextureView second
        ) {
            this.firstTexture = firstTexture;
            this.first = first;
            this.secondTexture = secondTexture;
            this.second = second;
        }

        GpuTextureView first() {
            return this.first;
        }

        GpuTextureView second() {
            return this.second;
        }

        GpuTextureView read() {
            return this.secondIsRead ? this.second : this.first;
        }

        GpuTextureView write() {
            return this.secondIsRead ? this.first : this.second;
        }

        void flip() {
            this.secondIsRead = !this.secondIsRead;
        }

        void reset() {
            this.secondIsRead = false;
        }

        @Override
        public void close() {
            ShaderPackRenderGraph.close(this.first);
            ShaderPackRenderGraph.close(this.firstTexture);
            ShaderPackRenderGraph.close(this.second);
            ShaderPackRenderGraph.close(this.secondTexture);
        }
    }

    private record FrameValues(
            int width,
            int height,
            int frame,
            float time,
            ShaderPackFrameContext context,
            float[] previousProjection,
            float[] previousModelView,
            float previousCameraX,
            float previousCameraY,
            float previousCameraZ
    )
            implements ShaderPackUniformLayout.FrameValues {
        @Override
        public int integer(final String name) {
            return switch (name) {
                case "frameCounter" -> this.frame;
                case "worldTime" -> (int) (this.time * 20.0F) % 24000;
                case "eyeBrightness", "eyeBrightnessSmooth" -> 240;
                default -> 0;
            };
        }

        @Override
        public int[] integerVector(final String name, final int components) {
            int[] result = new int[components];
            if (name.equals("eyeBrightness") || name.equals("eyeBrightnessSmooth")) {
                java.util.Arrays.fill(result, 240);
            }
            return result;
        }

        @Override
        public float[] floatVector(final String name, final int components) {
            float[] result = new float[components];
            switch (name) {
                case "viewWidth" -> result[0] = this.width;
                case "viewHeight" -> result[0] = this.height;
                case "aspectRatio" -> result[0] = (float) this.width / this.height;
                case "frameTimeCounter" -> result[0] = this.time;
                case "frameTime" -> result[0] = 1.0F / 60.0F;
                case "near" -> result[0] = this.context.near();
                case "far" -> result[0] = this.context.far();
                case "cameraPosition" -> {
                    result[0] = this.context.cameraX();
                    result[1] = this.context.cameraY();
                    result[2] = this.context.cameraZ();
                }
                case "previousCameraPosition" -> {
                    result[0] = this.previousCameraX;
                    result[1] = this.previousCameraY;
                    result[2] = this.previousCameraZ;
                }
                case "timeBrightness", "shadowFade" -> result[0] = 1.0F;
                case "upVec" -> result[1] = 1.0F;
                case "sunVec" -> {
                    result[0] = 0.3F;
                    result[1] = 0.9F;
                    result[2] = 0.2F;
                }
                default -> {
                }
            }
            return result;
        }

        @Override
        public float[] matrix(final String name, final int columns) {
            if (columns != 4) {
                float[] identity = new float[columns * columns];
                for (int index = 0; index < columns; index++) {
                    identity[index * columns + index] = 1.0F;
                }
                return identity;
            }
            return switch (name) {
                case "gbufferProjection" -> this.context.projection();
                case "gbufferProjectionInverse" -> this.context.projectionInverse();
                case "gbufferModelView" -> this.context.modelView();
                case "gbufferModelViewInverse" -> this.context.modelViewInverse();
                case "gbufferPreviousProjection" -> this.previousProjection;
                case "gbufferPreviousModelView" -> this.previousModelView;
                default -> new float[]{
                        1.0F, 0.0F, 0.0F, 0.0F,
                        0.0F, 1.0F, 0.0F, 0.0F,
                        0.0F, 0.0F, 1.0F, 0.0F,
                        0.0F, 0.0F, 0.0F, 1.0F
                };
            };
        }
    }
}
