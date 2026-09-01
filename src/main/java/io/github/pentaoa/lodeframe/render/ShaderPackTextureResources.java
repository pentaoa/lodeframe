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
    private final @Nullable GpuTexture noiseTexture;
    private final @Nullable GpuTextureView noiseView;
    private @Nullable GpuTextureView shadowDepthView;
    private @Nullable GpuTextureView shadowColorView;

    ShaderPackTextureResources(
            final MetalDevice device,
            final MetalCommandEncoder commandEncoder,
            final byte[] noiseSource
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
        if (noiseSource.length == 0) {
            this.noiseTexture = null;
            this.noiseView = null;
            return;
        }
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

    @Nullable GpuTextureView forSampler(final String name) {
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

    GpuSampler samplerFor(final LegacyFullscreenTransformer.SamplerField samplerField) {
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
            final LegacyFullscreenTransformer.SamplerField samplerField
    ) {
        if (samplerField.name().equals(LegacyFullscreenTransformer.LEGACY_TEXTURE_SAMPLER)) {
            return;
        }
        boolean comparison = samplerField.type().contains("Shadow");
        GpuTextureView customTexture = forSampler(samplerField.name());
        pass.bindTexture(
                samplerField.name(),
                customTexture != null ? customTexture : comparison ? this.neutralShadowDepthView : this.neutralColorView,
                comparison ? this.comparisonSampler : this.sampler
        );
    }

    @Override
    public void close() {
        if (this.noiseView != null) {
            this.noiseView.close();
        }
        if (this.noiseTexture != null) {
            this.noiseTexture.close();
        }
        this.sampler.close();
        this.comparisonSampler.close();
        this.neutralColorView.close();
        this.neutralColor.close();
        this.neutralDepthView.close();
        this.neutralDepth.close();
        this.neutralShadowDepthView.close();
        this.neutralShadowDepth.close();
    }
}
