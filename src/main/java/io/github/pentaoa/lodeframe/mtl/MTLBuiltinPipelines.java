package io.github.pentaoa.lodeframe.mtl;

import io.github.pentaoa.lodeframe.objc.AutoreleasePool;
import io.github.pentaoa.lodeframe.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

@Environment(EnvType.CLIENT)
public final class MTLBuiltinPipelines {
    private static final String PRESENT_MSL = """
            #include <metal_stdlib>
            using namespace metal;
            
            struct PresentVertexOut {
              float4 position [[position]];
              float2 uv;
            };
            
            vertex PresentVertexOut lodeframe_present_vs(uint vertexId [[vertex_id]]) {
              const float2 positions[3] = {
                float2(-1.0,  1.0),
                float2( 3.0,  1.0),
                float2(-1.0, -3.0)
              };
            
              // Y-flip version:
              // old equivalent was uvMin=(0,1), uvMax=(1,0)
              const float2 uvs[3] = {
                float2(0.0,  1.0),
                float2(2.0,  1.0),
                float2(0.0, -1.0)
              };
            
              PresentVertexOut out;
              out.position = float4(positions[vertexId], 0.0, 1.0);
              out.uv = uvs[vertexId];
              return out;
            }
            
            fragment float4 lodeframe_present_fs(
              PresentVertexOut in [[stage_in]],
              texture2d<float> tex [[texture(0)]],
              sampler smp [[sampler(0)]]
            ) {
              return tex.sample(smp, in.uv);
            }
            """;

    private static final String CLEAR_MSL = """
            #include <metal_stdlib>
            using namespace metal;
            
            struct ClearUniforms {
              float z;
              float3 _padding0;
              float4 color;
            };
            
            struct ClearVertexOut {
              float4 position [[position]];
              float4 color;
            };
            
            vertex ClearVertexOut lodeframe_clear_vs(
              uint vertexId [[vertex_id]],
              constant ClearUniforms& u [[buffer(1)]]
            ) {
              const float2 positions[3] = {
                float2(-1.0,  1.0),
                float2( 3.0,  1.0),
                float2(-1.0, -3.0)
              };
            
              ClearVertexOut out;
              out.position = float4(positions[vertexId], u.z, 1.0);
              out.color = u.color;
              return out;
            }
            
            fragment float4 lodeframe_clear_fs(ClearVertexOut in [[stage_in]]) {
              return in.color;
            }
            """;

    private static final String LEGACY_DEPTH_MSL = """
            #include <metal_stdlib>
            using namespace metal;

            struct LegacyDepthVertexOut {
              float4 position [[position]];
              float2 uv;
            };

            vertex LegacyDepthVertexOut lodeframe_legacy_depth_vs(uint vertexId [[vertex_id]]) {
              const float2 positions[3] = {
                float2(-1.0,  1.0),
                float2( 3.0,  1.0),
                float2(-1.0, -3.0)
              };
              const float2 uvs[3] = {
                float2(0.0, 0.0),
                float2(2.0, 0.0),
                float2(0.0, 2.0)
              };

              LegacyDepthVertexOut out;
              out.position = float4(positions[vertexId], 0.0, 1.0);
              out.uv = uvs[vertexId];
              return out;
            }

            fragment float lodeframe_legacy_depth_fs(
              LegacyDepthVertexOut in [[stage_in]],
              depth2d<float> depthTexture [[texture(0)]],
              sampler nearestSampler [[sampler(0)]]
            ) {
              return 1.0 - depthTexture.sample(nearestSampler, in.uv);
            }
            """;

    private static MTLDevice device;
    private static MemorySegment presentPipeline = MemorySegment.NULL;
    private static MemorySegment legacyDepthPipeline = MemorySegment.NULL;
    private static MemorySegment presentLinearSampler = MemorySegment.NULL;
    private static MemorySegment presentNearestSampler = MemorySegment.NULL;
    private static final Map<Long, MemorySegment> clearPipelines = new HashMap<>();
    private static final Map<Long, MemorySegment> depthStencilStates = new HashMap<>();

