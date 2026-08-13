package dev.pluginsync.loader.neoforge;

import dev.pluginsync.core.config.ClientConfig;
import dev.pluginsync.core.sync.SyncStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Adds a "Check for Updates" / "Sync Updates" button to the title screen, directly above
 * {@link SyncStatusOverlay}'s status line, so a player can trigger a sync manually without
 * relaunching the game - including when {@link ClientConfig#enabled()} has automatic sync on
 * launch turned off. Hidden entirely when the mod isn't configured with a server to sync against,
 * since there would be nothing for it to do.
 */
public final class TitleScreenSyncButton {

    private static final int BUTTON_WIDTH = 150;
    private static final int BUTTON_HEIGHT = 20;
    private static final int RIGHT_MARGIN = 2;
    /** SyncStatusOverlay's text sits at {@code height - 20}; leave a small gap above it. */
    private static final int BOTTOM_OFFSET = 20 + 4 + BUTTON_HEIGHT;

    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen screen)) {
            return;
        }
        ClientConfig config = ClientSyncManager.loadConfigForEditing();
        if (!config.isConfigured()) {
            return;
        }

        // Screen.init() re-runs every time this screen becomes active (including returning here
        // from the sync screen), so reading SyncStatus fresh each time keeps the label honest
        // without needing to track and update a live widget reference.
        boolean pending = SyncStatus.get().state() == SyncStatus.State.RESTART_REQUIRED;
        Component label = Component.literal(pending ? "Sync Updates" : "Check for Updates");

        int x = screen.width - BUTTON_WIDTH - RIGHT_MARGIN;
        int y = screen.height - BOTTOM_OFFSET;
        event.addListener(Button.builder(label, button -> Minecraft.getInstance().setScreen(new SyncProgressScreen(config, screen)))
                .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }
}
