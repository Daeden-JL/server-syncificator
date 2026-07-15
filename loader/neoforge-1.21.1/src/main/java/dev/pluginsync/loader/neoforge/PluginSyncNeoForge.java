package dev.pluginsync.loader.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Mod entrypoint. Client and dedicated-server setup are wired through the mod event bus (received
 * via constructor injection, the current NeoForge 1.21.x convention) so that client-only classes
 * (Screen, GuiGraphics, ...) are never loaded on a dedicated server: the
 * {@code FMLClientSetupEvent} handler - the only place {@link ClientSyncManager} is referenced -
 * is guaranteed by NeoForge to only fire on the client physical side.
 */
@Mod(PluginSyncNeoForge.MOD_ID)
public final class PluginSyncNeoForge {

    public static final String MOD_ID = "pluginsync";

    public PluginSyncNeoForge(IEventBus modEventBus) {
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onDedicatedServerSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        NeoForge.EVENT_BUS.register(new ClientSyncManager());
    }

    private void onDedicatedServerSetup(FMLDedicatedServerSetupEvent event) {
        ServerLifecycleHandler.start();
    }
}
