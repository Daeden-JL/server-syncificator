package dev.pluginsync.loader.forge;

import dev.pluginsync.core.config.ClientConfig;
import dev.pluginsync.core.config.ClientConfigStore;
import dev.pluginsync.core.config.ServerConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;

/**
 * The screen behind the mods-list "Config" button. Edits a scratch copy of {@link ClientConfig} and
 * only writes it to disk on Save, so backing out leaves the saved config untouched.
 *
 * <p>Saving here does not re-run a sync: the sync decision is made once, when the title screen
 * first opens, long before this screen is reachable. Changes therefore take effect on the next
 * launch, which the footer says out loud.
 */
public final class ClientConfigScreen extends Screen {

    private static final int FIELD_WIDTH = 200;
    private static final int FIELD_HEIGHT = 20;
    private static final int LABEL_COLOR = 0xFFA0A0A0;
    private static final int ERROR_COLOR = 0xFFFF5555;
    private static final int FOOTER_COLOR = 0xFF808080;

    private final Screen parent;
    private final ClientConfig working;

    private EditBox hostBox;
    private EditBox syncPortBox;
    private EditBox minecraftPortBox;
    private String errorMessage;

    ClientConfigScreen(Screen parent, ClientConfig current) {
        super(Component.literal("Daeden's Server Syncificator"));
        this.parent = parent;
        this.working = current.copy();
    }

    @Override
    protected void init() {
        // init() re-runs on every resize and rebuilds these widgets from scratch, so seed each
        // field from what's currently typed rather than from `working` - the text boxes are only
        // read back on save, so seeding from `working` would silently discard unsaved edits.
        // (The toggles below need no such care: they write into `working` as they're clicked.)
        String host = hostBox != null ? hostBox.getValue() : working.serverHost();
        String syncPort = syncPortBox != null ? syncPortBox.getValue() : Integer.toString(working.syncPort());
        String minecraftPort = minecraftPortBox != null
                ? minecraftPortBox.getValue()
                : Integer.toString(working.minecraftPort());

        int x = width / 2 - FIELD_WIDTH / 2;
        int y = 40;

        hostBox = new EditBox(font, x, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Server host"));
        hostBox.setMaxLength(253); // max DNS name length
        hostBox.setValue(host);
        addRenderableWidget(hostBox);
        y += 32;

        syncPortBox = portBox(x, y, "Sync port", syncPort);
        addRenderableWidget(syncPortBox);
        y += 32;

        minecraftPortBox = portBox(x, y, "Minecraft port", minecraftPort);
        addRenderableWidget(minecraftPortBox);
        y += 28;

        addRenderableWidget(CycleButton.onOffBuilder(working.enabled())
                .create(x, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Sync on launch"),
                        (button, value) -> working.setEnabled(value)));
        y += 22;

        addRenderableWidget(CycleButton.onOffBuilder(working.autoRestart())
                .create(x, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Auto-restart"),
                        (button, value) -> working.setAutoRestart(value)));
        y += 22;

        addRenderableWidget(CycleButton.onOffBuilder(working.pinToServerList())
                .create(x, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Pin to server list"),
                        (button, value) -> working.setPinToServerList(value)));

        int buttonY = height - 28;
        addRenderableWidget(Button.builder(Component.literal("Save"), button -> save())
                .bounds(width / 2 - 102, buttonY, 100, FIELD_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(width / 2 + 2, buttonY, 100, FIELD_HEIGHT).build());
    }

    private EditBox portBox(int x, int y, String name, String value) {
        EditBox box = new EditBox(font, x, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal(name));
        box.setMaxLength(5);
        // Keep the field numeric as you type; range is still checked on save, since "0" and "99999"
        // are both typeable but neither is a port.
        box.setFilter(text -> text.isEmpty() || text.matches("\\d{1,5}"));
        box.setValue(value);
        return box;
    }

    private void save() {
        Integer syncPort = parsePort(syncPortBox);
        Integer minecraftPort = parsePort(minecraftPortBox);
        if (syncPort == null || minecraftPort == null) {
            errorMessage = "Ports must be a number between 1 and 65535.";
            return;
        }

        working.setServerHost(hostBox.getValue());
        working.setSyncPort(syncPort);
        working.setMinecraftPort(minecraftPort);

        try {
            ClientConfigStore.save(ClientSyncManager.configPath(), working);
            onClose();
        } catch (IOException e) {
            errorMessage = "Could not save config: " + e.getMessage();
        }
    }

    /** @return the port, or null if it isn't a usable one. */
    private static Integer parsePort(EditBox box) {
        try {
            int port = Integer.parseInt(box.getValue().trim());
            return ClientConfig.isValidPort(port) ? port : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFFFF);

        int labelX = width / 2 - FIELD_WIDTH / 2;
        graphics.drawString(font, "Server host", labelX, 30, LABEL_COLOR);
        graphics.drawString(font, "Sync port (default " + ServerConfig.DEFAULT_HTTP_PORT + ")", labelX, 62, LABEL_COLOR);
        graphics.drawString(font, "Minecraft port (default " + ClientConfig.DEFAULT_MINECRAFT_PORT + ")", labelX, 94, LABEL_COLOR);

        if (errorMessage != null) {
            graphics.drawCenteredString(font, errorMessage, width / 2, height - 42, ERROR_COLOR);
        } else {
            graphics.drawCenteredString(font, "Changes apply the next time you launch the game.",
                    width / 2, height - 42, FOOTER_COLOR);
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