    private MTLBuiltinPipelines() {
    }

    public static void init(final MTLDevice mtlDevice) {
        device = mtlDevice;
        presentPipeline = buildPipeline(PRESENT_MSL, "lodeframe_present_vs", "lodeframe_present_fs",
                MTLPixelFormat.BGRA8Unorm.value, MTLPixelFormat.Invalid.value, MTLColorWriteMask.All.value);
        legacyDepthPipeline = buildPipeline(
                LEGACY_DEPTH_MSL,
                "lodeframe_legacy_depth_vs",
                "lodeframe_legacy_depth_fs",
                MTLPixelFormat.R32Float.value,
                MTLPixelFormat.Invalid.value,
                MTLColorWriteMask.All.value
        );
        presentLinearSampler = buildPresentSampler(MTLSamplerMinMagFilter.Linear);
        presentNearestSampler = buildPresentSampler(MTLSamplerMinMagFilter.Nearest);
        ensureClearPipeline(MTLPixelFormat.BGRA8Unorm.value, MTLPixelFormat.Depth32Float.value, true);
        ensureClearPipeline(MTLPixelFormat.RGBA8Unorm.value, MTLPixelFormat.Depth32Float.value, true);
        ensureClearPipeline(MTLPixelFormat.BGRA8Unorm.value, MTLPixelFormat.Invalid.value, true);
    }

    public static void close() {
        if (!ObjC.isNil(presentPipeline)) {
            ObjC.release(presentPipeline);
            presentPipeline = MemorySegment.NULL;
        }
        if (!ObjC.isNil(presentLinearSampler)) {
            ObjC.release(presentLinearSampler);
            presentLinearSampler = MemorySegment.NULL;
        }
        if (!ObjC.isNil(legacyDepthPipeline)) {
            ObjC.release(legacyDepthPipeline);
            legacyDepthPipeline = MemorySegment.NULL;
        }
        if (!ObjC.isNil(presentNearestSampler)) {
            ObjC.release(presentNearestSampler);
            presentNearestSampler = MemorySegment.NULL;
        }
        clearPipelines.values().forEach(ObjC::release);
        clearPipelines.clear();
        depthStencilStates.values().forEach(ObjC::release);
        depthStencilStates.clear();
        device = null;
    }

    static void clearDraw(
            final MTLRenderCommandEncoder encoder,
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            @Nullable final Vector4fc clearColor,
            @Nullable final Double clearDepth
    ) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MemorySegment sizeTexture = ObjC.isNil(colorTexture) ? depthTexture : colorTexture;
            if (ObjC.isNil(sizeTexture)) {
                return;
            }

            long colorFormat = ObjC.isNil(colorTexture) ? MTLPixelFormat.Invalid.value : MTLTexture.pixelFormat(colorTexture);
            long depthFormat = ObjC.isNil(depthTexture) ? MTLPixelFormat.Invalid.value : MTLTexture.pixelFormat(depthTexture);
            MemorySegment pipeline = ensureClearPipeline(colorFormat, depthFormat, clearColor != null);
            if (ObjC.isNil(pipeline)) {
                return;
            }

            MemorySegment depthState = depthFormat != MTLPixelFormat.Invalid.value
                    ? ensureDepthStencilState(MTLCompareFunction.Always, clearDepth != null)
                    : MemorySegment.NULL;

            long width = MTLTexture.width(sizeTexture);
            long height = MTLTexture.height(sizeTexture);
            if (width <= 0 || height <= 0) {
                return;
            }

