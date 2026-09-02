package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.shaders.pack.ShaderDirectiveParser;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderDirectives;
import com.mojang.blaze3d.shaders.ShaderType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
final class ShaderPackGlslPreprocessor {
    private static final Pattern VERSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[ \\t]+\\d+[^\\r\\n]*");

    private ShaderPackGlslPreprocessor() {
    }

    static String preprocess(final String source, final ShaderType stage) {
        return preprocessSource(source, stage);
    }

    static FragmentSource preprocessFragment(final String source) {
        ShaderDirectiveParser.InstrumentedDirectives instrumented = ShaderDirectiveParser.instrumentRenderTargets(source);
        String preprocessed = preprocessSource(instrumented.source(), ShaderType.FRAGMENT);
        ShaderDirectiveParser.ResolvedDirectives resolved = instrumented.resolve(preprocessed);
        return new FragmentSource(resolved.source(), resolved.renderTargets());
    }

    static Set<String> definedMacros(
            final String source,
            final ShaderType stage,
            final Set<String> candidates
    ) {
        if (candidates.isEmpty()) {
            return Set.of();
        }
        StringBuilder instrumented = new StringBuilder(source);
        candidates.stream().sorted().forEach(candidate -> instrumented
                .append("\n#ifdef ").append(candidate)
                .append("\nconst int lodeframe_option_").append(candidate).append(" = 1;")
                .append("\n#endif\n"));
        String preprocessed = preprocessSource(instrumented.toString(), stage);
        Set<String> defined = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (preprocessed.contains("lodeframe_option_" + candidate)) {
                defined.add(candidate);
            }
        }
        return Set.copyOf(defined);
    }

    private static String preprocessSource(final String source, final ShaderType stage) {
        String compatibleSource = VERSION.matcher(source).replaceFirst("#version 330 core");
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_add_macro_definition(options, "MC_VERSION", "12602");
            Shaderc.shaderc_compile_options_add_macro_definition(options, "MC_OS_MAC", "1");
            Shaderc.shaderc_compile_options_add_macro_definition(options, "MC_GL_RENDERER_APPLE", "1");
            Shaderc.shaderc_compile_options_add_macro_definition(options, "IS_IRIS", "1");
            int shaderKind = stage == ShaderType.VERTEX
                    ? Shaderc.shaderc_vertex_shader
                    : Shaderc.shaderc_fragment_shader;
            ByteBuffer sourceBuffer = MemoryUtil.memUTF8(compatibleSource, false);
            ByteBuffer filenameBuffer = MemoryUtil.memUTF8("shaderpack." + stage.getName());
            ByteBuffer entryPointBuffer = MemoryUtil.memUTF8("main");
            long result;
            try {
                result = Shaderc.shaderc_compile_into_preprocessed_text(
                        compiler,
                        sourceBuffer,
                        shaderKind,
                        filenameBuffer,
                        entryPointBuffer,
                        options
                );
            } finally {
                MemoryUtil.memFree(entryPointBuffer);
                MemoryUtil.memFree(filenameBuffer);
                MemoryUtil.memFree(sourceBuffer);
            }
            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != Shaderc.shaderc_compilation_status_success) {
                    throw new IllegalArgumentException(
                            "Shader pack preprocessing failed: " + Shaderc.shaderc_result_get_error_message(result)
                    );
                }
                int length = Math.toIntExact(Shaderc.shaderc_result_get_length(result));
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result).duplicate();
                bytes.limit(bytes.position() + length);
                return StandardCharsets.UTF_8.decode(bytes).toString();
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    record FragmentSource(String source, Optional<ShaderDirectives.RenderTargets> renderTargets) {
    }
}
