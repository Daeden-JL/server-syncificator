package dev.pluginsync.loader.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Mod entrypoint. Client and dedicated-server setup are wired through the mod event bus so that
 * client-only classes (Screen, GuiGraphics, ...) are never loaded on a dedicated server: the
 * {@code FMLClientSetupEvent} handler - the only place {@link ClientSyncManager} is referenced -
 * is guaranteed by Forge to only fire on the client physical side.
 */
@Mod(PluginSyncForge.MOD_ID)
public final class PluginSyncForge {

    public static final String MOD_ID = "pluginsync";

    public PluginSyncForge() {
        var modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onDedicatedServerSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        MinecraftForge.EVENT_BUS.register(new ClientSyncManager());
    }

    private void onDedicatedServerSetup(FMLDedicatedServerSetupEvent event) {
        ServerLifecycleHandler.start();
    }
}
