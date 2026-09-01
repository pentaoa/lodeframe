package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.mixin.render.GpuDeviceAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.texture.TextureAtlas;
import com.mojang.blaze3d.textures.GpuSampler;
import org.jspecify.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class ShaderPackRenderHooks {
    private ShaderPackRenderHooks() {
    }

    public static void processWorldFrame(
            final GameRenderer gameRenderer,
            final DeltaTracker deltaTracker
    ) {
        GpuDeviceBackend backend = ((GpuDeviceAccessor) (Object) RenderSystem.getDevice()).lodeframe$getBackend();
        if (backend instanceof MetalDevice metalDevice) {
            metalDevice.shaderPackPostProcessor().processWorld(
                    gameRenderer.mainRenderTarget().getColorTextureView(),
                    frameContext(gameRenderer, deltaTracker)
            );
        }
    }

    public static void beginWorldFrame(
            final GameRenderer gameRenderer,
            final DeltaTracker deltaTracker
    ) {
        GpuDeviceBackend backend = ((GpuDeviceAccessor) (Object) RenderSystem.getDevice()).lodeframe$getBackend();
        if (backend instanceof MetalDevice metalDevice) {
            metalDevice.shaderPackPostProcessor().beginWorld(
                    gameRenderer.mainRenderTarget().getColorTextureView(),
                    frameContext(gameRenderer, deltaTracker)
            );
        }
    }

    public static RenderPipeline overrideSodiumTerrainPipeline(
            final RenderPipeline base,
            final TerrainRenderPass pass
    ) {
        GpuDeviceBackend backend = ((GpuDeviceAccessor) (Object) RenderSystem.getDevice()).lodeframe$getBackend();
        if (backend instanceof MetalDevice metalDevice) {
            int stage = pass == DefaultTerrainRenderPasses.TRANSLUCENT
                    ? 17
                    : pass == DefaultTerrainRenderPasses.CUTOUT ? 10 : 8;
            return metalDevice.shaderPackPostProcessor().registerSodiumTerrainPipeline(base, stage);
        }
        return base;
    }

    public static void clearSodiumTerrainPipelines() {
        GpuDeviceBackend backend = ((GpuDeviceAccessor) (Object) RenderSystem.getDevice()).lodeframe$getBackend();
        if (backend instanceof MetalDevice metalDevice) {
            metalDevice.shaderPackPostProcessor().clearSodiumTerrainPipelines();
        }
    }

    public static void captureWorldDepth(final RenderTarget renderTarget) {
        GpuDeviceBackend backend = ((GpuDeviceAccessor) (Object) RenderSystem.getDevice()).lodeframe$getBackend();
        if (backend instanceof MetalDevice metalDevice) {
            metalDevice.shaderPackPostProcessor().captureWorldDepth(renderTarget.getDepthTextureView());
        }
    }

    public static void beginHand() {
        GpuDeviceBackend backend = ((GpuDeviceAccessor) (Object) RenderSystem.getDevice()).lodeframe$getBackend();
        if (backend instanceof MetalDevice metalDevice) {
            metalDevice.shaderPackPostProcessor().setRenderingHand(true);
        }
    }

    public static void endHand() {
        GpuDeviceBackend backend = ((GpuDeviceAccessor) (Object) RenderSystem.getDevice()).lodeframe$getBackend();
        if (backend instanceof MetalDevice metalDevice) {
            metalDevice.shaderPackPostProcessor().setRenderingHand(false);
        }
    }

    public static void renderShadows(
            final SodiumWorldRenderer worldRenderer,
            final ChunkSectionLayerGroup group,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final GpuSampler blockSampler
    ) {
        GpuDeviceBackend backend = ((GpuDeviceAccessor) (Object) RenderSystem.getDevice()).lodeframe$getBackend();
        if (backend instanceof MetalDevice metalDevice) {
            metalDevice.shaderPackPostProcessor().renderShadows(
                    worldRenderer,
                    group,
                    cameraX,
                    cameraY,
                    cameraZ,
                    blockSampler
            );
        }
    }

    public static @Nullable RenderTarget activeShadowTarget() {
        GpuDeviceBackend backend = ((GpuDeviceAccessor) (Object) RenderSystem.getDevice()).lodeframe$getBackend();
        return backend instanceof MetalDevice metalDevice
                ? metalDevice.shaderPackPostProcessor().activeShadowTarget()
                : null;
    }

    private static ShaderPackFrameContext frameContext(
            final GameRenderer gameRenderer,
            final DeltaTracker deltaTracker
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        com.mojang.blaze3d.textures.GpuTexture blockAtlas = minecraft.getAtlasManager()
                .getAtlasOrThrow(TextureAtlas.LOCATION_BLOCKS)
                .getTexture();
        return ShaderPackFrameContext.from(
                gameRenderer.gameRenderState().levelRenderState.cameraRenderState,
                gameRenderer.gameRenderState().levelRenderState,
                minecraft.level,
                deltaTracker.getGameTimeDeltaPartialTick(false),
                blockAtlas.getWidth(0),
                blockAtlas.getHeight(0),
                minecraft.options.gamma().get().floatValue()
        );
    }
}
