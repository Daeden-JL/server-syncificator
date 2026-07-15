package dev.pluginsync.loader.forge;

import dev.pluginsync.core.config.ClientConfig;
import dev.pluginsync.core.json.JsonCodec;
import dev.pluginsync.core.relaunch.RelaunchHelper;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Intercepts the first title screen shown after launch and swaps in {@link SyncProgressScreen} if
 * plugin-sync is configured and enabled. Only ever triggers once per JVM session - subsequent
 * returns to the title screen (e.g. after disconnecting from a server) are left alone.
 */
public final class ClientSyncManager {

    private static final AtomicBoolean HANDLED_THIS_SESSION = new AtomicBoolean(false);

    @SubscribeEvent
    public void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof TitleScreen)) {
            return;
        }
        if (!HANDLED_THIS_SESSION.compareAndSet(false, true)) {
            return;
        }
        if (RelaunchHelper.isPostRelaunch()) {
            // We just relaunched ourselves after a sync - don't sync again immediately.
            return;
        }

        ClientConfig config = loadConfig();
        if (config == null || !config.enabled() || config.syncBaseUrl().isEmpty()) {
            return;
        }

        event.setNewScreen(new SyncProgressScreen(config, event.getNewScreen()));
    }

    private static ClientConfig loadConfig() {
        Path path = FMLPaths.CONFIGDIR.get().resolve("pluginsync-client.json");
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return JsonCodec.readFile(path, ClientConfig.class);
        } catch (IOException e) {
            return null;
        }
    }
}
