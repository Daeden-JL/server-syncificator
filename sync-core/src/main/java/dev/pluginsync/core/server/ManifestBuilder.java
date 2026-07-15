package dev.pluginsync.core.server;

import dev.pluginsync.core.config.ModSource;
import dev.pluginsync.core.config.ServerConfig;
import dev.pluginsync.core.config.ServerModConfigEntry;
import dev.pluginsync.core.model.ModEntry;
import dev.pluginsync.core.model.Side;
import dev.pluginsync.core.model.SyncManifest;
import dev.pluginsync.core.scan.ModrinthClient;
import dev.pluginsync.core.scan.ModsFolderScanner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Builds the {@link SyncManifest} the server hands out to clients, by combining what's actually
 * on disk in the mods folder with the admin's {@code pluginsync-server.json} config.
 *
 * <p>The file on disk is always the hash source of truth. If a mod is configured with an external
 * Modrinth source, that source is only used to add a (faster/offloaded) download URL - if
 * resolution fails, or the resolved hash doesn't match what's actually on disk, the entry falls
 * back to direct-from-server only, rather than failing the whole manifest.
 */
public final class ManifestBuilder {

    private final Path modsDir;
    private final ServerConfig config;
    private final ModrinthClient modrinthClient;
    private final String directBaseUrl;
    private final BiConsumer<String, Throwable> warningLogger;

    public ManifestBuilder(Path modsDir, ServerConfig config, String directBaseUrl) {
        this(modsDir, config, directBaseUrl, new ModrinthClient(), (msg, err) -> { });
    }

    public ManifestBuilder(
            Path modsDir,
            ServerConfig config,
            String directBaseUrl,
            ModrinthClient modrinthClient,
            BiConsumer<String, Throwable> warningLogger) {
        this.modsDir = modsDir;
        this.config = config;
        this.directBaseUrl = directBaseUrl.endsWith("/") ? directBaseUrl.substring(0, directBaseUrl.length() - 1) : directBaseUrl;
        this.modrinthClient = modrinthClient;
        this.warningLogger = warningLogger;
    }

    public SyncManifest build() throws IOException {
        var scanned = ModsFolderScanner.scan(modsDir);
        List<ModEntry> entries = new ArrayList<>();

        for (ServerModConfigEntry configEntry : config.mods()) {
            if (configEntry.side() == Side.SERVER_ONLY) {
                continue;
            }
            var file = scanned.get(configEntry.fileName());
            if (file == null) {
                warningLogger.accept(
                        "Configured mod '" + configEntry.fileName() + "' is not present in " + modsDir + " - skipping",
                        null);
                continue;
            }

            List<String> urls = new ArrayList<>();
            String directUrl = directBaseUrl + "/plugin-sync/v1/files/" + urlEncode(file.fileName());

            if (configEntry.source() == ModSource.MODRINTH && configEntry.modrinthVersionId() != null) {
                try {
                    ModrinthClient.ResolvedFile resolved = modrinthClient.resolveVersion(configEntry.modrinthVersionId());
                    if (resolved.sha256().equalsIgnoreCase(file.sha256())) {
                        urls.add(resolved.downloadUrl());
                    } else {
                        warningLogger.accept(
                                "Modrinth version " + configEntry.modrinthVersionId() + " hash does not match local file "
                                        + file.fileName() + "; using direct download only",
                                null);
                    }
                } catch (IOException e) {
                    warningLogger.accept("Failed to resolve Modrinth version for " + file.fileName(), e);
                }
            }

            urls.add(directUrl);

            entries.add(new ModEntry(file.fileName(), file.sha256(), file.size(), urls, configEntry.side()));
        }

        return SyncManifest.of(config.serverName(), config.motd(), entries);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
