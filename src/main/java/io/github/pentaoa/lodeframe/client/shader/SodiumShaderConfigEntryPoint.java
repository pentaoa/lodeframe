package io.github.pentaoa.lodeframe.client.shader;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class SodiumShaderConfigEntryPoint implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(final ConfigBuilder builder) {
        builder.registerOwnModOptions()
                .addPage(builder.createExternalPage()
                        .setName(Component.translatable("lodeframe.shaderpacks.page"))
                        .setScreenConsumer(parent -> Minecraft.getInstance().setScreenAndShow(
                                new ShaderPackScreen(parent, LodeframeShaderPacks.getInstance())
                        )));
    }
}
