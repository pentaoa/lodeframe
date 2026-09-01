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

@Environment(EnvType.CLIENT)
record ShaderPackProgramSet(
        List<ShaderPackProgramLoader.PreparedProgram> fullscreenPrograms,
        ShaderPackProgramLoader.PreparedProgram finalProgram,
        Map<Integer, GpuFormat> bufferFormats
) {
    ShaderPackProgramSet {
        fullscreenPrograms = List.copyOf(fullscreenPrograms);
        bufferFormats = Map.copyOf(bufferFormats);
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
        ShaderPackProgramLoader.PreparedProgram finalProgram = null;
        try (ShaderPack pack = ShaderPack.open(source)) {
            for (ShaderProgram program : selected) {
                for (Map.Entry<Integer, String> format : program.directives().bufferFormats().entrySet()) {
                    formats.put(format.getKey(), toGpuFormat(format.getValue()));
                }
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
        }
        if (finalProgram == null) {
            throw new IllegalArgumentException("Shader pack has no final vertex/fragment program in " + dimension);
        }
        return new ShaderPackProgramSet(prepared, finalProgram, formats);
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
