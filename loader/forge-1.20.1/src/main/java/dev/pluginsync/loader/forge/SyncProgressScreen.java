package dev.pluginsync.loader.forge;

import dev.pluginsync.core.config.ClientConfig;
import dev.pluginsync.core.json.JsonCodec;
import dev.pluginsync.core.relaunch.PendingOperations;
import dev.pluginsync.core.relaunch.RelaunchHelper;
import dev.pluginsync.core.relaunch.RelaunchOutcome;
import dev.pluginsync.core.serverlist.ServersDatEditor;
import dev.pluginsync.core.sync.SyncEvent;
import dev.pluginsync.core.sync.SyncSession;
import dev.pluginsync.core.sync.SyncStatus;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shows sync progress while a background thread runs {@link SyncSession}. All game/render state
 * is only ever touched from {@link #tick()}/{@link #render}, which run on the client thread;
 * the background worker only ever communicates back via the {@code events} queue and a handful of
 * volatile fields.
 */
public final class SyncProgressScreen extends Screen {

    private static final Logger LOGGER = Logger.getLogger(PluginSyncForge.MOD_ID);
    private static final int LINE_HEIGHT = 10;
    private static final int ERROR_MARGIN = 20;

    private final ClientConfig config;
    private final Screen parent;
    private final ConcurrentLinkedQueue<SyncEvent> events = new ConcurrentLinkedQueue<>();
    private final AtomicReference<String> statusLine = new AtomicReference<>("Connecting...");
    /** Guards against {@link #init()} re-running on resize and starting a duplicate sync. */
    private final AtomicBoolean workerStarted = new AtomicBoolean(false);

    private volatile float progress = 0f;
    private volatile boolean finished = false;
    private volatile String errorMessage = null;
    private volatile boolean relaunchTriggered = false;
    /** Sync worked, but the game couldn't restart itself - distinct from a failure. */
    private volatile boolean restartNeeded = false;

    protected SyncProgressScreen(ClientConfig config, Screen parent) {
        super(Component.literal("Syncing mods"));
        this.config = config;
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Screen.init() runs again on every resize (resize -> repositionElements -> rebuildWidgets
        // -> init), so without this guard a window resize mid-sync starts a *second* SyncSession
        // against the same mods folder. The two race on the managed-state write, and on Windows the
        // loser fails with AccessDeniedException because the file it's replacing is already open.
        if (!workerStarted.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(this::runSync, "daedens-server-syncificator-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void runSync() {
        Path modsDir = FMLPaths.MODSDIR.get();
        Path managedStatePath = FMLPaths.CONFIGDIR.get().resolve("daedens-server-syncificator-managed.json");

        try {
            SyncSession session = new SyncSession(config.syncBaseUrl(), modsDir, managedStatePath, events::add);
            SyncEvent.Complete result = session.run();

            pinServerToList();

            if (result.restartRequired() && config.autoRestart()) {
                relaunchAndExit(session.pendingOperationsPath());
            } else {
                SyncStatus.set(result.restartRequired()
                        ? SyncStatus.State.RESTART_REQUIRED
                        : SyncStatus.State.UP_TO_DATE);
                finished = true;
            }
        } catch (Exception e) {
            // Catches unchecked failures too (not just IOException) - anything escaping here would
            // otherwise kill this daemon thread silently and leave the UI stuck mid-sync forever.
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            errorMessage = message;
            SyncStatus.set(SyncStatus.State.FAILED, message);
            // The screen is the only other place this surfaces, and it can't show a stack trace -
            // without this the cause of a failed sync never reaches the log at all.
            LOGGER.log(Level.SEVERE, "Daeden's Server Syncificator: sync failed", e);
            finished = true;
        }
    }

    private void pinServerToList() {
        if (!config.pinToServerList() || config.serverAddress().isEmpty()) {
            return;
        }
        try {
            Path serversDat = FMLPaths.GAMEDIR.get().resolve("servers.dat");
            String name = config.serverListName().isEmpty() ? config.serverAddress() : config.serverListName();
            ServersDatEditor.upsertPinnedToTop(serversDat, new ServersDatEditor.ServerListEntry(name, config.serverAddress()));
        } catch (IOException ignored) {
            // Non-fatal - the actual mod sync already succeeded, this is just a convenience.
        }
    }

    private void relaunchAndExit(Path pendingOperationsPath) {
        try {
            boolean autoRelaunch;
            if (Files.isRegularFile(pendingOperationsPath)) {
                // Some files couldn't be updated/removed immediately because this JVM already had
                // them locked (on Windows: true of every already-installed mod jar for the life of
                // the session, not just from a race). This launches a detached helper that waits
                // for this process to actually exit before applying them - that part doesn't
                // depend on being able to relaunch Minecraft afterward, which is why it can still
                // run even where relaunching can't.
                PendingOperations pending = JsonCodec.readFile(pendingOperationsPath, PendingOperations.class);
                RelaunchOutcome outcome = RelaunchHelper.relaunchWithPendingOperations(pending);
                autoRelaunch = outcome instanceof RelaunchOutcome.Relaunched;
            } else {
                RelaunchHelper.relaunch();
                autoRelaunch = true;
            }

            if (autoRelaunch) {
                relaunchTriggered = true;
                statusLine.set("Restarting to apply changes...");
                if (minecraft != null) {
                    minecraft.execute(() -> minecraft.stop());
                }
            } else {
                // Not a failure - the sync itself worked and the pending changes are staged with a
                // helper waiting to apply them. Windows never exposes the launch command at all
                // (ProcessHandle.arguments() is empty there), so it can't restart Minecraft
                // automatically, but closing the game normally is enough for the helper to finish.
                restartNeeded = true;
                statusLine.set("Mods updated - restart to apply");
                SyncStatus.set(SyncStatus.State.RESTART_REQUIRED, "automatic restart unavailable");
                LOGGER.log(Level.INFO, "Daeden's Server Syncificator: mods are updated and a helper is "
                        + "waiting to apply them once you close the game, but this platform won't let it "
                        + "restart automatically - restart manually to apply them.");
                finished = true;
            }
        } catch (IOException e) {
            // Reached only when there was nothing pending and the plain relaunch() failed, or the
            // helper process itself couldn't be started - a genuine "can't restart" case distinct
            // from the platform-can't-auto-relaunch case above.
            restartNeeded = true;
            statusLine.set("Mods updated - restart to apply");
            SyncStatus.set(SyncStatus.State.RESTART_REQUIRED, "automatic restart unavailable");
            LOGGER.log(Level.INFO, "Daeden's Server Syncificator: mods are updated, but this platform "
                    + "won't let the game restart itself (" + e.getMessage() + ") - restart manually to apply them.");
            finished = true;
        }
    }

    @Override
    public void tick() {
        SyncEvent event;
        while ((event = events.poll()) != null) {
            applyEvent(event);
        }
        // restartNeeded keeps the screen up: closing straight to the title screen would hide the
        // one instruction the player actually has to act on.
        if (finished && errorMessage == null && !restartNeeded && !relaunchTriggered && minecraft != null) {
            minecraft.setScreen(new TitleScreen());
        }
    }

    private void applyEvent(SyncEvent event) {
        if (event instanceof SyncEvent.Connecting connecting) {
            statusLine.set("Connecting to " + connecting.baseUrl() + "...");
        } else if (event instanceof SyncEvent.FetchingManifest) {
            statusLine.set("Fetching mod list from server...");
        } else if (event instanceof SyncEvent.Diffing) {
            statusLine.set("Comparing with local mods...");
        } else if (event instanceof SyncEvent.Deleting deleting) {
            statusLine.set("Removing " + deleting.fileName() + " (" + deleting.fileIndex() + "/" + deleting.totalFiles() + ")");
            progress = deleting.fileIndex() / (float) Math.max(1, deleting.totalFiles());
        } else if (event instanceof SyncEvent.Downloading downloading) {
            statusLine.set("Downloading " + downloading.fileName() + " (" + downloading.fileIndex() + "/" + downloading.totalFiles() + ")");
            float fileProgress = downloading.totalBytes() > 0
                    ? downloading.bytesDownloaded() / (float) downloading.totalBytes()
                    : 0f;
            progress = (downloading.fileIndex() - 1 + fileProgress) / (float) Math.max(1, downloading.totalFiles());
        } else if (event instanceof SyncEvent.Finalizing) {
            statusLine.set("Finishing up...");
            progress = 1f;
        } else if (event instanceof SyncEvent.Complete complete) {
            statusLine.set(complete.restartRequired() ? "Up to date - restarting..." : "Already up to date!");
        } else if (event instanceof SyncEvent.Failed failed) {
            errorMessage = failed.message();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xC0101010);

        graphics.drawCenteredString(font, "Syncing mods with server...", width / 2, height / 2 - 24, 0xFFFFFF);
        graphics.drawCenteredString(font, statusLine.get(), width / 2, height / 2 - 4, 0xAAAAAA);

        int barWidth = 240;
        int barHeight = 10;
        int x = width / 2 - barWidth / 2;
        int y = height / 2 + 14;
        graphics.fill(x, y, x + barWidth, y + barHeight, 0xFF3A3A3A);
        graphics.fill(x, y, x + Math.round(barWidth * Math.min(1f, Math.max(0f, progress))), y + barHeight, 0xFF57C25B);

        if (errorMessage != null) {
            renderError(graphics);
        } else if (restartNeeded) {
            renderRestartNeeded(graphics);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Amber, not red, and no "failed": the mods are in place, they just aren't loaded yet. */
    private void renderRestartNeeded(GuiGraphics graphics) {
        graphics.drawCenteredString(font, "Your mods are up to date.", width / 2, height / 2 + 34, 0xFFFFAA00);
        graphics.drawCenteredString(font, "Please restart the game to load them.", width / 2, height / 2 + 46, 0xFFFFAA00);
        graphics.drawCenteredString(font, "Press Escape to continue.", width / 2, height - 24, 0xFF888888);
    }

    /**
     * Draws the failure text wrapped to the screen. These messages routinely embed one or two
     * absolute paths (or a whole relaunch command line), which as a single centred line runs off
     * both edges and is unreadable. Truncated on purpose - the full text goes to the log.
     */
    private void renderError(GuiGraphics graphics) {
        int y = height / 2 + 32;
        int footerY = height - 24;
        int maxLines = Math.max(1, (footerY - y) / LINE_HEIGHT - 1);

        List<FormattedCharSequence> lines = font.split(
                Component.literal("Sync failed: " + errorMessage), width - 2 * ERROR_MARGIN);
        boolean truncated = lines.size() > maxLines;
        if (truncated) {
            lines = lines.subList(0, maxLines);
        }
        for (FormattedCharSequence line : lines) {
            graphics.drawCenteredString(font, line, width / 2, y, 0xFFFF5555);
            y += LINE_HEIGHT;
        }
        if (truncated) {
            graphics.drawCenteredString(font, "(full error in the game log)", width / 2, y, 0xFF888888);
        }
        graphics.drawCenteredString(font, "Press Escape to continue without syncing.", width / 2, footerY, 0xFF888888);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Only once the screen has nothing left to do - never mid-sync.
        return errorMessage != null || restartNeeded;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
