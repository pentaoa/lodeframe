package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.Lodeframe;
import io.github.pentaoa.lodeframe.client.shader.LodeframeShaderPacks;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackException;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackReport;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import com.mojang.blaze3d.textures.GpuSampler;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Executes the shader pack's final program as the first real pack-backed Metal pass.
 * The complete Iris-compatible graph will feed this pass once gbuffer and composite
 * programs are available.
 */
@Environment(EnvType.CLIENT)
final class ShaderPackPostProcessor implements AutoCloseable {
    private static final int STAGE_SKY = 1;
    private static final int STAGE_SUNSET = 2;
    private static final int STAGE_MOON = 5;
    private static final int STAGE_STARS = 6;
    private static final int STAGE_TERRAIN_SOLID = 8;
    private static final int STAGE_TERRAIN_CUTOUT = 10;
    private static final int STAGE_ENTITIES = 11;
    private static final int STAGE_HAND_SOLID = 16;
    private static final int STAGE_TERRAIN_TRANSLUCENT = 17;
    private static final int STAGE_PARTICLES = 19;
    private static final int STAGE_RAIN_SNOW = 21;
    private static final int STAGE_HAND_TRANSLUCENT = 23;
    private final MetalDevice device;
    private final MetalCommandEncoder commandEncoder;
    private final ShaderPackFrameTracker frameTracker = new ShaderPackFrameTracker();
    private final Map<RenderPipeline, Integer> sodiumTerrainPipelines = new IdentityHashMap<>();

    private long loadedRevision = Long.MIN_VALUE;
    private String desiredDimension = "world0";
    private String loadedDimension = "";
    private boolean failed;
    private boolean announced;
    private boolean renderingHand;
    private boolean renderingShadow;
    private boolean shadowRendered;
    private @Nullable ShaderPackProgramSet programSet;
    private ShaderPackProgramLoader.@Nullable PreparedProgram program;
    private @Nullable ShaderPackRenderGraph renderGraph;
    private @Nullable ShaderPackTerrainRenderer terrainRenderer;
    private @Nullable ShaderPackTerrainRenderer terrainCutoutRenderer;
    private @Nullable ShaderPackTerrainRenderer waterRenderer;
    private @Nullable ShaderPackGeometryRenderer skyBasicRenderer;
    private @Nullable ShaderPackGeometryRenderer skySunsetRenderer;
    private @Nullable ShaderPackGeometryRenderer skyCelestialRenderer;
    private @Nullable ShaderPackGeometryRenderer skyStarsRenderer;
    private @Nullable ShaderPackGeometryRenderer entitiesRenderer;
    private @Nullable ShaderPackGeometryRenderer entitiesGlowingRenderer;
    private @Nullable ShaderPackGeometryRenderer handRenderer;
    private @Nullable ShaderPackGeometryRenderer handWaterRenderer;
    private @Nullable ShaderPackGeometryRenderer texturedRenderer;
    private @Nullable ShaderPackGeometryRenderer weatherRenderer;
    private @Nullable ShaderPackTerrainRenderer shadowRenderer;
    private @Nullable RenderTarget shadowTarget;
    private @Nullable ShaderPackTextureResources textureResources;
    private @Nullable ShaderPackFrameValues frameValues;

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

    void beginWorld(final GpuTextureView colorView, final ShaderPackFrameContext frameContext) {
        selectDimension(frameContext);
        if (!prepareActiveGraph()) {
            return;
        }
        this.renderGraph.beginFrame(colorView);
        this.shadowRendered = false;
        this.frameValues = this.frameTracker.begin(
                colorView.getWidth(0),
                colorView.getHeight(0),
                frameContext,
                this.programSet.shadowDistance(),
                this.programSet.sunPathRotation(),
                this.programSet.shadowMapResolution()
        );
        updateGeometryUniforms(this.frameValues);
    }

    void setRenderingHand(final boolean renderingHand) {
        this.renderingHand = renderingHand;
    }

