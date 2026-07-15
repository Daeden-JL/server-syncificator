package dev.pluginsync.core.serverlist;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, full-fidelity in-memory model of the NBT tag types used by Minecraft's
 * {@code servers.dat} (and, incidentally, every other vanilla NBT file). Implemented from scratch
 * here specifically so that {@link ServersDatEditor} can read-modify-write a player's real
 * {@code servers.dat} without depending on any Minecraft/loader class - every standard tag type is
 * supported (not just the ones {@code servers.dat} happens to use today) so unrelated/future
 * fields always round-trip untouched.
 */
public sealed interface NbtTag {

    int typeId();

    record NbtByte(byte value) implements NbtTag {
        public int typeId() {
            return 1;
        }
    }

    record NbtShort(short value) implements NbtTag {
        public int typeId() {
            return 2;
        }
    }

    record NbtInt(int value) implements NbtTag {
        public int typeId() {
            return 3;
        }
    }

    record NbtLong(long value) implements NbtTag {
        public int typeId() {
            return 4;
        }
    }

    record NbtFloat(float value) implements NbtTag {
        public int typeId() {
            return 5;
        }
    }

    record NbtDouble(double value) implements NbtTag {
        public int typeId() {
            return 6;
        }
    }

    record NbtByteArray(byte[] value) implements NbtTag {
        public int typeId() {
            return 7;
        }
    }

    record NbtString(String value) implements NbtTag {
        public int typeId() {
            return 8;
        }
    }

    /** {@code elementTypeId} is preserved even for an empty list, matching vanilla's own behavior. */
    record NbtList(int elementTypeId, List<NbtTag> values) implements NbtTag {
        public int typeId() {
            return 9;
        }
    }

    final class NbtCompound implements NbtTag {
        private final Map<String, NbtTag> entries = new LinkedHashMap<>();

        public int typeId() {
            return 10;
        }

        public Map<String, NbtTag> entries() {
            return entries;
        }

        public NbtCompound put(String key, NbtTag value) {
            entries.put(key, value);
            return this;
        }

        public NbtTag get(String key) {
            return entries.get(key);
        }

        public String getString(String key, String fallback) {
            NbtTag tag = entries.get(key);
            return tag instanceof NbtString s ? s.value() : fallback;
        }
    }

    record NbtIntArray(int[] value) implements NbtTag {
        public int typeId() {
            return 11;
        }
    }

    record NbtLongArray(long[] value) implements NbtTag {
        public int typeId() {
            return 12;
        }
    }
}
