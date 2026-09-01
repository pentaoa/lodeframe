package io.github.pentaoa.lodeframe.render.sodium;

import io.github.pentaoa.lodeframe.Lodeframe;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPack;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackProperties;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ShaderPackBlockIds {
    private static volatile int[] stateIds = new int[0];
    private static final ThreadLocal<Integer> CURRENT_BLOCK_ID = ThreadLocal.withInitial(() -> 0);

    private ShaderPackBlockIds() {
    }

    public static void load(final ShaderPack pack) throws IOException {
        String source = pack.readOptional("block.properties");
        if (source.isEmpty()) {
            stateIds = new int[0];
            return;
        }
        Map<String, List<String>> properties = ShaderPackProperties.parse(source, 12602);
        int[] result = new int[Block.BLOCK_STATE_REGISTRY.size()];
        int matchedStates = 0;
        for (Map.Entry<String, List<String>> entry : properties.entrySet()) {
            if (!entry.getKey().startsWith("block.")) {
                continue;
            }
            int shaderId;
            try {
                shaderId = Integer.parseInt(entry.getKey().substring("block.".length()));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid block material key " + entry.getKey(), exception);
            }
            for (String token : entry.getValue()) {
                matchedStates += mapToken(result, shaderId, token);
            }
        }
        stateIds = result;
        Lodeframe.LOGGER.info("Mapped {} block states from shader-pack block.properties", matchedStates);
    }

    public static void clear() {
        stateIds = new int[0];
        CURRENT_BLOCK_ID.remove();
    }

    public static int id(final BlockState state) {
        int stateId = Block.getId(state);
        int[] current = stateIds;
        return stateId >= 0 && stateId < current.length ? current[stateId] : 0;
    }

    public static void begin(final BlockState state) {
        CURRENT_BLOCK_ID.set(id(state));
    }

    public static int current() {
        return CURRENT_BLOCK_ID.get();
    }

    public static void end() {
        CURRENT_BLOCK_ID.remove();
    }

    private static int mapToken(final int[] result, final int shaderId, final String token) {
        String[] parts = token.split(":");
        String blockName;
        int propertyStart;
        if (parts.length >= 2 && !parts[1].contains("=")) {
            blockName = parts[0] + ":" + parts[1];
            propertyStart = 2;
        } else {
            blockName = "minecraft:" + parts[0];
            propertyStart = 1;
        }
        Identifier identifier = Identifier.tryParse(blockName);
        if (identifier == null) {
            return 0;
        }
        Block block = BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
        if (block == null) {
            return 0;
        }
        Map<String, String> filters = new LinkedHashMap<>();
        for (int index = propertyStart; index < parts.length; index++) {
            int equals = parts[index].indexOf('=');
            if (equals <= 0) {
                continue;
            }
            filters.put(parts[index].substring(0, equals), parts[index].substring(equals + 1));
        }

        int matched = 0;
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            if (!matches(state, filters)) {
                continue;
            }
            int stateId = Block.getId(state);
            if (stateId >= 0 && stateId < result.length) {
                result[stateId] = shaderId;
                matched++;
            }
        }
        return matched;
    }

    private static boolean matches(final BlockState state, final Map<String, String> filters) {
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(filter.getKey());
            if (property == null || !propertyValue(property, state).equals(filter.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static <T extends Comparable<T>> String propertyValue(
            final Property<T> property,
            final BlockState state
    ) {
        return property.getName(state.getValue(property));
    }
}
