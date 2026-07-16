package dev.pluginsync.loader.neoforge;

import dev.pluginsync.core.sync.SyncStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Draws a one-line sync status in the bottom-right of the title screen, above vanilla's copyright
 * notice. Renders nothing unless a sync actually reported a result this session, so an
 * unconfigured client's title screen looks exactly like vanilla's.
 */
public final class SyncStatusOverlay {

    /** Vanilla draws its copyright line at {@code height - 10}; sit one line above it. */
    private static final int BOTTOM_OFFSET = 20;
    private static final int RIGHT_MARGIN = 2;

    // Full ARGB with an explicit alpha. Font only back-fills an opaque alpha when the top bits are
    // zero, and later Minecraft versions dropped that fallback and treat colours as strict ARGB -
    // where a 0x00-alpha colour renders nothing at all. Being explicit is correct under both.
    private static final int COLOR_UP_TO_DATE = 0xFF55FF55;
    private static final int COLOR_RESTART_REQUIRED = 0xFFFFAA00;
    private static final int COLOR_FAILED = 0xFFFF5555;

    @SubscribeEvent
    public void onScreenRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof TitleScreen screen)) {
            return;
        }
        SyncStatus.Snapshot status = SyncStatus.get();
        String label = label(status);
        if (label == null) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        GuiGraphics graphics = event.getGuiGraphics();
        int x = screen.width - font.width(label) - RIGHT_MARGIN;
        int y = screen.height - BOTTOM_OFFSET;
        graphics.drawString(font, label, x, y, color(status.state()));
    }

    /** @return the line to draw, or null if this state shouldn't be shown at all. */
    private static String label(SyncStatus.Snapshot status) {
        return switch (status.state()) {
            case UNKNOWN -> null;
            case UP_TO_DATE -> "Mods synced";
            case RESTART_REQUIRED -> "Mods updated - restart to apply";
            // The reason was already shown in full on the sync screen the user dismissed; this is
            // just the persistent reminder, so keep it to something that fits in the corner.
            case FAILED -> "Mod sync failed";
        };
    }

    /** Listed exhaustively (no {@code default}) so a new state has to pick a colour deliberately. */
    private static int color(SyncStatus.State state) {
        return switch (state) {
            case UNKNOWN, UP_TO_DATE -> COLOR_UP_TO_DATE;
            case RESTART_REQUIRED -> COLOR_RESTART_REQUIRED;
            case FAILED -> COLOR_FAILED;
        };
    }
}
