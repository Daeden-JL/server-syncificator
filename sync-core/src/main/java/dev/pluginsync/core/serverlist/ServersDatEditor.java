package dev.pluginsync.core.serverlist;

import dev.pluginsync.core.serverlist.NbtTag.NbtByte;
import dev.pluginsync.core.serverlist.NbtTag.NbtCompound;
import dev.pluginsync.core.serverlist.NbtTag.NbtList;
import dev.pluginsync.core.serverlist.NbtTag.NbtString;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the vanilla multiplayer server list ({@code servers.dat} in the game
 * directory) so the configured server can be pinned to the top of it.
 *
 * <p>This never touches any entry other than one matching the given IP: existing servers,
 * unknown/extra NBT fields on any entry (icons, {@code moreInfo}, etc.), and the file's general
 * structure are all preserved untouched. If the file can't be parsed (unexpected/corrupt
 * structure), {@link #upsertPinnedToTop} throws before writing anything - callers should catch
 * and log rather than let a parse failure ever cascade into clobbering a player's real server
 * list.
 */
public final class ServersDatEditor {

    private ServersDatEditor() {
    }

    public record ServerListEntry(String name, String ip, String icon, boolean acceptTextures) {
        public ServerListEntry(String name, String ip) {
            this(name, ip, null, false);
        }
    }

    /**
     * Inserts (or moves) the entry matching {@code entry.ip()} (case-insensitive) to index 0 of
     * the server list, creating {@code serversDatFile} if it doesn't exist yet. All other entries
     * are preserved in their existing relative order.
     */
    public static void upsertPinnedToTop(Path serversDatFile, ServerListEntry entry) throws IOException {
        NbtCompound root = readOrEmpty(serversDatFile);

        NbtTag existing = root.get("servers");
        List<NbtTag> serverList;
        int elementTypeId = 10; // TAG_Compound
        if (existing instanceof NbtList list) {
            serverList = new ArrayList<>(list.values());
            if (list.elementTypeId() != 0) {
                elementTypeId = list.elementTypeId();
            }
        } else {
            serverList = new ArrayList<>();
        }

        serverList.removeIf(tag -> tag instanceof NbtCompound c && entry.ip().equalsIgnoreCase(c.getString("ip", "")));

        NbtCompound newEntry = new NbtCompound();
        newEntry.put("name", new NbtString(entry.name()));
        newEntry.put("ip", new NbtString(entry.ip()));
        if (entry.icon() != null) {
            newEntry.put("icon", new NbtString(entry.icon()));
        }
        newEntry.put("acceptTextures", new NbtByte((byte) (entry.acceptTextures() ? 1 : 0)));

        serverList.add(0, newEntry);
        root.put("servers", new NbtList(elementTypeId, serverList));

        writeAtomically(serversDatFile, root);
    }

    /** Returns entries as (name, ip) pairs in list order - mainly useful for tests/inspection. */
    public static List<ServerListEntry> readEntries(Path serversDatFile) throws IOException {
        NbtCompound root = readOrEmpty(serversDatFile);
        List<ServerListEntry> result = new ArrayList<>();
        if (root.get("servers") instanceof NbtList list) {
            for (NbtTag tag : list.values()) {
                if (tag instanceof NbtCompound c) {
                    result.add(new ServerListEntry(
                            c.getString("name", ""),
                            c.getString("ip", ""),
                            c.getString("icon", null),
                            c.get("acceptTextures") instanceof NbtByte b && b.value() != 0));
                }
            }
        }
        return result;
    }

    private static NbtCompound readOrEmpty(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new NbtCompound();
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            return NbtIo.readRootCompound(in);
        }
    }

    private static void writeAtomically(Path file, NbtCompound root) throws IOException {
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent, "servers-dat", ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            NbtIo.writeRootCompound(out, root);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
