package dev.pluginsync.loader.forge;

import dev.pluginsync.core.config.ClientConfig;
import dev.pluginsync.core.config.ClientConfigStore;
import dev.pluginsync.core.relaunch.RelaunchHelper;
import dev.pluginsync.core.sync.SyncStatus;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Intercepts the first title screen shown after launch and swaps in {@link SyncProgressScreen} if
 * the mod is configured and enabled. Only ever triggers once per JVM session - subsequent returns
 * to the title screen (e.g. after disconnecting from a server) are left alone.
 */
public final class ClientSyncManager {

    static final String CONFIG_FILE_NAME = "daedens-server-syncificator-client.json";

    private static final Logger LOGGER = Logger.getLogger(PluginSyncForge.MOD_ID);
    private static final AtomicBoolean HANDLED_THIS_SESSION = new AtomicBoolean(false);

    /** Location of the client config file. Also used by {@link ClientConfigScreen} when saving. */
    static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE_NAME);
    }

    @SubscribeEvent
    public void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof TitleScreen)) {
            return;
        }
        if (!HANDLED_THIS_SESSION.compareAndSet(false, true)) {
            return;
        }
        if (RelaunchHelper.isPostRelaunch()) {
            // We just relaunched ourselves after a sync - don't sync again immediately. The sync
            // that triggered the relaunch succeeded, so this JVM's mods folder is current.
            SyncStatus.set(SyncStatus.State.UP_TO_DATE);
            return;
        }

        ClientConfig config = loadConfig();
        if (config == null || !config.enabled() || !config.isConfigured()) {
            return;
        }

        event.setNewScreen(new SyncProgressScreen(config, event.getNewScreen()));
    }

    /**
     * Config for {@link ClientConfigScreen} to edit. Unlike the sync path, an unreadable file must
     * not stop the screen from opening - fall back to defaults so the player can fix it in-game
     * (saving then overwrites the broken file).
     */
    static ClientConfig loadConfigForEditing() {
        ClientConfig config = loadConfig();
        return config == null ? ClientConfig.createDefault() : config;
    }

    /**
     * Loads the config, creating a default file if this is a fresh install. Returns null only when
     * the file exists but can't be used, in which case syncing is skipped for the session.
     */
    private static ClientConfig loadConfig() {
        Path path = configPath();
        try {
            ClientConfig config = ClientConfigStore.loadOrCreate(path);
            if (!config.isConfigured()) {
                LOGGER.info("Daeden's Server Syncificator: no 'serverHost' set in " + path
                        + " - not syncing. Set one there, or in-game via Mods > Daeden's Server"
                        + " Syncificator > Config, then restart.");
            }
            return config;
        } catch (IOException e) {
            // Deliberately not silent: a typo'd config used to look identical to "not installed".
            LOGGER.log(Level.SEVERE, "Daeden's Server Syncificator: could not read " + path
                    + " - not syncing this session. Fix or delete the file to regenerate it.", e);
            return null;
        }
    }
}
