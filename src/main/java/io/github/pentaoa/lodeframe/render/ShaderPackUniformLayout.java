package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.shaders.translate.LegacyFullscreenTransformer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

final class ShaderPackUniformLayout {
    private final List<Entry> entries;
    private final int size;

    private ShaderPackUniformLayout(final List<Entry> entries, final int size) {
        this.entries = List.copyOf(entries);
        this.size = size;
    }

    static ShaderPackUniformLayout of(final List<LegacyFullscreenTransformer.UniformField> fields) {
        List<Entry> entries = new ArrayList<>(fields.size());
        int offset = 0;
        for (LegacyFullscreenTransformer.UniformField field : fields) {
            TypeLayout type = TypeLayout.of(field.type());
            offset = align(offset, type.alignment());
            entries.add(new Entry(field, offset, type));
            offset += type.size();
        }
        return new ShaderPackUniformLayout(entries, align(offset, 16));
    }

    int size() {
        return this.size;
    }

    void write(final ByteBuffer target, final FrameValues values) {
        target.clear();
        for (Entry entry : this.entries) {
            writeEntry(target, entry, values);
        }
        target.position(0);
        target.limit(this.size);
    }

    private static void writeEntry(final ByteBuffer target, final Entry entry, final FrameValues values) {
        String name = entry.field().name();
        String type = entry.field().type();
        int offset = entry.offset();
        if (type.startsWith("mat")) {
            int columns = type.charAt(3) - '0';
            for (int column = 0; column < columns; column++) {
                for (int row = 0; row < columns; row++) {
                    target.putFloat(
                            offset + column * 16 + row * Float.BYTES,
                            values.matrixComponent(name, columns, column, row)
                    );
                }
            }
            return;
        }
        if (type.equals("int") || type.equals("uint") || type.equals("bool")) {
            target.putInt(offset, values.integer(name));
            return;
        }
        if (type.startsWith("ivec") || type.startsWith("uvec")) {
            int components = type.charAt(type.length() - 1) - '0';
            for (int component = 0; component < components; component++) {
                target.putInt(offset + component * Integer.BYTES, values.integerComponent(name, component));
            }
            return;
        }
        int components = type.equals("float") ? 1 : type.charAt(type.length() - 1) - '0';
        for (int component = 0; component < components; component++) {
            target.putFloat(offset + component * Float.BYTES, values.floatComponent(name, component));
        }
    }

    private static int align(final int value, final int alignment) {
        return (value + alignment - 1) & -alignment;
    }

    interface FrameValues {
        int integer(String name);

        int integerComponent(String name, int component);

        float floatComponent(String name, int component);

        float matrixComponent(String name, int columns, int column, int row);
    }

    private record Entry(
            LegacyFullscreenTransformer.UniformField field,
            int offset,
            TypeLayout type
    ) {
    }

    private record TypeLayout(int alignment, int size) {
        static TypeLayout of(final String type) {
            return switch (type) {
                case "float", "int", "uint", "bool" -> new TypeLayout(4, 4);
                case "vec2", "ivec2", "uvec2" -> new TypeLayout(8, 8);
                case "vec3", "ivec3", "uvec3" -> new TypeLayout(16, 12);
                case "vec4", "ivec4", "uvec4" -> new TypeLayout(16, 16);
                case "mat2" -> new TypeLayout(16, 32);
                case "mat3" -> new TypeLayout(16, 48);
                case "mat4" -> new TypeLayout(16, 64);
                default -> throw new IllegalArgumentException("Unsupported shader-pack uniform type " + type);
            };
        }
    }
}