    RenderPipeline overrideMinecraftGeometryPipeline(final RenderPipeline base) {
        if (this.renderingShadow && this.shadowRenderer != null) {
            return this.shadowRenderer.override(base);
        }
        Integer terrainStage = this.sodiumTerrainPipelines.get(base);
        if (terrainStage != null) {
            if (!prepareActiveGraph()) {
                return base;
            }
            ShaderPackTerrainRenderer renderer = switch (terrainStage) {
                case 10 -> this.terrainCutoutRenderer;
                case 17 -> this.waterRenderer;
                default -> this.terrainRenderer;
            };
            if (renderer == null) {
                return base;
            }
            try {
                return renderer.override(base);
            } catch (RuntimeException exception) {
                failGraph(exception);
                return base;
            }
        }
        boolean sky = isSkyPipeline(base);
        boolean entity = isEntityPipeline(base);
        boolean glowingEntity = base == RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE
                || base == RenderPipelines.EYES;
        boolean textured = isParticlePipeline(base);
        boolean weather = isWeatherPipeline(base);
        if (!sky
                && !entity
                && !glowingEntity
                && !textured
                && !weather
                && !(this.renderingHand && isHandItemPipeline(base))) {
            return base;
        }
        if (!prepareActiveGraph()) {
            return base;
        }
        ShaderPackGeometryRenderer renderer;
        if (sky) {
            renderer = skyRenderer(base);
        } else if (this.renderingHand && isHandItemPipeline(base)) {
            renderer = currentHandRenderer(base);
        } else if (glowingEntity) {
            renderer = this.entitiesGlowingRenderer;
        } else if (textured) {
            renderer = this.texturedRenderer;
        } else if (weather) {
            renderer = this.weatherRenderer;
        } else {
            renderer = this.entitiesRenderer;
        }
        if (renderer == null) {
            return base;
        }
        try {
            return renderer.override(base);
        } catch (RuntimeException exception) {
            failGraph(exception);
            return base;
        }
    }

    RenderPipeline registerSodiumTerrainPipeline(final RenderPipeline base, final int renderStage) {
        this.sodiumTerrainPipelines.put(base, renderStage);
        return base;
    }

    void clearSodiumTerrainPipelines() {
        this.sodiumTerrainPipelines.clear();
    }

    void bindShaderPackResources(final MetalRenderPass pass, final RenderPipeline pipeline) {
        if (this.terrainRenderer != null && this.terrainRenderer.owns(pipeline)) {
            this.terrainRenderer.bindResources(pass);
        } else if (this.terrainCutoutRenderer != null && this.terrainCutoutRenderer.owns(pipeline)) {
            this.terrainCutoutRenderer.bindResources(pass);
        } else if (this.waterRenderer != null && this.waterRenderer.owns(pipeline)) {
            this.waterRenderer.bindResources(pass);
        } else if (this.skyBasicRenderer != null && this.skyBasicRenderer.owns(pipeline)) {
            this.skyBasicRenderer.bindResources(pass);
        } else if (this.skySunsetRenderer != null && this.skySunsetRenderer.owns(pipeline)) {
            this.skySunsetRenderer.bindResources(pass);
        } else if (this.skyCelestialRenderer != null && this.skyCelestialRenderer.owns(pipeline)) {
            this.skyCelestialRenderer.bindResources(pass);
        } else if (this.skyStarsRenderer != null && this.skyStarsRenderer.owns(pipeline)) {
            this.skyStarsRenderer.bindResources(pass);
        } else if (this.entitiesRenderer != null && this.entitiesRenderer.owns(pipeline)) {
            this.entitiesRenderer.bindResources(pass);
        } else if (this.entitiesGlowingRenderer != null && this.entitiesGlowingRenderer.owns(pipeline)) {
            this.entitiesGlowingRenderer.bindResources(pass);
        } else if (this.handRenderer != null && this.handRenderer.owns(pipeline)) {
            this.handRenderer.bindResources(pass);
        } else if (this.handWaterRenderer != null && this.handWaterRenderer.owns(pipeline)) {
            this.handWaterRenderer.bindResources(pass);
        } else if (this.texturedRenderer != null && this.texturedRenderer.owns(pipeline)) {
            this.texturedRenderer.bindResources(pass);
        } else if (this.weatherRenderer != null && this.weatherRenderer.owns(pipeline)) {
            this.weatherRenderer.bindResources(pass);
        } else if (this.shadowRenderer != null && this.shadowRenderer.owns(pipeline)) {
            this.shadowRenderer.bindResources(pass);
        }
    }

