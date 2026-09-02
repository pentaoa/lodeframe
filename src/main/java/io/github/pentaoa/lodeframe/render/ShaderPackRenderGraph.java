package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.shaders.pack.ShaderDirectives;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderProgramType;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderStage;
import io.github.pentaoa.lodeframe.shaders.translate.LegacyFullscreenTransformer;
import com.mojang.blaze3d.GpuFormat;
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
    private final ShaderPackTextureResources packTextures;
    private final List<ProgramPass> passes = new ArrayList<>();
    private final Map<Integer, TargetPair> targets = new HashMap<>();
    private final GpuSampler sampler;
    private final GpuSampler depthSampler;
    private final DepthSnapshot finalDepth = new DepthSnapshot("shader pack depthtex0");
    private final DepthSnapshot preTranslucentDepth = new DepthSnapshot("shader pack depthtex1");
    private final DepthSnapshot preHandDepth = new DepthSnapshot("shader pack depthtex2");
    private int width;
    private int height;
    private @Nullable GpuFormat inputFormat;
    private @Nullable GpuTexture outputTexture;
    private @Nullable GpuTextureView outputView;
    private boolean framePrepared;
    private boolean preTranslucentDepthCaptured;

    ShaderPackRenderGraph(
            final MetalDevice device,
            final MetalCommandEncoder commandEncoder,
            final ShaderPackProgramSet programSet,
            final ShaderPackTextureResources packTextures
    ) {
        this.device = device;
        this.commandEncoder = commandEncoder;
        this.programSet = programSet;
        this.packTextures = packTextures;
        this.sampler = device.createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                1,
                OptionalDouble.empty()
        );
        this.depthSampler = device.createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                OptionalDouble.empty()
        );
    }

    GpuTextureView process(final GpuTextureView input, final ShaderPackFrameValues values) {
        ensureResources(input);
        if (!this.framePrepared) {
            beginFrame(input);
        }
        copyInput(input, this.targets.get(0).read());
        boolean finalExecuted = false;
        for (ProgramPass pass : this.passes) {
            pass.updateUniforms(values);
            if (pass.program().program().type() == ShaderProgramType.FINAL) {
                executeFinal(pass);
                finalExecuted = true;
            } else {
                executeFullscreen(pass);
            }
        }
        this.framePrepared = false;
        return finalExecuted ? this.outputView : this.targets.get(0).read();
    }

    void beginFrame(final GpuTextureView input) {
        ensureResources(input);
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
        this.finalDepth.invalidate();
        this.preTranslucentDepth.invalidate();
        this.preHandDepth.invalidate();
        this.preTranslucentDepthCaptured = false;
        this.framePrepared = true;
    }

    @Nullable GpuTextureView gbufferColorAttachment(
            final ShaderPackProgramLoader.PreparedProgram program,
            final int location
    ) {
        List<Integer> physicalTargets = program.renderTargets()
                .map(ShaderDirectives.RenderTargets::buffers)
                .orElse(List.of(0));
        if (location < 0 || location >= physicalTargets.size()) {
            return null;
        }
        TargetPair target = this.targets.get(physicalTargets.get(location));
        return target == null ? null : target.read();
    }

    void capturePreTranslucentDepth(final GpuTextureView depth) {
        if (!this.preTranslucentDepthCaptured) {
            this.preTranslucentDepth.capture(depth);
            this.preTranslucentDepthCaptured = true;
        }
    }

    void capturePreHandDepth(final GpuTextureView depth) {
        this.preHandDepth.capture(depth);
    }

    void captureFinalDepth(final GpuTextureView depth) {
        this.finalDepth.capture(depth);
    }

    void bindGeometrySamplers(
            final MetalRenderPass pass,
            final ShaderPackProgramLoader.PreparedProgram program
    ) {
        program.vertex().samplers().forEach(field -> bindGraphSampler(pass, field));
        program.fragment().samplers().forEach(field -> bindGraphSampler(pass, field));
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
        for (int index : usedColorBuffers()) {
            GpuFormat targetFormat = effectiveFormats.getOrDefault(index, GpuFormat.RGBA8_UNORM);
            this.targets.put(index, createPair(index, targetFormat));
        }
        this.outputTexture = createTexture("shader pack final output", format, this.width, this.height);
        this.outputView = this.device.createTextureView(this.outputTexture);
        for (ShaderPackProgramLoader.PreparedProgram program : this.programSet.fullscreenPrograms()) {
            RenderPipeline pipeline = ShaderPackPipelineFactory.create(program, effectiveFormats, format);
            CompiledRenderPipeline compiled = this.device.precompilePipeline(pipeline, program.shaderSource());
            if (!compiled.isValid()) {
                this.device.releasePipeline(pipeline);
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
        addProgramBuffers(result, this.programSet.terrainProgram());
        addProgramBuffers(result, this.programSet.waterProgram());
        addProgramBuffers(result, this.programSet.skyBasicProgram());
        addProgramBuffers(result, this.programSet.entitiesProgram());
        addProgramBuffers(result, this.programSet.entitiesGlowingProgram());
        addProgramBuffers(result, this.programSet.handProgram());
        addProgramBuffers(result, this.programSet.handWaterProgram());
        addProgramBuffers(result, this.programSet.texturedProgram());
        addProgramBuffers(result, this.programSet.weatherProgram());
        return result;
    }

    private static void addProgramBuffers(
            final Set<Integer> result,
            final ShaderPackProgramLoader.@Nullable PreparedProgram program
    ) {
        if (program == null) {
            return;
        }
        program.renderTargets().ifPresent(targets -> result.addAll(targets.buffers()));
        program.vertex().samplers().forEach(sampler -> addSamplerBuffer(result, sampler.name()));
        program.fragment().samplers().forEach(sampler -> addSamplerBuffer(result, sampler.name()));
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
            GpuSampler selectedSampler = samplerField.name().startsWith("depthtex")
                    ? this.depthSampler
                    : samplerField.type().contains("Shadow")
                    || this.packTextures.forSampler(samplerField.name()) != null
                    ? this.packTextures.samplerFor(samplerField)
                    : this.sampler;
            pass.bindTexture(samplerField.name(), texture, selectedSampler);
        }
    }

    private GpuTextureView textureForSampler(final String name) {
        GpuTextureView customTexture = this.packTextures.forSampler(name);
        if (customTexture != null) {
            return customTexture;
        }
        Integer legacy = LEGACY_COLORTEX.get(name);
        if (legacy != null) {
            return this.targets.get(legacy).read();
        }
        Matcher matcher = COLORTEX.matcher(name);
        if (matcher.matches()) {
            TargetPair target = this.targets.get(Integer.parseInt(matcher.group(1)));
            return target == null ? this.packTextures.neutralColorView() : target.read();
        }
        if (name.startsWith("shadowtex") || name.equals("watershadow")) {
            return this.packTextures.neutralShadowDepthView();
        }
        if (name.equals("depthtex0")) {
            return firstDepth(this.finalDepth, this.preHandDepth, this.preTranslucentDepth);
        }
        if (name.equals("depthtex1")) {
            return firstDepth(this.preTranslucentDepth, this.preHandDepth, this.finalDepth);
        }
        if (name.equals("depthtex2")) {
            return firstDepth(this.preHandDepth, this.finalDepth, this.preTranslucentDepth);
        }
        return this.packTextures.neutralColorView();
    }

    private void bindGraphSampler(
            final MetalRenderPass pass,
            final LegacyFullscreenTransformer.SamplerField field
    ) {
        String name = field.name();
        if (!isGraphSampler(name)) {
            return;
        }
        pass.bindTexture(name, textureForSampler(name), name.startsWith("depthtex") ? this.depthSampler : this.sampler);
    }

    private static boolean isGraphSampler(final String name) {
        return name.startsWith("depthtex") || LEGACY_COLORTEX.containsKey(name) || COLORTEX.matcher(name).matches();
    }

    private GpuTextureView firstDepth(final DepthSnapshot first, final DepthSnapshot second, final DepthSnapshot third) {
        GpuTextureView result = first.view(this.width, this.height);
        if (result == null) {
            result = second.view(this.width, this.height);
        }
        if (result == null) {
            result = third.view(this.width, this.height);
        }
        return result == null ? this.packTextures.neutralDepthView() : result;
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
        this.finalDepth.close();
        this.preTranslucentDepth.close();
        this.preHandDepth.close();
        this.depthSampler.close();
        this.sampler.close();
    }

    private void releaseColorResources() {
        this.passes.forEach(ProgramPass::close);
        this.passes.clear();
        this.targets.values().forEach(TargetPair::close);
        this.targets.clear();
        close(this.outputView);
        close(this.outputTexture);
        this.outputView = null;
        this.outputTexture = null;
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
        private final @Nullable ShaderPackUniformState vertexUniforms;
        private final @Nullable ShaderPackUniformState fragmentUniforms;

        private ProgramPass(
                final ShaderPackProgramLoader.PreparedProgram program,
                final RenderPipeline pipeline
        ) {
            this.program = program;
            this.pipeline = pipeline;
            this.vertexUniforms = program.vertex().uniforms().isEmpty()
                    ? null
                    : new ShaderPackUniformState(
                            device,
                            program.vertex().uniforms(),
                            "shader pack " + program.program().key() + " vertex uniforms"
                    );
            this.fragmentUniforms = program.fragment().uniforms().isEmpty()
                    ? null
                    : new ShaderPackUniformState(
                            device,
                            program.fragment().uniforms(),
                            "shader pack " + program.program().key() + " fragment uniforms"
                    );
        }

        ShaderPackProgramLoader.PreparedProgram program() {
            return this.program;
        }

        RenderPipeline pipeline() {
            return this.pipeline;
        }

        void updateUniforms(final ShaderPackFrameValues values) {
            if (this.vertexUniforms != null) {
                this.vertexUniforms.update(commandEncoder, values);
            }
            if (this.fragmentUniforms != null) {
                this.fragmentUniforms.update(commandEncoder, values);
            }
        }

        void bindUniforms(final MetalRenderPass pass) {
            if (this.vertexUniforms != null) {
                this.vertexUniforms.bind(
                        pass,
                        LegacyFullscreenTransformer.uniformBlockName(ShaderStage.VERTEX)
                );
            }
            if (this.fragmentUniforms != null) {
                this.fragmentUniforms.bind(
                        pass,
                        LegacyFullscreenTransformer.uniformBlockName(ShaderStage.FRAGMENT)
                );
            }
        }

        @Override
        public void close() {
            device.releasePipeline(this.pipeline);
            if (this.vertexUniforms != null) {
                this.vertexUniforms.close();
            }
            if (this.fragmentUniforms != null) {
                this.fragmentUniforms.close();
            }
        }

    }

    private final class DepthSnapshot implements AutoCloseable {
        private final String label;
        private @Nullable GpuTexture texture;
        private @Nullable GpuTextureView view;
        private int width;
        private int height;
        private boolean valid;

        private DepthSnapshot(final String label) {
            this.label = label;
        }

        void capture(final GpuTextureView source) {
            int capturedWidth = source.getWidth(0);
            int capturedHeight = source.getHeight(0);
            if (this.view == null || this.width != capturedWidth || this.height != capturedHeight) {
                close();
                this.width = capturedWidth;
                this.height = capturedHeight;
                this.texture = createTexture(this.label, GpuFormat.R32_FLOAT, capturedWidth, capturedHeight);
                this.view = device.createTextureView(this.texture);
            }
            commandEncoder.copyReversedDepthToLegacyColor(source, this.view);
            this.valid = true;
        }

        @Nullable GpuTextureView view(final int expectedWidth, final int expectedHeight) {
            return this.valid && this.width == expectedWidth && this.height == expectedHeight ? this.view : null;
        }

        void invalidate() {
            this.valid = false;
        }

        @Override
        public void close() {
            ShaderPackRenderGraph.close(this.view);
            ShaderPackRenderGraph.close(this.texture);
            this.view = null;
            this.texture = null;
            this.width = 0;
            this.height = 0;
            this.valid = false;
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

}
