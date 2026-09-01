package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.mtl.MTLPixelFormat;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MetalCompiledRenderPipelineTest {
    @Test
    void depthOnlyPipelineLeavesEveryColorAttachmentUnused() {
        MTLPixelFormat[] formats = MetalCompiledRenderPipeline.colorTargetFormats(new ColorTargetState[0]);

        assertEquals(8, formats.length);
        for (MTLPixelFormat format : formats) {
            assertEquals(MTLPixelFormat.Invalid, format);
        }
    }

    @Test
    void unusedColorTargetsKeepTheirAttachmentIndices() {
        ColorTargetState[] targets = {
                colorTarget(GpuFormat.RGBA8_UNORM),
                null,
                colorTarget(GpuFormat.RG16_FLOAT)
        };

        MTLPixelFormat[] formats = MetalCompiledRenderPipeline.colorTargetFormats(targets);

        assertEquals(MTLPixelFormat.RGBA8Unorm, formats[0]);
        assertEquals(MTLPixelFormat.Invalid, formats[1]);
        assertEquals(MTLPixelFormat.RG16Float, formats[2]);
        assertEquals(MTLPixelFormat.Invalid, formats[3]);
    }

    @Test
    void multipleRenderTargetsPreserveEveryFormat() {
        ColorTargetState[] targets = {
                colorTarget(GpuFormat.RGBA8_UNORM),
                colorTarget(GpuFormat.RGBA16_FLOAT),
                colorTarget(GpuFormat.R32_UINT),
                colorTarget(GpuFormat.RG11B10_FLOAT)
        };

        MTLPixelFormat[] formats = MetalCompiledRenderPipeline.colorTargetFormats(targets);

        assertEquals(MTLPixelFormat.RGBA8Unorm, formats[0]);
        assertEquals(MTLPixelFormat.RGBA16Float, formats[1]);
        assertEquals(MTLPixelFormat.R32Uint, formats[2]);
        assertEquals(MTLPixelFormat.RG11B10Float, formats[3]);
    }

    private static ColorTargetState colorTarget(final GpuFormat format) {
        return new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL);
    }
}