    void configureShaderPackAttachments(final MetalRenderPass pass, final RenderPipeline pipeline) {
        ShaderPackTerrainRenderer renderer = terrainRendererFor(pipeline);
        if (renderer != null) {
            configureProgramAttachments(pass, renderer.program());
            return;
        }
        ShaderPackGeometryRenderer geometry = geometryRendererFor(pipeline);
        if (geometry != null) {
            configureProgramAttachments(pass, geometry.program());
        }
    }

    private void configureProgramAttachments(
            final MetalRenderPass pass,
            final ShaderPackProgramLoader.PreparedProgram program
    ) {
        if (this.renderGraph == null) {
            return;
        }
        int highestOutput = program.fragment().fragmentOutputLocations().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        for (int location = 1; location <= highestOutput; location++) {
            if (!program.fragment().fragmentOutputLocations().contains(location)) {
                continue;
            }
            GpuTextureView attachment = this.renderGraph.gbufferColorAttachment(program, location);
            if (attachment != null) {
                pass.setColorAttachment(location, attachment);
            }
        }
    }

    void bindLegacyTextureAliases(
            final MetalRenderPass pass,
            final RenderPipeline pipeline,
            final GpuTextureView textureView,
            final GpuSampler sampler
    ) {
        ShaderPackProgramLoader.PreparedProgram owner = programForPipeline(pipeline);
        if (owner == null) {
            return;
        }
        owner.vertex().samplers().forEach(field -> bindLegacyTextureAlias(pass, field.name(), textureView, sampler));
        owner.fragment().samplers().forEach(field -> bindLegacyTextureAlias(pass, field.name(), textureView, sampler));
    }

    private static void bindLegacyTextureAlias(
            final MetalRenderPass pass,
            final String name,
            final GpuTextureView textureView,
            final GpuSampler sampler
    ) {
        if (name.equals(io.github.pentaoa.lodeframe.shaders.translate.LegacyFullscreenTransformer.LEGACY_TEXTURE_SAMPLER)
                || name.equals("tex")
                || name.equals("gtexture")) {
            pass.bindTextureAlias(name, textureView, sampler);
        }
    }

    private ShaderPackProgramLoader.@Nullable PreparedProgram programForPipeline(final RenderPipeline pipeline) {
        if (this.terrainRenderer != null && this.terrainRenderer.owns(pipeline)) {
            return this.terrainRenderer.program();
        }
        if (this.terrainCutoutRenderer != null && this.terrainCutoutRenderer.owns(pipeline)) {
            return this.terrainCutoutRenderer.program();
        }
        if (this.waterRenderer != null && this.waterRenderer.owns(pipeline)) {
            return this.waterRenderer.program();
        }
        if (this.entitiesRenderer != null && this.entitiesRenderer.owns(pipeline)) {
            return this.entitiesRenderer.program();
        }
        if (this.entitiesGlowingRenderer != null && this.entitiesGlowingRenderer.owns(pipeline)) {
            return this.entitiesGlowingRenderer.program();
        }
        if (this.handRenderer != null && this.handRenderer.owns(pipeline)) {
            return this.handRenderer.program();
        }
        if (this.handWaterRenderer != null && this.handWaterRenderer.owns(pipeline)) {
            return this.handWaterRenderer.program();
        }
        if (this.texturedRenderer != null && this.texturedRenderer.owns(pipeline)) {
            return this.texturedRenderer.program();
        }
        if (this.weatherRenderer != null && this.weatherRenderer.owns(pipeline)) {
            return this.weatherRenderer.program();
        }
        if (this.shadowRenderer != null && this.shadowRenderer.owns(pipeline)) {
            return this.shadowRenderer.program();
        }
        return null;
    }

    @Nullable RenderTarget activeShadowTarget() {
        return this.renderingShadow ? this.shadowTarget : null;
    }