            encodeClearDraw(
                    encoder, pipeline,
                    (long) viewportWidth, (long) viewportHeight,
                    clearColor,
                    0L, 0L, width, height,
                    depthState, clearDepth
            );
        }
    }

    static void clearColorDepthTexturesRegion(
            final MTLCommandBuffer commandBuffer,
            final MemorySegment colorTexture,
            final Vector4fc clearColor,
            final MemorySegment depthTexture,
            final double clearDepth,
            final int x,
            final int y,
            final int width,
            final int height,
            final MTLFence globalFence
    ) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            if (width <= 0 || height <= 0) {
                return;
            }

            long textureWidth = Math.min(MTLTexture.width(colorTexture), MTLTexture.width(depthTexture));
            long textureHeight = Math.min(MTLTexture.height(colorTexture), MTLTexture.height(depthTexture));
            long clampedX = Math.max(x, 0);
            long clampedY = Math.max(y, 0);
            long clampedMaxX = Math.min((long) x + width, textureWidth);
            long clampedMaxY = Math.min((long) y + height, textureHeight);
            if (clampedX >= clampedMaxX || clampedY >= clampedMaxY) {
                return;
            }
            boolean fullRegion = clampedX == 0 && clampedY == 0 && clampedMaxX == textureWidth && clampedMaxY == textureHeight;

            MTLRenderCommandEncoder encoder;
            try (MTLRenderPassDescriptor renderPass = new MTLRenderPassDescriptor()) {
                renderPass.colorAttachment(
                        0,
                        colorTexture,
                        fullRegion ? MTLRenderPassDescriptor.LOAD_ACTION_CLEAR : MTLRenderPassDescriptor.LOAD_ACTION_LOAD,
                        MTLRenderPassDescriptor.STORE_ACTION_STORE,
                        clearColor
                );
                renderPass.depthAttachment(
                        depthTexture,
                        fullRegion ? MTLRenderPassDescriptor.LOAD_ACTION_CLEAR : MTLRenderPassDescriptor.LOAD_ACTION_LOAD,
                        MTLRenderPassDescriptor.STORE_ACTION_STORE,
                        clearDepth
                );
                if (MTLPixelFormat.hasStencil(MTLTexture.pixelFormat(depthTexture))) {
                    renderPass.stencilAttachment(
                            depthTexture,
                            MTLRenderPassDescriptor.LOAD_ACTION_DONT_CARE,
                            MTLRenderPassDescriptor.STORE_ACTION_DONT_CARE
                    );
                }
                encoder = commandBuffer.makeRenderCommandEncoder(renderPass);
            }

            if (globalFence != null) {
                encoder.waitForFence(globalFence, MTLRenderStages.Fragment);
            }

            if (!fullRegion) {
                MemorySegment pipeline = ensureClearPipeline(MTLTexture.pixelFormat(colorTexture), MTLTexture.pixelFormat(depthTexture), true);
                MemorySegment depthState = ensureDepthStencilState(MTLCompareFunction.Always, true);
                if (ObjC.isNil(pipeline) || ObjC.isNil(depthState)) {
                    encoder.endEncoding();
                    return;
                }
                encodeClearDraw(
                        encoder, pipeline,
                        textureWidth, textureHeight,
                        clearColor,
                        clampedX, clampedY, clampedMaxX - clampedX, clampedMaxY - clampedY,
                        depthState, clearDepth
                );
            }

            if (globalFence != null) {
                encoder.updateFence(globalFence, MTLRenderStages.Fragment);
            }

            encoder.endEncoding();
        }
    }

    static void encodePresentTextureToDrawable(
            final MTLCommandBuffer commandBuffer,
            final CAMetalLayer layer,
            final MemorySegment sourceTexture,
            final MTLFence globalFence
    ) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            CAMetalDrawable drawable = layer.nextDrawable();
            if (drawable == null) {
                return;
            }
            MemorySegment drawableTexture = drawable.texture();

            MTLRenderCommandEncoder encoder;
            try (MTLRenderPassDescriptor renderPass = new MTLRenderPassDescriptor()) {
                renderPass.colorAttachment(
                        0,
                        drawableTexture,
                        MTLRenderPassDescriptor.LOAD_ACTION_DONT_CARE,
                        MTLRenderPassDescriptor.STORE_ACTION_STORE,
                        null
                );
                encoder = commandBuffer.makeRenderCommandEncoder(renderPass);
            }

            if (globalFence != null) {
                encoder.waitForFence(globalFence, MTLRenderStages.Fragment);
            }

            long drawableWidth = MTLTexture.width(drawableTexture);
            long drawableHeight = MTLTexture.height(drawableTexture);
            encoder.setViewport(0.0, 0.0, drawableWidth, drawableHeight, 0.0, 1.0);
            encoder.setRenderPipelineState(presentPipeline);
            encoder.setFragmentTexture(sourceTexture, 0L);

            boolean requiresScaling = MTLTexture.width(sourceTexture) != drawableWidth
                    || MTLTexture.height(sourceTexture) != drawableHeight;
            encoder.setFragmentSamplerState(requiresScaling ? presentLinearSampler : presentNearestSampler, 0L);

            encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);

            if (globalFence != null) {
                encoder.updateFence(globalFence, MTLRenderStages.Fragment);
            }

            encoder.endEncoding();
            commandBuffer.presentDrawable(drawable);
        }
    }

    static void encodeLegacyDepthCopy(
            final MTLCommandBuffer commandBuffer,
            final MemorySegment sourceDepthTexture,
            final MemorySegment destinationColorTexture,
            final MTLFence globalFence
    ) {
        try (AutoreleasePool _ = AutoreleasePool.push();
             MTLRenderPassDescriptor renderPass = new MTLRenderPassDescriptor()) {
            renderPass.colorAttachment(
                    0,
                    destinationColorTexture,
                    MTLRenderPassDescriptor.LOAD_ACTION_DONT_CARE,
                    MTLRenderPassDescriptor.STORE_ACTION_STORE,
                    null
            );
            MTLRenderCommandEncoder encoder = commandBuffer.makeRenderCommandEncoder(renderPass);
            if (globalFence != null) {
                encoder.waitForFence(globalFence, MTLRenderStages.Fragment);
            }

            long width = MTLTexture.width(destinationColorTexture);
            long height = MTLTexture.height(destinationColorTexture);
            encoder.setViewport(0.0, 0.0, width, height, 0.0, 1.0);
            encoder.setRenderPipelineState(legacyDepthPipeline);
            encoder.setFragmentTexture(sourceDepthTexture, 0L);
            encoder.setFragmentSamplerState(presentNearestSampler, 0L);
            encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);

            if (globalFence != null) {
                encoder.updateFence(globalFence, MTLRenderStages.Fragment);
            }
            encoder.endEncoding();
        }
    }

    private static void encodeClearDraw(
            final MTLRenderCommandEncoder encoder,
            final MemorySegment pipeline,
            final long viewportWidth,
            final long viewportHeight,
            @Nullable final Vector4fc clearColor,
            final long scissorX,
            final long scissorY,
            final long scissorWidth,
            final long scissorHeight,
            final MemorySegment depthState,
            @Nullable final Double clearDepth
    ) {
        encoder.setViewport(0.0, 0.0, viewportWidth, viewportHeight, 0.0, 1.0);
        encoder.setScissorRect(scissorX, scissorY, scissorWidth, scissorHeight);
        encoder.setRenderPipelineState(pipeline);
        if (!ObjC.isNil(depthState)) {
            encoder.setDepthStencilState(depthState);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            MemorySegment uniforms = MemorySegment.ofAddress(stack.nmalloc(16, 48)).reinterpret(48);
            float z = ObjC.isNil(depthState) || clearDepth == null ? 0.0f : (float) Math.clamp(clearDepth, 0.0, 1.0);
            uniforms.set(JAVA_FLOAT, 0, z);
            uniforms.set(JAVA_FLOAT, 32, clearColor == null ? 0.0f : clearColor.x());
            uniforms.set(JAVA_FLOAT, 36, clearColor == null ? 0.0f : clearColor.y());
            uniforms.set(JAVA_FLOAT, 40, clearColor == null ? 0.0f : clearColor.z());
            uniforms.set(JAVA_FLOAT, 44, clearColor == null ? 0.0f : clearColor.w());
            encoder.setVertexBytes(uniforms, 48L, 1L);
        }

        encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);
    }

    private static MemorySegment ensureClearPipeline(final long colorFormat, final long depthFormat, final boolean writeColor) {
        long key = (colorFormat << 32) | (depthFormat << 1) | (writeColor ? 1L : 0L);
        MemorySegment cached = clearPipelines.get(key);
        if (cached != null) {
            return cached;
        }
        MemorySegment pipeline = buildPipeline(CLEAR_MSL, "lodeframe_clear_vs", "lodeframe_clear_fs",
                colorFormat, depthFormat, writeColor ? MTLColorWriteMask.All.value : MTLColorWriteMask.None.value);
        if (!ObjC.isNil(pipeline)) {
            clearPipelines.put(key, pipeline);
        }
        return pipeline;
    }

    private static MemorySegment ensureDepthStencilState(final MTLCompareFunction compareOp, final boolean writeDepth) {
        long key = (compareOp.value << 1) | (writeDepth ? 1L : 0L);
        MemorySegment cached = depthStencilStates.get(key);
        if (cached != null) {
            return cached;
        }
        try (MTLDepthStencilDescriptor descriptor = MTLDepthStencilDescriptor.create()) {
            descriptor.depthCompareFunction(compareOp);
            descriptor.depthWriteEnabled(writeDepth);
            MemorySegment state = device.newDepthStencilState(descriptor);
            if (!ObjC.isNil(state)) {
                depthStencilStates.put(key, state);
            }
            return state;
        }
    }

    private static MemorySegment buildPipeline(
            final String mslSource,
            final String vertexEntry,
            final String fragmentEntry,
            final long colorFormat,
            final long depthFormat,
            final long writeMask
    ) {
        MemorySegment vertexFunction = device.newFunction(mslSource, vertexEntry);
        MemorySegment fragmentFunction = device.newFunction(mslSource, fragmentEntry);
        if (ObjC.isNil(vertexFunction) || ObjC.isNil(fragmentFunction)) {
            releaseIfPresent(vertexFunction);
            releaseIfPresent(fragmentFunction);
            return MemorySegment.NULL;
        }
        MemorySegment pipeline;
        try (MTLRenderPipelineDescriptor descriptor = new MTLRenderPipelineDescriptor()) {
            descriptor.setCompiledFunctions(vertexFunction, fragmentFunction);
            descriptor.setColorAttachmentFormat(0, colorFormat);
            descriptor.setDepthStencilFormats(depthFormat, MTLPixelFormat.Invalid.value);
            descriptor.disableBlending(0, writeMask);
            pipeline = device.newRenderPipelineState(descriptor);
        }
        ObjC.release(vertexFunction);
        ObjC.release(fragmentFunction);
        return pipeline;
    }

    private static MemorySegment buildPresentSampler(final MTLSamplerMinMagFilter filter) {
        try (MTLSamplerDescriptor descriptor = MTLSamplerDescriptor.create()) {
            descriptor.minFilter(filter);
            descriptor.magFilter(filter);
            descriptor.mipFilter(MTLSamplerMipFilter.NotMipmapped);
            descriptor.sAddressMode(MTLSamplerAddressMode.ClampToEdge);
            descriptor.tAddressMode(MTLSamplerAddressMode.ClampToEdge);
            return device.newSamplerState(descriptor);
        }
    }

    private static void releaseIfPresent(final MemorySegment object) {
        if (!ObjC.isNil(object)) {
            ObjC.release(object);
        }
    }

}
