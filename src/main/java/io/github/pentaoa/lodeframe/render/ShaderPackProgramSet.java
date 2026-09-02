package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.shaders.pack.ShaderPack;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackException;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackReport;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderProgram;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderProgramType;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderStage;
import com.mojang.blaze3d.GpuFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
record ShaderPackProgramSet(
        List<ShaderPackProgramLoader.PreparedProgram> fullscreenPrograms,
        ShaderPackProgramLoader.PreparedProgram finalProgram,
        ShaderPackProgramLoader.PreparedProgram terrainProgram,
        ShaderPackProgramLoader.PreparedProgram waterProgram,
        ShaderPackProgramLoader.PreparedProgram skyBasicProgram,
        ShaderPackProgramLoader.PreparedProgram entitiesProgram,
        ShaderPackProgramLoader.PreparedProgram entitiesGlowingProgram,
        ShaderPackProgramLoader.PreparedProgram handProgram,
        ShaderPackProgramLoader.PreparedProgram handWaterProgram,
        ShaderPackProgramLoader.PreparedProgram texturedProgram,
        ShaderPackProgramLoader.PreparedProgram weatherProgram,
        ShaderPackProgramLoader.PreparedProgram shadowProgram,
        int shadowMapResolution,
        float shadowDistance,
        float sunPathRotation,
        byte[] noiseTexture,
        ShaderPackCustomUniforms customUniforms,
        List<ShaderPackCustomTexture> customTextures,
        Map<Integer, GpuFormat> bufferFormats,
        Map<Integer, Boolean> bufferClears,
        Map<Integer, io.github.pentaoa.lodeframe.shaders.pack.ShaderDirectives.ClearColor> bufferClearColors
) {
    private static final Pattern NOISE_TEXTURE = Pattern.compile(
            "(?m)^[ \\t]*texture\\.noise[ \\t]*=[ \\t]*([^#\\s]+)"
    );
    private static final Pattern SHADOW_MAP_RESOLUTION = Pattern.compile(
            "(?m)\\bconst[ \\t]+int[ \\t]+shadowMapResolution[ \\t]*=[ \\t]*(\\d+)[ \\t]*;"
    );
    private static final Pattern SHADOW_DISTANCE = Pattern.compile(
            "(?m)\\bconst[ \\t]+float[ \\t]+shadowDistance[ \\t]*=[ \\t]*([0-9]+(?:\\.[0-9]+)?)[fF]?[ \\t]*;"
    );
    private static final Pattern SUN_PATH_ROTATION = Pattern.compile(
            "(?m)\\bconst[ \\t]+float[ \\t]+sunPathRotation[ \\t]*=[ \\t]*\\(?[ \\t]*"
                    + "(-?[ \\t]*[0-9]+(?:\\.[0-9]+)?)[fF]?[ \\t]*\\)?[ \\t]*;"
    );
    private static final int MC_VERSION = 260200;

    ShaderPackProgramSet {
        fullscreenPrograms = List.copyOf(fullscreenPrograms);
        customTextures = List.copyOf(customTextures);
        bufferFormats = Map.copyOf(bufferFormats);
        bufferClears = Map.copyOf(bufferClears);
        bufferClearColors = Map.copyOf(bufferClearColors);
    }

    static ShaderPackProgramSet load(
            final Path source,
            final ShaderPackReport report,
            final String preferredDimension,
            final long revision
    ) throws IOException, ShaderPackException {
        String dimension = selectDimension(report, preferredDimension);
        List<ShaderProgram> selected = report.programs().stream()
                .filter(program -> program.dimension().equals(dimension))
                .filter(program -> program.type().fullscreen())
                .filter(program -> program.stage(ShaderStage.VERTEX).isPresent())
                .filter(program -> program.stage(ShaderStage.FRAGMENT).isPresent())
                .toList();

        List<ShaderPackProgramLoader.PreparedProgram> prepared = new ArrayList<>(selected.size());
        Map<Integer, GpuFormat> formats = new LinkedHashMap<>();
        Map<Integer, Boolean> clears = new LinkedHashMap<>();
        Map<Integer, io.github.pentaoa.lodeframe.shaders.pack.ShaderDirectives.ClearColor> clearColors = new LinkedHashMap<>();
        ShaderPackProgramLoader.PreparedProgram finalProgram = null;
        ShaderPackProgramLoader.PreparedProgram terrainProgram = null;
        ShaderPackProgramLoader.PreparedProgram waterProgram = null;
        ShaderPackProgramLoader.PreparedProgram skyBasicProgram = null;
        ShaderPackProgramLoader.PreparedProgram entitiesProgram = null;
        ShaderPackProgramLoader.PreparedProgram entitiesGlowingProgram = null;
        ShaderPackProgramLoader.PreparedProgram handProgram = null;
        ShaderPackProgramLoader.PreparedProgram handWaterProgram = null;
        ShaderPackProgramLoader.PreparedProgram texturedProgram = null;
        ShaderPackProgramLoader.PreparedProgram weatherProgram = null;
        ShaderPackProgramLoader.PreparedProgram shadowProgram = null;
        int shadowMapResolution = 1024;
        float shadowDistance = 128.0F;
        float sunPathRotation = 0.0F;
        byte[] noiseTexture = new byte[0];
        ShaderPackCustomUniforms customUniforms = ShaderPackCustomUniforms.empty();
        List<ShaderPackCustomTexture> customTextures = List.of();
        try (ShaderPack pack = ShaderPack.open(source)) {
            for (ShaderProgram program : selected) {
                for (Map.Entry<Integer, String> format : program.directives().bufferFormats().entrySet()) {
                    formats.put(format.getKey(), toGpuFormat(format.getValue()));
                }
                clears.putAll(program.directives().bufferClears());
                clearColors.putAll(program.directives().bufferClearColors());
                ShaderPackProgramLoader.PreparedProgram loaded = ShaderPackProgramLoader.loadFullscreen(
                        pack,
                        program,
                        revision
                );
                prepared.add(loaded);
                if (program.type() == ShaderProgramType.FINAL) {
                    finalProgram = loaded;
                }
            }
            ShaderProgram terrain = report.programs().stream()
                    .filter(program -> program.dimension().equals(dimension))
                    .filter(program -> program.name().equals("gbuffers_terrain"))
                    .filter(program -> program.stage(ShaderStage.VERTEX).isPresent())
                    .filter(program -> program.stage(ShaderStage.FRAGMENT).isPresent())
                    .findFirst()
                    .orElse(null);
            if (terrain != null) {
                terrainProgram = ShaderPackProgramLoader.loadSodiumChunkProgram(pack, terrain, revision);
            }
            ShaderProgram water = report.programs().stream()
                    .filter(program -> program.dimension().equals(dimension))
                    .filter(program -> program.name().equals("gbuffers_water"))
                    .filter(program -> program.stage(ShaderStage.VERTEX).isPresent())
                    .filter(program -> program.stage(ShaderStage.FRAGMENT).isPresent())
                    .findFirst()
                    .orElse(null);
            if (water != null) {
                waterProgram = ShaderPackProgramLoader.loadSodiumChunkProgram(pack, water, revision);
            }
            ShaderProgram skyBasic = findProgram(report, dimension, "gbuffers_skybasic");
            if (skyBasic != null) {
                skyBasicProgram = ShaderPackProgramLoader.loadMinecraftPositionProgram(pack, skyBasic, revision);
            }
            ShaderProgram entities = findProgram(report, dimension, "gbuffers_entities");
            if (entities != null) {
                entitiesProgram = ShaderPackProgramLoader.loadMinecraftEntityProgram(pack, entities, revision);
            }
            ShaderProgram entitiesGlowing = findProgram(report, dimension, "gbuffers_entities_glowing");
            if (entitiesGlowing != null) {
                entitiesGlowingProgram = ShaderPackProgramLoader.loadMinecraftEntityProgram(
                        pack,
                        entitiesGlowing,
                        revision
                );
            }
            ShaderProgram hand = findProgram(report, dimension, "gbuffers_hand");
            if (hand != null) {
                handProgram = ShaderPackProgramLoader.loadMinecraftEntityProgram(pack, hand, revision);
            }
            ShaderProgram handWater = findProgram(report, dimension, "gbuffers_hand_water");
            if (handWater != null) {
                handWaterProgram = ShaderPackProgramLoader.loadMinecraftEntityProgram(pack, handWater, revision);
            }
            ShaderProgram textured = findProgram(report, dimension, "gbuffers_textured");
            if (textured != null) {
                texturedProgram = ShaderPackProgramLoader.loadMinecraftParticleProgram(pack, textured, revision);
            }
            ShaderProgram weather = findProgram(report, dimension, "gbuffers_weather");
            if (weather != null) {
                weatherProgram = ShaderPackProgramLoader.loadMinecraftParticleProgram(pack, weather, revision);
            }
            ShaderProgram shadow = findProgram(report, dimension, "shadow");
            if (shadow != null) {
                shadowProgram = ShaderPackProgramLoader.loadSodiumChunkProgram(pack, shadow, revision);
                String shadowVertex = shadowProgram.vertex().source();
                shadowMapResolution = matchInt(SHADOW_MAP_RESOLUTION, shadowVertex, shadowMapResolution);
                shadowDistance = matchFloat(SHADOW_DISTANCE, shadowVertex, shadowDistance);
                sunPathRotation = matchFloat(SUN_PATH_ROTATION, shadowVertex, sunPathRotation);
            }
            String properties = pack.readOptional("shaders.properties");
            customUniforms = ShaderPackCustomUniforms.parse(properties, MC_VERSION);
            customTextures = ShaderPackCustomTexture.load(pack, properties);
            Matcher noise = NOISE_TEXTURE.matcher(properties);
            if (noise.find()) {
                noiseTexture = pack.readOptionalBytes(noise.group(1));
            }
        }
        return new ShaderPackProgramSet(
                prepared,
                finalProgram,
                terrainProgram,
                waterProgram,
                skyBasicProgram,
                entitiesProgram,
                entitiesGlowingProgram,
                handProgram,
                handWaterProgram,
                texturedProgram,
                weatherProgram,
                shadowProgram,
                shadowMapResolution,
                shadowDistance,
                sunPathRotation,
                noiseTexture,
                customUniforms,
                customTextures,
                formats,
                clears,
                clearColors
        );
    }

    private static int matchInt(final Pattern pattern, final String source, final int fallback) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private static float matchFloat(final Pattern pattern, final String source, final float fallback) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? Float.parseFloat(matcher.group(1).replaceAll("[ \\t]", "")) : fallback;
    }

    private static ShaderProgram findProgram(
            final ShaderPackReport report,
            final String dimension,
            final String name
    ) {
        return report.programs().stream()
                .filter(program -> program.dimension().equals(dimension))
                .filter(program -> program.name().equals(name))
                .filter(program -> program.stage(ShaderStage.VERTEX).isPresent())
                .filter(program -> program.stage(ShaderStage.FRAGMENT).isPresent())
                .findFirst()
                .orElse(null);
    }

    private static String selectDimension(final ShaderPackReport report, final String preferred) {
        if (report.dimensions().contains(preferred)) {
            return preferred;
        }
        if (report.dimensions().contains("default")) {
            return "default";
        }
        throw new IllegalArgumentException("Shader pack has no programs for " + preferred + " or the default dimension");
    }

    static GpuFormat toGpuFormat(final String format) {
        return switch (format) {
            case "RGBA", "RGBA8", "RGB8" -> GpuFormat.RGBA8_UNORM;
            case "RGBA16", "RGB16" -> GpuFormat.RGBA16_UNORM;
            case "RGB10_A2" -> GpuFormat.RGB10A2_UNORM;
            case "RG16" -> GpuFormat.RG16_UNORM;
            case "RG8" -> GpuFormat.RG8_UNORM;
            case "R16" -> GpuFormat.R16_UNORM;
            case "R8" -> GpuFormat.R8_UNORM;
            case "RGBA16_SNORM", "RGB16_SNORM" -> GpuFormat.RGBA16_SNORM;
            case "RGBA8_SNORM", "RGB8_SNORM" -> GpuFormat.RGBA8_SNORM;
            case "RG16_SNORM" -> GpuFormat.RG16_SNORM;
            case "RG8_SNORM" -> GpuFormat.RG8_SNORM;
            case "R16_SNORM" -> GpuFormat.R16_SNORM;
            case "R8_SNORM" -> GpuFormat.R8_SNORM;
            case "RGBA32F", "RGB32F" -> GpuFormat.RGBA32_FLOAT;
            case "RGBA16F", "RGB16F" -> GpuFormat.RGBA16_FLOAT;
            case "R11F_G11F_B10F" -> GpuFormat.RG11B10_FLOAT;
            case "RG32F" -> GpuFormat.RG32_FLOAT;
            case "RG16F" -> GpuFormat.RG16_FLOAT;
            case "R32F" -> GpuFormat.R32_FLOAT;
            case "R16F" -> GpuFormat.R16_FLOAT;
            case "RGBA32UI", "RGB32UI" -> GpuFormat.RGBA32_UINT;
            case "RGBA16UI", "RGB16UI" -> GpuFormat.RGBA16_UINT;
            case "RGBA8UI", "RGB8UI" -> GpuFormat.RGBA8_UINT;
            case "RGB10_A2UI" -> GpuFormat.RGB10A2_UINT;
            case "RG32UI" -> GpuFormat.RG32_UINT;
            case "RG16UI" -> GpuFormat.RG16_UINT;
            case "RG8UI" -> GpuFormat.RG8_UINT;
            case "R32UI" -> GpuFormat.R32_UINT;
            case "R16UI" -> GpuFormat.R16_UINT;
            case "R8UI" -> GpuFormat.R8_UINT;
            case "RGBA32I", "RGB32I" -> GpuFormat.RGBA32_SINT;
            case "RGBA16I", "RGB16I" -> GpuFormat.RGBA16_SINT;
            case "RGBA8I", "RGB8I" -> GpuFormat.RGBA8_SINT;
            case "RG32I" -> GpuFormat.RG32_SINT;
            case "RG16I" -> GpuFormat.RG16_SINT;
            case "RG8I" -> GpuFormat.RG8_SINT;
            case "R32I" -> GpuFormat.R32_SINT;
            case "R16I" -> GpuFormat.R16_SINT;
            case "R8I" -> GpuFormat.R8_SINT;
            default -> throw new IllegalArgumentException("Metal backend does not support shader buffer format " + format);
        };
    }
}
