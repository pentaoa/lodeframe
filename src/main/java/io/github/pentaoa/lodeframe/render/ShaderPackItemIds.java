package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.Lodeframe;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPack;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackProperties;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ShaderPackItemIds {
    private static volatile Map<Identifier, Integer> itemIds = Map.of();

    private ShaderPackItemIds() {
    }

    public static void load(final ShaderPack pack) throws IOException {
        String source = pack.readOptional("item.properties");
        if (source.isEmpty()) {
            itemIds = Map.of();
            return;
        }
        Map<String, List<String>> properties = ShaderPackProperties.parse(source, 12602);
        Map<Identifier, Integer> result = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : properties.entrySet()) {
            if (!entry.getKey().startsWith("item.")) {
                continue;
            }
            int shaderId;
            try {
                shaderId = Integer.parseInt(entry.getKey().substring("item.".length()));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid item material key " + entry.getKey(), exception);
            }
            for (String token : entry.getValue()) {
                Identifier identifier = Identifier.tryParse(token.contains(":") ? token : "minecraft:" + token);
                if (identifier != null) {
                    result.put(identifier, shaderId);
                }
            }
        }
        itemIds = Map.copyOf(result);
        Lodeframe.LOGGER.info("Mapped {} items from shader-pack item.properties", result.size());
    }

    public static void clear() {
        itemIds = Map.of();
    }

    public static int id(final ItemStack stack) {
        if (stack.isEmpty()) {
            return -1;
        }
        Identifier identifier = stack.get(DataComponents.ITEM_MODEL);
        if (identifier == null) {
            identifier = BuiltInRegistries.ITEM.getKey(stack.getItem());
        }
        return id(identifier);
    }

    static int id(final Identifier identifier) {
        return itemIds.getOrDefault(identifier, -1);
    }

    public static int lightEmission(final ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return 0;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        BlockItemStateProperties properties = stack.get(DataComponents.BLOCK_STATE);
        if (properties != null) {
            state = properties.apply(state);
        }
        return state.getLightEmission();
    }
}
