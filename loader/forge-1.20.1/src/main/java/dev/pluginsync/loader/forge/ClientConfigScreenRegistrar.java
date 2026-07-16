package dev.pluginsync.loader.forge;

import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * Registers the mods-list "Config" screen.
 *
 * <p>This exists as its own class purely to keep client-only types out of {@link PluginSyncForge}.
 * The lambda below compiles to a synthetic method whose descriptor names {@code Screen} and
 * {@code Minecraft}, and the JVM resolves the types in a method's descriptor/body when it
 * <em>verifies</em> the class that declares it - which happens at link time, not when the method
 * runs. Inlining this into the mod entrypoint therefore loads {@code Screen} during
 * {@code constructMods} on a dedicated server and trips Forge's dist check, even though the code
 * path is client-only. Keeping it here means the entrypoint only ever names this class, and this
 * class isn't loaded until the client-only caller actually executes.
 *
 * <p>Note that {@code new ClientSyncManager()}-style references are safe in the entrypoint for the
 * same reason: they keep the client types inside <em>that</em> class's descriptors, not the
 * entrypoint's. It's specifically lambdas over client-typed functional interfaces that leak.
 */
final class ClientConfigScreenRegistrar {

    private ClientConfigScreenRegistrar() {
    }

    static void register() {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new ClientConfigScreen(parent, ClientSyncManager.loadConfigForEditing())));
    }
}
