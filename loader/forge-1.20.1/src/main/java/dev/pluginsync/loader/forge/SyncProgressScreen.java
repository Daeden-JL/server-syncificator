package dev.pluginsync.loader.forge;

import dev.pluginsync.core.config.ClientConfig;
import dev.pluginsync.core.relaunch.RelaunchHelper;
import dev.pluginsync.core.serverlist.ServersDatEditor;
import dev.pluginsync.core.sync.SyncEvent;
import dev.pluginsync.core.sync.SyncSession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shows sync progress while a background thread runs {@link SyncSession}. All game/render state
 * is only ever touched from {@link #tick()}/{@link #render}, which run on the client thread;
 * the background worker only ever communicates back via the {@code events} queue and a handful of
 * volatile fields.
 */
public final class SyncProgressScreen extends Screen {

    private final ClientConfig config;
    private final Screen parent;
    private final ConcurrentLinkedQueue<SyncEvent> events = new ConcurrentLinkedQueue<>();
    private final AtomicReference<String> statusLine = new AtomicReference<>("Connecting...");

    private volatile float progress = 0f;
    private volatile boolean finished = false;
    private volatile String errorMessage = null;
    private volatile boolean relaunchTriggered = false;

    protected SyncProgressScreen(ClientConfig config, Screen parent) {
        super(Component.literal("Syncing mods"));
        this.config = config;
        this.parent = parent;
    }

    @Override
    protected void init() {
        Thread worker = new Thread(this::runSync, "plugin-sync-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void runSync() {
        Path modsDir = FMLPaths.MODSDIR.get();
        Path managedStatePath = FMLPaths.CONFIGDIR.get().resolve("pluginsync-managed.json");

        try {
            SyncSession session = new SyncSession(config.syncBaseUrl(), modsDir, managedStatePath, events::add);
            SyncEvent.Complete result = session.run();

            pinServerToList();

            if (result.restartRequired() && config.autoRestart()) {
                relaunchAndExit();
            } else {
                finished = true;
            }
        } catch (Exception e) {
            // Catches unchecked failures too (not just IOException) - anything escaping here would
            // otherwise kill this daemon thread silently and leave the UI stuck mid-sync forever.
            errorMessage = e.getMessage() == null ? e.toString() : e.getMessage();
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

    private void relaunchAndExit() {
        try {
            RelaunchHelper.relaunch();
            relaunchTriggered = true;
            statusLine.set("Restarting to apply changes...");
            if (minecraft != null) {
                minecraft.execute(() -> minecraft.stop());
            }
        } catch (IOException e) {
            errorMessage = "Sync finished, but automatic restart failed (" + e.getMessage() + "). Please restart the game manually.";
            finished = true;
        }
    }

    @Override
    public void tick() {
        SyncEvent event;
        while ((event = events.poll()) != null) {
            applyEvent(event);
        }
        if (finished && errorMessage == null && !relaunchTriggered && minecraft != null) {
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
            graphics.drawCenteredString(font, "Sync failed: " + errorMessage, width / 2, height / 2 + 36, 0xFF5555);
            graphics.drawCenteredString(font, "Press Escape to continue without syncing.", width / 2, height / 2 + 52, 0x888888);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return errorMessage != null;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
