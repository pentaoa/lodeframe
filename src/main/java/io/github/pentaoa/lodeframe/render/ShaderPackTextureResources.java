package io.github.pentaoa.lodeframe.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import io.github.pentaoa.lodeframe.shaders.translate.LegacyFullscreenTransformer;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

final class ShaderPackTextureResources implements AutoCloseable {
    private final GpuTexture neutralColor;
    private final GpuTextureView neutralColorView;
    private final GpuTexture neutralDepth;
    private final GpuTextureView neutralDepthView;
    private final GpuTexture neutralShadowDepth;
    private final GpuTextureView neutralShadowDepthView;
    private final GpuSampler sampler;
    private final GpuSampler comparisonSampler;
    private @Nullable GpuTexture noiseTexture;
    private @Nullable GpuTextureView noiseView;
    private final Map<CustomKey, CustomBinding> customBindings = new LinkedHashMap<>();
    private final Map<String, UploadedTexture> customImages = new LinkedHashMap<>();
    private final Map<SamplerSettings, GpuSampler> customSamplers = new LinkedHashMap<>();
    private @Nullable GpuTextureView shadowDepthView;
    private @Nullable GpuTextureView shadowColorView;

    ShaderPackTextureResources(
            final MetalDevice device,
            final MetalCommandEncoder commandEncoder,
            final byte[] noiseSource,
            final List<ShaderPackCustomTexture> customTextures
    ) throws IOException {
        this.neutralColor = device.createTexture(
                "shader pack neutral color",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM,
                1,
                1,
                1,
                1
        );
        this.neutralColorView = device.createTextureView(this.neutralColor);
        this.neutralDepth = device.createTexture(
                "shader pack neutral depth",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.D32_FLOAT,
                1,
                1,
                1,
                1
        );
        this.neutralDepthView = device.createTextureView(this.neutralDepth);
        this.neutralShadowDepth = device.createTexture(
                "shader pack neutral shadow depth",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.D32_FLOAT,
                1,
                1,
                1,
                1
        );
        this.neutralShadowDepthView = device.createTextureView(this.neutralShadowDepth);
        this.sampler = device.createSampler(
                AddressMode.REPEAT,
                AddressMode.REPEAT,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                1,
                OptionalDouble.empty()
        );
        this.comparisonSampler = device.createComparisonSampler();
        commandEncoder.clearColorTexture(this.neutralColor, new Vector4f(1.0F));
        commandEncoder.clearDepthTexture(this.neutralDepth, 1.0);
        commandEncoder.clearDepthTexture(this.neutralShadowDepth, 0.0);
        try {
            loadCustomTextures(device, commandEncoder, customTextures);
            if (noiseSource.length != 0) {
                try (NativeImage image = NativeImage.read(noiseSource)) {
                    this.noiseTexture = device.createTexture(
                            "shader pack noisetex",
                            GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                            GpuFormat.RGBA8_UNORM,
                            image.getWidth(),
                            image.getHeight(),
                            1,
                            1
                    );
                    this.noiseView = device.createTextureView(this.noiseTexture);
                    commandEncoder.writeToTexture(
                            this.noiseTexture,
                            image.getPixelBytes(),
                            0,
                            0,
                            0,
                            0,
                            image.getWidth(),
                            image.getHeight()
                    );
                }
            }
        } catch (IOException | RuntimeException exception) {
            close();
            throw exception;
        }
    }

    @Nullable GpuTextureView forSampler(
            final ShaderPackProgramLoader.PreparedProgram program,
            final String name
    ) {
        CustomBinding custom = customBinding(program, name);
        if (custom != null) {
            return custom.view();
        }
        if (name.equals("noisetex")) {
            return this.noiseView;
        }
        if (name.startsWith("shadowtex") || name.equals("watershadow")) {
            return this.shadowDepthView;
        }
        if (name.equals("shadowcolor") || name.startsWith("shadowcolor")) {
            return this.shadowColorView;
        }
        return null;
    }

    GpuTextureView neutralColorView() {
        return this.neutralColorView;
    }

    GpuTextureView neutralDepthView() {
        return this.neutralDepthView;
    }

    GpuTextureView neutralShadowDepthView() {
        return this.neutralShadowDepthView;
    }