    void renderShadows(
            final SodiumWorldRenderer worldRenderer,
            final ChunkSectionLayerGroup group,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final GpuSampler blockSampler
    ) {
        if (group != ChunkSectionLayerGroup.OPAQUE
                || this.shadowRendered
                || !prepareActiveGraph()
                || this.shadowRenderer == null
                || this.shadowTarget == null
                || this.frameValues == null) {
            return;
        }
        this.shadowRendered = true;
        this.commandEncoder.clearColorTexture(this.shadowTarget.getColorTexture(), new Vector4f(1.0F));
        this.commandEncoder.clearDepthTexture(this.shadowTarget.getDepthTexture(), 0.0);
        ShaderPackShadowMatrices shadow = this.frameValues.shadowMatrices();
        ChunkRenderMatrices matrices = new ChunkRenderMatrices(
                new Matrix4f().set(shadow.renderProjection()),
                new Matrix4f().set(shadow.modelView())
        );
        this.renderingShadow = true;
        try {
            worldRenderer.renderLayer(
                    matrices,
                    DefaultTerrainRenderPasses.SOLID,
                    cameraX,
                    cameraY,
                    cameraZ,
                    FogParameters.NONE,
                    blockSampler
            );
            worldRenderer.renderLayer(
                    matrices,
                    DefaultTerrainRenderPasses.CUTOUT,
                    cameraX,
                    cameraY,
                    cameraZ,
                    FogParameters.NONE,
                    blockSampler
            );
        } finally {
            this.renderingShadow = false;
        }
    }

    private @Nullable ShaderPackGeometryRenderer geometryRendererFor(final RenderPipeline pipeline) {
        if (this.skyBasicRenderer != null && this.skyBasicRenderer.owns(pipeline)) {
            return this.skyBasicRenderer;
        }
        if (this.skySunsetRenderer != null && this.skySunsetRenderer.owns(pipeline)) {
            return this.skySunsetRenderer;
        }
        if (this.skyCelestialRenderer != null && this.skyCelestialRenderer.owns(pipeline)) {
            return this.skyCelestialRenderer;
        }
        if (this.skyStarsRenderer != null && this.skyStarsRenderer.owns(pipeline)) {
            return this.skyStarsRenderer;
        }
        if (this.entitiesRenderer != null && this.entitiesRenderer.owns(pipeline)) {
            return this.entitiesRenderer;
        }
        if (this.entitiesGlowingRenderer != null && this.entitiesGlowingRenderer.owns(pipeline)) {
            return this.entitiesGlowingRenderer;
        }
        if (this.handRenderer != null && this.handRenderer.owns(pipeline)) {
            return this.handRenderer;
        }
        if (this.handWaterRenderer != null && this.handWaterRenderer.owns(pipeline)) {
            return this.handWaterRenderer;
        }
        if (this.texturedRenderer != null && this.texturedRenderer.owns(pipeline)) {
            return this.texturedRenderer;
        }
        if (this.weatherRenderer != null && this.weatherRenderer.owns(pipeline)) {
            return this.weatherRenderer;
        }
        return null;
    }

    private @Nullable ShaderPackGeometryRenderer currentHandRenderer(final RenderPipeline pipeline) {
        if (isHandTranslucentPipeline(pipeline) && this.handWaterRenderer != null) {
            return this.handWaterRenderer;
        }
        return this.handRenderer;
    }

    private @Nullable ShaderPackGeometryRenderer skyRenderer(final RenderPipeline pipeline) {
        if (pipeline == RenderPipelines.SUNRISE_SUNSET) {
            return this.skySunsetRenderer;
        }
        if (pipeline == RenderPipelines.CELESTIAL) {
            return this.skyCelestialRenderer;
        }
        if (pipeline == RenderPipelines.STARS) {
            return this.skyStarsRenderer;
        }
        return this.skyBasicRenderer;
    }

    private static boolean isSkyPipeline(final RenderPipeline pipeline) {
        return pipeline == RenderPipelines.SKY
                || pipeline == RenderPipelines.END_SKY
                || pipeline == RenderPipelines.SUNRISE_SUNSET
                || pipeline == RenderPipelines.CELESTIAL
                || pipeline == RenderPipelines.STARS;
    }

