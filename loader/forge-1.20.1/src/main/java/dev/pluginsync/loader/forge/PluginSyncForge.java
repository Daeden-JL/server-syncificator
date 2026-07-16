package dev.pluginsync.loader.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Mod entrypoint. Client and dedicated-server setup are wired through the mod event bus so that
 * client-only classes (Screen, GuiGraphics, ...) are never loaded on a dedicated server: the
 * {@code FMLClientSetupEvent} handler - the only place {@link ClientSyncManager} and
 * {@link SyncStatusOverlay} are referenced - is guaranteed by Forge to only fire on the client
 * physical side.
 */
@Mod(PluginSyncForge.MOD_ID)
public final class PluginSyncForge {

    public static final String MOD_ID = "daedens_server_syncificator";

    public PluginSyncForge() {
        var modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onDedicatedServerSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        MinecraftForge.EVENT_BUS.register(new ClientSyncManager());
        MinecraftForge.EVENT_BUS.register(new SyncStatusOverlay());

        // Enables the "Config" button next to this mod in the mods list. Must stay behind a plain
        // method call: a lambda over the screen factory here would put Screen in this class's
        // descriptors and break dedicated servers at link time - see ClientConfigScreenRegistrar.
        ClientConfigScreenRegistrar.register();
    }

    private void onDedicatedServerSetup(FMLDedicatedServerSetupEvent event) {
        ServerLifecycleHandler.start();
    }
}
