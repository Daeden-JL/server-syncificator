package dev.pluginsync.core.config;

import dev.pluginsync.core.json.JsonCodec;
import dev.pluginsync.core.model.Side;
import dev.pluginsync.core.scan.ModsFolderScanner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Reads and writes the server config, and keeps its {@link ServerConfig#mods()} list in step with
 * the jars actually in the mods folder.
 *
 * <p>The point of the reconcile is visibility: the manifest handed to clients is derived from this
 * list, so writing the list back to disk means an admin can open the config and see exactly what is
 * being advertised - and flip anything that shouldn't be to {@code SERVER_ONLY} - instead of having
 * to infer it from the contents of a folder.
 *
 * <p>Only the list is persisted, never the manifest itself: {@code sha256}, {@code size} and the
 * download URLs are derived from the jars on disk and change every time a mod is updated. Freezing
 * them into an editable file would mean a config that lies about the files next to it, and hashes a
 * human can't maintain by hand.
 */
public final class ServerConfigStore {

    private ServerConfigStore() {
    }

    /** Loads the config at {@code path}, or writes a default there and returns that. */
    public static ServerConfig loadOrCreate(Path path, String defaultServerName) throws IOException {
        if (java.nio.file.Files.isRegularFile(path)) {
            return JsonCodec.readFile(path, ServerConfig.class);
        }
        ServerConfig config = ServerConfig.createDefault(defaultServerName, "");
        JsonCodec.writeFile(path, config);
        return config;
    }

    public static void save(Path path, ServerConfig config) throws IOException {
        JsonCodec.writeFile(path, config);
    }

    /**
     * Brings {@code config.mods()} in line with the jars in {@code modsDir}, mutating the config in
     * place.
     *
     * <p>Entries are added for jars that have none (when
     * {@link ServerConfig#autoServeModsFolder()} is on), and dropped when their jar is gone - with
     * one deliberate exception: a {@code SERVER_ONLY} entry is kept even with no file behind it. It
     * is a standing rule about a name rather than a description of the folder, so dropping it would
     * mean putting that jar back later silently starts publishing it to clients.
     *
     * @return true if anything changed and the caller should persist the config
     */
    public static boolean reconcileWithModsFolder(ServerConfig config, Path modsDir) throws IOException {
        List<String> onDisk = ModsFolderScanner.listJarNames(modsDir);
        Set<String> onDiskNames = new HashSet<>(onDisk);
        boolean changed = false;

        Iterator<ServerModConfigEntry> entries = config.mods().iterator();
        while (entries.hasNext()) {
            ServerModConfigEntry entry = entries.next();
            if (!onDiskNames.contains(entry.fileName()) && entry.side() != Side.SERVER_ONLY) {
                entries.remove();
                changed = true;
            }
        }

        if (config.autoServeModsFolder()) {
            Set<String> known = new HashSet<>();
            for (ServerModConfigEntry entry : config.mods()) {
                known.add(entry.fileName());
            }
            for (String fileName : onDisk) {
                if (known.add(fileName)) {
                    config.mods().add(new ServerModConfigEntry(fileName, ModSource.DIRECT, null, Side.BOTH));
                    changed = true;
                }
            }
        }

        return changed;
    }
}