    private static boolean isEntityPipeline(final RenderPipeline pipeline) {
        return pipeline == RenderPipelines.ARMOR_CUTOUT_NO_CULL
                || pipeline == RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL
                || pipeline == RenderPipelines.ARMOR_TRANSLUCENT
                || pipeline == RenderPipelines.ENTITY_SOLID
                || pipeline == RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD
                || pipeline == RenderPipelines.ENTITY_CUTOUT_CULL
                || pipeline == RenderPipelines.ENTITY_CUTOUT
                || pipeline == RenderPipelines.ENTITY_CUTOUT_Z_OFFSET
                || pipeline == RenderPipelines.ENTITY_CUTOUT_DISSOLVE
                || pipeline == RenderPipelines.ENTITY_TRANSLUCENT
                || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_CULL;
    }

    private static boolean isHandItemPipeline(final RenderPipeline pipeline) {
        return pipeline == RenderPipelines.ITEM_CUTOUT
                || pipeline == RenderPipelines.ITEM_TRANSLUCENT
                || isEntityPipeline(pipeline);
    }

    private static boolean isHandTranslucentPipeline(final RenderPipeline pipeline) {
        return pipeline == RenderPipelines.ITEM_TRANSLUCENT
                || pipeline == RenderPipelines.ENTITY_TRANSLUCENT
                || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_CULL;
    }

    private static boolean isParticlePipeline(final RenderPipeline pipeline) {
        return pipeline == RenderPipelines.OPAQUE_PARTICLE
                || pipeline == RenderPipelines.TRANSLUCENT_PARTICLE;
    }

    private static boolean isWeatherPipeline(final RenderPipeline pipeline) {
        return pipeline == RenderPipelines.WEATHER_DEPTH_WRITE
                || pipeline == RenderPipelines.WEATHER_NO_DEPTH_WRITE;
    }

    private void updateGeometryUniforms(final ShaderPackFrameValues values) {
        update(this.terrainRenderer, values, STAGE_TERRAIN_SOLID);
        update(this.terrainCutoutRenderer, values, STAGE_TERRAIN_CUTOUT);
        update(this.waterRenderer, values, STAGE_TERRAIN_TRANSLUCENT);
        update(this.skyBasicRenderer, values, STAGE_SKY);
        update(this.skySunsetRenderer, values, STAGE_SUNSET);
        update(this.skyCelestialRenderer, values, STAGE_MOON);
        update(this.skyStarsRenderer, values, STAGE_STARS);
        update(this.entitiesRenderer, values, STAGE_ENTITIES);
        update(this.entitiesGlowingRenderer, values, STAGE_ENTITIES);
        update(this.handRenderer, values, STAGE_HAND_SOLID);
        update(this.handWaterRenderer, values, STAGE_HAND_TRANSLUCENT);
        update(this.texturedRenderer, values, STAGE_PARTICLES);
        update(this.weatherRenderer, values, STAGE_RAIN_SNOW);
        update(this.shadowRenderer, values, STAGE_TERRAIN_SOLID);
    }

    private static void update(
            final @Nullable ShaderPackTerrainRenderer renderer,
            final ShaderPackFrameValues values,
            final int stage
    ) {
        if (renderer != null) {
            renderer.update(new ShaderPackStageFrameValues(values, stage));
        }
    }

    private static void update(
            final @Nullable ShaderPackGeometryRenderer renderer,
            final ShaderPackFrameValues values,
            final int stage
    ) {
        if (renderer != null) {
            renderer.update(new ShaderPackStageFrameValues(values, stage));
        }
    }

