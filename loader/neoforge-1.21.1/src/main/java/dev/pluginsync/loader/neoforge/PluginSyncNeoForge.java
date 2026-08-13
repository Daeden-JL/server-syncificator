package dev.pluginsync.loader.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Mod entrypoint. Client and dedicated-server setup are wired through the mod event bus (received
 * via constructor injection, the current NeoForge 1.21.x convention) so that client-only classes
 * (Screen, GuiGraphics, ...) are never loaded on a dedicated server: the
 * {@code FMLClientSetupEvent} handler - the only place {@link ClientSyncManager},
 * {@link SyncStatusOverlay}, {@link TitleScreenSyncButton} and {@link ClientConfigScreen} are
 * referenced - is guaranteed by NeoForge to only fire on the client physical side.
 */
@Mod(PluginSyncNeoForge.MOD_ID)
public final class PluginSyncNeoForge {

    public static final String MOD_ID = "daedens_server_syncificator";

    private final ModContainer modContainer;

    public PluginSyncNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        this.modContainer = modContainer;
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onDedicatedServerSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        NeoForge.EVENT_BUS.register(new ClientSyncManager());
        NeoForge.EVENT_BUS.register(new SyncStatusOverlay());
        NeoForge.EVENT_BUS.register(new TitleScreenSyncButton());

        // Enables the "Config" button next to this mod in the mods list. Must stay behind a plain
        // method call: a lambda over IConfigScreenFactory here would put Screen in this class's
        // descriptors and break dedicated servers at link time - see ClientConfigScreenRegistrar.
        ClientConfigScreenRegistrar.register(modContainer);
    }

    private void onDedicatedServerSetup(FMLDedicatedServerSetupEvent event) {
        ServerLifecycleHandler.start();
    }
}
