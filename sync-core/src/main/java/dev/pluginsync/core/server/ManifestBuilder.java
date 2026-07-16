package dev.pluginsync.core.server;

import dev.pluginsync.core.config.ModSource;
import dev.pluginsync.core.config.ServerConfig;
import dev.pluginsync.core.config.ServerModConfigEntry;
import dev.pluginsync.core.model.ModEntry;
import dev.pluginsync.core.model.Side;
import dev.pluginsync.core.model.SyncManifest;
import dev.pluginsync.core.scan.ModrinthClient;
import dev.pluginsync.core.scan.ModsFolderScanner;
import dev.pluginsync.core.scan.ModsFolderScanner.ScannedFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Builds the {@link SyncManifest} the server hands out to clients from {@link ServerConfig#mods()},
 * hashing each listed file in the mods folder.
 *
 * <p>The config list is the single source of truth for <em>which</em> mods are advertised, so an
 * admin can read the config and know exactly what clients will be told about.
 * {@code ServerConfigStore#reconcileWithModsFolder} is what keeps that list in step with the folder
 * (adding new jars, dropping departed ones) and persists it before this runs.
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
        Map<String, ScannedFile> scanned = ModsFolderScanner.scan(modsDir);
        List<ModEntry> entries = new ArrayList<>();

        for (ServerModConfigEntry configEntry : config.mods()) {
            if (configEntry.side() == Side.SERVER_ONLY) {
                continue;
            }
            ScannedFile file = scanned.get(configEntry.fileName());
            if (file == null) {
                warningLogger.accept(
                        "Configured mod '" + configEntry.fileName() + "' is not present in " + modsDir + " - skipping",
                        null);
                continue;
            }
            entries.add(toModEntry(file, configEntry));
        }

        return SyncManifest.of(config.serverName(), config.motd(), entries);
    }

    private ModEntry toModEntry(ScannedFile file, ServerModConfigEntry configEntry) {
        List<String> urls = new ArrayList<>();
        String directUrl = directBaseUrl + "/daedens-server-syncificator/v1/files/" + urlEncode(file.fileName());

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

        return new ModEntry(file.fileName(), file.sha256(), file.size(), urls, configEntry.side());
    }

    /**
     * URLEncoder targets form encoding, not URL paths: it turns a space into '+' while correctly
     * escaping a literal '+' to %2B. The replace() therefore only ever rewrites encoded spaces -
     * a '+' in the file name has already become %2B by then and is left alone.
     */
    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