    private @Nullable ShaderPackTerrainRenderer terrainRendererFor(final RenderPipeline pipeline) {
        if (this.terrainRenderer != null && this.terrainRenderer.owns(pipeline)) {
            return this.terrainRenderer;
        }
        if (this.waterRenderer != null && this.waterRenderer.owns(pipeline)) {
            return this.waterRenderer;
        }
        if (this.terrainCutoutRenderer != null && this.terrainCutoutRenderer.owns(pipeline)) {
            return this.terrainCutoutRenderer;
        }
        return null;
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
        selectDimension(frameContext);
        if (!prepareActiveGraph()) {
            return inputView;
        }

        try {
            ShaderPackFrameValues values = this.frameValues;
            if (values == null) {
                values = this.frameTracker.begin(
                        inputView.getWidth(0),
                        inputView.getHeight(0),
                        frameContext,
                        this.programSet.shadowDistance(),
                        this.programSet.sunPathRotation(),
                        this.programSet.shadowMapResolution()
                );
                updateGeometryUniforms(values);
            }
            GpuTextureView result = this.renderGraph.process(inputView, values);
            this.frameTracker.commit(frameContext);
            this.frameValues = null;

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
        if (revision != this.loadedRevision || !this.desiredDimension.equals(this.loadedDimension)) {
            reload(shaderPacks, revision);
        }
        if (this.programSet == null || this.failed) {
            return false;
        }
        if (this.renderGraph == null) {
            this.renderGraph = new ShaderPackRenderGraph(
                    this.device,
                    this.commandEncoder,
                    this.programSet,
                    this.textureResources
            );
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
        this.loadedDimension = this.desiredDimension;
        this.frameTracker.reset();
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
            this.programSet = ShaderPackProgramSet.load(
                    source.get(),
                    report.get(),
                    this.desiredDimension,
                    revision
            );
            this.program = this.programSet.finalProgram();
            this.textureResources = new ShaderPackTextureResources(
                    this.device,
                    this.commandEncoder,
                    this.programSet.noiseTexture()
            );
            if (this.programSet.terrainProgram() != null) {
                this.terrainRenderer = new ShaderPackTerrainRenderer(
                        this.device,
                        this.commandEncoder,
                        this.programSet.terrainProgram(),
                        this.textureResources,
                        this.programSet.bufferFormats()
                );
                this.terrainCutoutRenderer = new ShaderPackTerrainRenderer(
                        this.device,
                        this.commandEncoder,
                        this.programSet.terrainProgram(),
                        this.textureResources,
                        this.programSet.bufferFormats()
                );
            }
            if (this.programSet.waterProgram() != null) {
                this.waterRenderer = new ShaderPackTerrainRenderer(
                        this.device,
                        this.commandEncoder,
                        this.programSet.waterProgram(),
                        this.textureResources,
                        this.programSet.bufferFormats()
                );
            }
            if (this.programSet.skyBasicProgram() != null) {
                this.skyBasicRenderer = new ShaderPackGeometryRenderer(
                        this.device,
                        this.commandEncoder,
                        this.programSet.skyBasicProgram(),
                        this.textureResources,
                        this.programSet.bufferFormats(),
                        "shader pack skybasic"
                );
                this.skySunsetRenderer = new ShaderPackGeometryRenderer(
                        this.device,
                        this.commandEncoder,
                        this.programSet.skyBasicProgram(),
                        this.textureResources,
                        this.programSet.bufferFormats(),
                        "shader pack skybasic sunset"
                );
                this.skyCelestialRenderer = new ShaderPackGeometryRenderer(
                        this.device,
                        this.commandEncoder,
                        this.programSet.skyBasicProgram(),
                        this.textureResources,
                        this.programSet.bufferFormats(),
                        "shader pack skybasic celestial"
                );
                this.skyStarsRenderer = new ShaderPackGeometryRenderer(
                        this.device,
                        this.commandEncoder,
                        this.programSet.skyBasicProgram(),
                        this.textureResources,
                        this.programSet.bufferFormats(),
                        "shader pack skybasic stars"
                );
            }
            if (this.programSet.entitiesProgram() != null) {
                this.entitiesRenderer = new ShaderPackGeometryRenderer(
                        this.device,
                        this.commandEncoder,
                        this.programSet.entitiesProgram(),
                        this.textureResources,
                        this.programSet.bufferFormats(),
                        "shader pack entities"
                );
            }
            if (this.programSet.entitiesGlowingProgram() != null) {
                this.entitiesGlowingRenderer = new ShaderPackGeometryRenderer(
                        this.device,
                        this.commandEncoder,
                        this.programSet.entitiesGlowingProgram(),
                        this.textureResources,
                        this.programSet.bufferFormats(),
                        "shader pack glowing entities"
                );
            }
            if (this.programSet.handProgram() != null) {
                this.handRenderer = new ShaderPackGeometryRenderer(
                        this.device,
                        this.commandEncoder,
                        this.programSet.handProgram(),
                        this.textureResources,
                        this.programSet.bufferFormats(),
                        "shader pack hand"
                );
            }
            if (this.programSet.handWaterProgram() != null) {
                this.handWaterRenderer = new ShaderPackGeometryRenderer(
                        this.device,
                        this.commandEncoder,
                        this.programSet.handWaterProgram(),
                        this.textureResources,
                        this.programSet.bufferFormats(),
                        "shader pack hand water"
                );
            }
            if (this.programSet.texturedProgram() != null) {
                this.texturedRenderer = new ShaderPackGeometryRenderer(
                        this.device,
                        this.commandEncoder,
                        this.programSet.texturedProgram(),
                        this.textureResources,
                        this.programSet.bufferFormats(),
                        "shader pack textured geometry"
                );
            }
            if (this.programSet.weatherProgram() != null) {
                this.weatherRenderer = new ShaderPackGeometryRenderer(
                        this.device,
                        this.commandEncoder,
                        this.programSet.weatherProgram(),
                        this.textureResources,
                        this.programSet.bufferFormats(),
                        "shader pack weather"
                );
            }
            if (this.programSet.shadowProgram() != null) {
                this.shadowRenderer = new ShaderPackTerrainRenderer(
                        this.device,
                        this.commandEncoder,
                        this.programSet.shadowProgram(),
                        this.textureResources,
                        this.programSet.bufferFormats()
                );
                this.shadowTarget = new RenderTarget(
                        "Lodeframe shader pack shadow map",
                        true,
                        com.mojang.blaze3d.GpuFormat.RGBA8_UNORM
                ) {
                };
                this.shadowTarget.createBuffers(
                        this.programSet.shadowMapResolution(),
                        this.programSet.shadowMapResolution()
                );
                this.textureResources.setShadowViews(
                        this.shadowTarget.getDepthTextureView(),
                        this.shadowTarget.getColorTextureView()
                );
            }
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
        this.renderingHand = false;
        this.renderingShadow = false;
        this.shadowRendered = false;
        this.frameValues = null;
        if (this.terrainRenderer != null) {
            this.terrainRenderer.close();
            this.terrainRenderer = null;
        }
        if (this.terrainCutoutRenderer != null) {
            this.terrainCutoutRenderer.close();
            this.terrainCutoutRenderer = null;
        }
        if (this.waterRenderer != null) {
            this.waterRenderer.close();
            this.waterRenderer = null;
        }
        if (this.skyBasicRenderer != null) {
            this.skyBasicRenderer.close();
            this.skyBasicRenderer = null;
        }
        if (this.skySunsetRenderer != null) {
            this.skySunsetRenderer.close();
            this.skySunsetRenderer = null;
        }
        if (this.skyCelestialRenderer != null) {
            this.skyCelestialRenderer.close();
            this.skyCelestialRenderer = null;
        }
        if (this.skyStarsRenderer != null) {
            this.skyStarsRenderer.close();
            this.skyStarsRenderer = null;
        }
        if (this.entitiesRenderer != null) {
            this.entitiesRenderer.close();
            this.entitiesRenderer = null;
        }
        if (this.entitiesGlowingRenderer != null) {
            this.entitiesGlowingRenderer.close();
            this.entitiesGlowingRenderer = null;
        }
        if (this.handRenderer != null) {
            this.handRenderer.close();
            this.handRenderer = null;
        }
        if (this.handWaterRenderer != null) {
            this.handWaterRenderer.close();
            this.handWaterRenderer = null;
        }
        if (this.texturedRenderer != null) {
            this.texturedRenderer.close();
            this.texturedRenderer = null;
        }
        if (this.weatherRenderer != null) {
            this.weatherRenderer.close();
            this.weatherRenderer = null;
        }
        if (this.textureResources != null) {
            this.textureResources.setShadowViews(null, null);
        }
        if (this.shadowRenderer != null) {
            this.shadowRenderer.close();
            this.shadowRenderer = null;
        }
        if (this.shadowTarget != null) {
            this.shadowTarget.destroyBuffers();
            this.shadowTarget = null;
        }
        if (this.renderGraph != null) {
            this.renderGraph.close();
            this.renderGraph = null;
        }
        if (this.textureResources != null) {
            this.textureResources.close();
            this.textureResources = null;
        }
    }

    private void selectDimension(final ShaderPackFrameContext context) {
        this.desiredDimension = context.shaderDimension();
    }

    @Override
    public void close() {
        releaseResources();
    }
}