    GpuSampler samplerFor(
            final ShaderPackProgramLoader.PreparedProgram program,
            final LegacyFullscreenTransformer.SamplerField samplerField
    ) {
        CustomBinding custom = customBinding(program, samplerField.name());
        if (custom != null) {
            return custom.sampler();
        }
        return samplerField.type().contains("Shadow") ? this.comparisonSampler : this.sampler;
    }

    void setShadowViews(
            final @Nullable GpuTextureView depthView,
            final @Nullable GpuTextureView colorView
    ) {
        this.shadowDepthView = depthView;
        this.shadowColorView = colorView;
    }

    void bind(
            final MetalRenderPass pass,
            final ShaderPackProgramLoader.PreparedProgram program,
            final LegacyFullscreenTransformer.SamplerField samplerField
    ) {
        if (samplerField.name().equals(LegacyFullscreenTransformer.LEGACY_TEXTURE_SAMPLER)) {
            return;
        }
        boolean comparison = samplerField.type().contains("Shadow");
        GpuTextureView customTexture = forSampler(program, samplerField.name());
        pass.bindTexture(
                samplerField.name(),
                customTexture != null ? customTexture : comparison ? this.neutralShadowDepthView : this.neutralColorView,
                customTexture != null ? samplerFor(program, samplerField)
                        : comparison ? this.comparisonSampler : this.sampler
        );
    }

    private void loadCustomTextures(
            final MetalDevice device,
            final MetalCommandEncoder commandEncoder,
            final List<ShaderPackCustomTexture> definitions
    ) throws IOException {
        for (ShaderPackCustomTexture definition : definitions) {
            UploadedTexture uploaded = this.customImages.get(definition.path());
            if (uploaded == null) {
                try (NativeImage image = NativeImage.read(definition.source())) {
                    GpuTexture texture = null;
                    GpuTextureView view = null;
                    try {
                        texture = device.createTexture(
                                "shader pack custom texture " + definition.path(),
                                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                                GpuFormat.RGBA8_UNORM,
                                image.getWidth(),
                                image.getHeight(),
                                1,
                                1
                        );
                        view = device.createTextureView(texture);
                        commandEncoder.writeToTexture(
                                texture,
                                image.getPixelBytes(),
                                0,
                                0,
                                0,
                                0,
                                image.getWidth(),
                                image.getHeight()
                        );
                        uploaded = new UploadedTexture(texture, view);
                        this.customImages.put(definition.path(), uploaded);
                    } catch (RuntimeException exception) {
                        if (view != null) view.close();
                        if (texture != null) texture.close();
                        throw exception;
                    }
                }
            }
            SamplerSettings settings = new SamplerSettings(definition.blur(), definition.clamp());
            GpuSampler customSampler = this.customSamplers.computeIfAbsent(settings, key -> device.createSampler(
                    key.clamp() ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT,
                    key.clamp() ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT,
                    key.blur() ? FilterMode.LINEAR : FilterMode.NEAREST,
                    key.blur() ? FilterMode.LINEAR : FilterMode.NEAREST,
                    1,
                    OptionalDouble.of(0.0)
            ));
            this.customBindings.put(
                    new CustomKey(definition.stage(), definition.sampler()),
                    new CustomBinding(uploaded.view(), customSampler)
            );
        }
    }

    private @Nullable CustomBinding customBinding(
            final ShaderPackProgramLoader.PreparedProgram program,
            final String samplerName
    ) {
        String stage = ShaderPackCustomTexture.stageName(program.program().type());
        return this.customBindings.get(new CustomKey(stage, samplerName));
    }

    @Override
    public void close() {
        if (this.noiseView != null) {
            this.noiseView.close();
        }
        if (this.noiseTexture != null) {
            this.noiseTexture.close();
        }
        this.customImages.values().forEach(UploadedTexture::close);
        this.customSamplers.values().forEach(GpuSampler::close);
        this.sampler.close();
        this.comparisonSampler.close();
        this.neutralColorView.close();
        this.neutralColor.close();
        this.neutralDepthView.close();
        this.neutralDepth.close();
        this.neutralShadowDepthView.close();
        this.neutralShadowDepth.close();
    }

    private record CustomKey(String stage, String sampler) {
    }

    private record CustomBinding(GpuTextureView view, GpuSampler sampler) {
    }

    private record SamplerSettings(boolean blur, boolean clamp) {
    }

    private record UploadedTexture(GpuTexture texture, GpuTextureView view) implements AutoCloseable {
        @Override
        public void close() {
            this.view.close();
            this.texture.close();
        }
    }
}
