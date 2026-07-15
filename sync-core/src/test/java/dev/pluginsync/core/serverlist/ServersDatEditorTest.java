package dev.pluginsync.core.serverlist;

import dev.pluginsync.core.serverlist.NbtTag.NbtCompound;
import dev.pluginsync.core.serverlist.NbtTag.NbtList;
import dev.pluginsync.core.serverlist.NbtTag.NbtString;
import dev.pluginsync.core.serverlist.ServersDatEditor.ServerListEntry;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServersDatEditorTest {

    @Test
    void createsFileWhenMissingAndInsertsEntry(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Path serversDat = tempDir.resolve("servers.dat");
        assertTrue(Files.notExists(serversDat));

        ServersDatEditor.upsertPinnedToTop(serversDat, new ServerListEntry("My Server", "play.example.com"));

        List<ServerListEntry> entries = ServersDatEditor.readEntries(serversDat);
        assertEquals(1, entries.size());
        assertEquals("My Server", entries.get(0).name());
        assertEquals("play.example.com", entries.get(0).ip());
    }

    @Test
    void pinsToTopWithoutDisturbingOtherEntries(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Path serversDat = tempDir.resolve("servers.dat");
        ServersDatEditor.upsertPinnedToTop(serversDat, new ServerListEntry("Friend's Server", "friend.example.com"));
        ServersDatEditor.upsertPinnedToTop(serversDat, new ServerListEntry("Another Server", "another.example.com"));

        // Now pin our managed server - it should land at index 0, the others keep their order.
        ServersDatEditor.upsertPinnedToTop(serversDat, new ServerListEntry("Managed Server", "managed.example.com"));

        List<ServerListEntry> entries = ServersDatEditor.readEntries(serversDat);
        assertEquals(3, entries.size());
        assertEquals("managed.example.com", entries.get(0).ip());
        assertEquals("another.example.com", entries.get(1).ip());
        assertEquals("friend.example.com", entries.get(2).ip());
    }

    @Test
    void reUpsertingSameIpMovesRatherThanDuplicates(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Path serversDat = tempDir.resolve("servers.dat");
        ServersDatEditor.upsertPinnedToTop(serversDat, new ServerListEntry("Other", "other.example.com"));
        ServersDatEditor.upsertPinnedToTop(serversDat, new ServerListEntry("Managed", "managed.example.com"));
        ServersDatEditor.upsertPinnedToTop(serversDat, new ServerListEntry("Other", "other.example.com"));
        // Re-run the managed upsert (as would happen on every client startup)
        ServersDatEditor.upsertPinnedToTop(serversDat, new ServerListEntry("Managed", "managed.example.com"));

        List<ServerListEntry> entries = ServersDatEditor.readEntries(serversDat);
        assertEquals(2, entries.size());
        assertEquals("managed.example.com", entries.get(0).ip());
        assertEquals("other.example.com", entries.get(1).ip());
    }

    @Test
    void preservesIconAndAcceptTexturesFields(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Path serversDat = tempDir.resolve("servers.dat");
        ServersDatEditor.upsertPinnedToTop(serversDat, new ServerListEntry("Managed", "managed.example.com", "iconbase64data", true));

        List<ServerListEntry> entries = ServersDatEditor.readEntries(serversDat);
        assertEquals("iconbase64data", entries.get(0).icon());
        assertTrue(entries.get(0).acceptTextures());
    }

    @Test
    void updatingAnExistingEntryPreservesFieldsThisCodebaseDoesNotKnowAbout(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Path serversDat = tempDir.resolve("servers.dat");
        ServersDatEditor.upsertPinnedToTop(serversDat, new ServerListEntry("Managed", "managed.example.com"));

        // Simulate the game (or another mod) having added a field we don't model, e.g. "moreInfo".
        addUnknownFieldToMatchingEntry(serversDat, "managed.example.com", "moreInfo", "some-vanilla-metadata");

        // Re-run the upsert, as happens on every client startup - this must not drop the field.
        ServersDatEditor.upsertPinnedToTop(serversDat, new ServerListEntry("Managed (renamed)", "managed.example.com"));

        assertEquals("some-vanilla-metadata", readUnknownFieldFromMatchingEntry(serversDat, "managed.example.com", "moreInfo"));
        assertEquals("Managed (renamed)", ServersDatEditor.readEntries(serversDat).get(0).name());
    }

    private static void addUnknownFieldToMatchingEntry(Path file, String ip, String key, String value) throws IOException {
        NbtCompound root;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            root = NbtIo.readRootCompound(in);
        }
        if (root.get("servers") instanceof NbtList list) {
            for (NbtTag tag : list.values()) {
                if (tag instanceof NbtCompound c && ip.equalsIgnoreCase(c.getString("ip", ""))) {
                    c.put(key, new NbtString(value));
                }
            }
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            NbtIo.writeRootCompound(out, root);
        }
    }

    private static String readUnknownFieldFromMatchingEntry(Path file, String ip, String key) throws IOException {
        NbtCompound root;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            root = NbtIo.readRootCompound(in);
        }
        if (root.get("servers") instanceof NbtList list) {
            for (NbtTag tag : list.values()) {
                if (tag instanceof NbtCompound c && ip.equalsIgnoreCase(c.getString("ip", "")) && c.get(key) instanceof NbtString s) {
                    return s.value();
                }
            }
        }
        return null;
    }
}
