package io.github.pentaoa.lodeframe.client.shader;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public final class ShaderPackScreen extends Screen {
    private static final Component TITLE = Component.translatable("lodeframe.shaderpacks.title");
    private static final Component ENABLED = Component.translatable("lodeframe.shaderpacks.enabled");
    private static final Component PACK = Component.translatable("lodeframe.shaderpacks.pack");
    private static final Component NONE = Component.translatable("lodeframe.shaderpacks.none");
    private static final Component OPEN_FOLDER = Component.translatable("lodeframe.shaderpacks.open_folder");
    private static final Component REFRESH = Component.translatable("lodeframe.shaderpacks.refresh");
    private static final Component DONE = Component.translatable("gui.done");

    private final Screen parent;
    private final LodeframeShaderPacks shaderPacks;
    private ShaderPackSelection selected = ShaderPackSelection.NONE;
    private Component status = Component.empty();

    public ShaderPackScreen(final Screen parent, final LodeframeShaderPacks shaderPacks) {
        super(TITLE);
        this.parent = parent;
        this.shaderPacks = shaderPacks;
    }

    @Override
    protected void init() {
        List<ShaderPackSelection> discovered = this.shaderPacks.discover();
        this.selected = this.shaderPacks.selectedFrom(discovered);
        List<ShaderPackSelection> choices = discovered.isEmpty()
                ? List.of(ShaderPackSelection.NONE)
                : discovered;

        int controlWidth = Math.min(310, this.width - 40);
        int left = (this.width - controlWidth) / 2;
        int buttonGap = 4;
        int halfWidth = (controlWidth - buttonGap) / 2;

        CycleButton<Boolean> enabledButton = CycleButton.onOffBuilder(this.shaderPacks.enabled())
                .create(left, 52, controlWidth, 20, ENABLED, (button, value) -> {
                    long previousRevision = this.shaderPacks.revision();
                    boolean saved = this.shaderPacks.setEnabled(value, this.selected);
                    if (this.shaderPacks.revision() != previousRevision) {
                        reloadWorldRenderer();
                    }
                    if (!this.selected.isPresent()) {
                        button.setValue(false);
                    }
                    updateStatus(saved, discovered.isEmpty());
                });
        enabledButton.active = !discovered.isEmpty();
        this.addRenderableWidget(enabledButton);

        CycleButton<ShaderPackSelection> packButton = CycleButton
                .builder(ShaderPackScreen::packName, this.selected)
                .withValues(choices)
                .create(left, 80, controlWidth, 20, PACK, (button, selection) -> {
                    this.selected = selection;
                    long previousRevision = this.shaderPacks.revision();
                    boolean saved = this.shaderPacks.select(selection);
                    if (this.shaderPacks.revision() != previousRevision) {
                        reloadWorldRenderer();
                    }
                    updateStatus(saved, false);
                });
        packButton.active = !discovered.isEmpty();
        this.addRenderableWidget(packButton);

        this.addRenderableWidget(Button.builder(OPEN_FOLDER, button -> openDirectory())
                .bounds(left, this.height - 52, halfWidth, 20)
                .build());
        this.addRenderableWidget(Button.builder(REFRESH, button -> this.rebuildWidgets())
                .bounds(left + halfWidth + buttonGap, this.height - 52, halfWidth, 20)
                .build());
        this.addRenderableWidget(Button.builder(DONE, button -> this.onClose())
                .bounds(left, this.height - 28, controlWidth, 20)
                .build());

        updateStatus(true, discovered.isEmpty());
    }

    @Override
    public void extractRenderState(
            final GuiGraphicsExtractor graphics,
            final int mouseX,
            final int mouseY,
            final float partialTick
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 24, 0xFFFFFFFF);
        graphics.centeredText(this.font, this.status, this.width / 2, 116, 0xFFB0B0B0);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }

    private void openDirectory() {
        try {
            Files.createDirectories(this.shaderPacks.directory());
            Util.getPlatform().openPath(this.shaderPacks.directory());
            this.status = Component.translatable("lodeframe.shaderpacks.status.folder_opened");
        } catch (IOException exception) {
            this.status = Component.translatable("lodeframe.shaderpacks.status.folder_failed");
        }
    }

    private void updateStatus(final boolean saved, final boolean empty) {
        if (!saved) {
            this.status = this.shaderPacks.activationError().isEmpty()
                    ? Component.translatable("lodeframe.shaderpacks.status.save_failed")
                    : Component.translatable(
                            "lodeframe.shaderpacks.status.load_failed",
                            this.shaderPacks.activationError()
                    );
        } else if (empty) {
            this.status = Component.translatable("lodeframe.shaderpacks.status.empty");
        } else if (this.shaderPacks.enabled()) {
            this.status = Component.translatable(
                    "lodeframe.shaderpacks.status.enabled",
                    this.selected.displayName()
            );
        } else {
            this.status = Component.translatable(
                    "lodeframe.shaderpacks.status.disabled",
                    this.selected.displayName()
            );
        }
    }

    private static Component packName(final ShaderPackSelection selection) {
        return selection.isPresent() ? Component.literal(selection.displayName()) : NONE;
    }

    private static void reloadWorldRenderer() {
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
        if (renderer != null) {
            renderer.reload();
        }
    }
}
