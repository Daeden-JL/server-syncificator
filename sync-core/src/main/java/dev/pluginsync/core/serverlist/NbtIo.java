package dev.pluginsync.core.serverlist;

import dev.pluginsync.core.serverlist.NbtTag.NbtByte;
import dev.pluginsync.core.serverlist.NbtTag.NbtByteArray;
import dev.pluginsync.core.serverlist.NbtTag.NbtCompound;
import dev.pluginsync.core.serverlist.NbtTag.NbtDouble;
import dev.pluginsync.core.serverlist.NbtTag.NbtFloat;
import dev.pluginsync.core.serverlist.NbtTag.NbtInt;
import dev.pluginsync.core.serverlist.NbtTag.NbtIntArray;
import dev.pluginsync.core.serverlist.NbtTag.NbtList;
import dev.pluginsync.core.serverlist.NbtTag.NbtLong;
import dev.pluginsync.core.serverlist.NbtTag.NbtLongArray;
import dev.pluginsync.core.serverlist.NbtTag.NbtShort;
import dev.pluginsync.core.serverlist.NbtTag.NbtString;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes uncompressed, big-endian NBT - the format Minecraft has used for
 * {@code servers.dat} since it was introduced. Handles every standard tag type so arbitrary
 * compounds (including ones with fields we don't know about) round-trip byte-for-byte.
 */
final class NbtIo {

    private NbtIo() {
    }

    /** Reads a root (unnamed) compound tag. */
    static NbtCompound readRootCompound(DataInputStream in) throws IOException {
        int typeId = in.readUnsignedByte();
        if (typeId != 10) {
            throw new IOException("Expected root TAG_Compound (10), got tag type " + typeId);
        }
        in.readUTF(); // root name, conventionally empty - discarded, not needed
        return readCompoundBody(in);
    }

    static void writeRootCompound(DataOutputStream out, NbtCompound compound) throws IOException {
        out.writeByte(10);
        out.writeUTF("");
        writeCompoundBody(out, compound);
    }

    private static NbtCompound readCompoundBody(DataInputStream in) throws IOException {
        NbtCompound compound = new NbtCompound();
        while (true) {
            int typeId = in.readUnsignedByte();
            if (typeId == 0) {
                return compound;
            }
            String name = in.readUTF();
            compound.put(name, readPayload(in, typeId));
        }
    }

    private static void writeCompoundBody(DataOutputStream out, NbtCompound compound) throws IOException {
        for (var entry : compound.entries().entrySet()) {
            NbtTag value = entry.getValue();
            out.writeByte(value.typeId());
            out.writeUTF(entry.getKey());
            writePayload(out, value);
        }
        out.writeByte(0); // TAG_End
    }

    private static NbtTag readPayload(DataInputStream in, int typeId) throws IOException {
        return switch (typeId) {
            case 1 -> new NbtByte(in.readByte());
            case 2 -> new NbtShort(in.readShort());
            case 3 -> new NbtInt(in.readInt());
            case 4 -> new NbtLong(in.readLong());
            case 5 -> new NbtFloat(in.readFloat());
            case 6 -> new NbtDouble(in.readDouble());
            case 7 -> {
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                yield new NbtByteArray(bytes);
            }
            case 8 -> new NbtString(in.readUTF());
            case 9 -> {
                int elementTypeId = in.readUnsignedByte();
                int len = in.readInt();
                List<NbtTag> values = new ArrayList<>(Math.max(0, len));
                for (int i = 0; i < len; i++) {
                    values.add(readPayload(in, elementTypeId));
                }
                yield new NbtList(elementTypeId, values);
            }
            case 10 -> readCompoundBody(in);
            case 11 -> {
                int len = in.readInt();
                int[] values = new int[len];
                for (int i = 0; i < len; i++) {
                    values[i] = in.readInt();
                }
                yield new NbtIntArray(values);
            }
            case 12 -> {
                int len = in.readInt();
                long[] values = new long[len];
                for (int i = 0; i < len; i++) {
                    values[i] = in.readLong();
                }
                yield new NbtLongArray(values);
            }
            default -> throw new IOException("Unknown NBT tag type " + typeId);
        };
    }

    // Written as an if/instanceof chain rather than a pattern-matching switch so this compiles
    // under --release 17 (Forge 1.20.1's minimum Java version); pattern matching in switch is a
    // Java 21 feature.
    private static void writePayload(DataOutputStream out, NbtTag tag) throws IOException {
        if (tag instanceof NbtByte t) {
            out.writeByte(t.value());
        } else if (tag instanceof NbtShort t) {
            out.writeShort(t.value());
        } else if (tag instanceof NbtInt t) {
            out.writeInt(t.value());
        } else if (tag instanceof NbtLong t) {
            out.writeLong(t.value());
        } else if (tag instanceof NbtFloat t) {
            out.writeFloat(t.value());
        } else if (tag instanceof NbtDouble t) {
            out.writeDouble(t.value());
        } else if (tag instanceof NbtByteArray t) {
            out.writeInt(t.value().length);
            out.write(t.value());
        } else if (tag instanceof NbtString t) {
            out.writeUTF(t.value());
        } else if (tag instanceof NbtList t) {
            out.writeByte(t.elementTypeId());
            out.writeInt(t.values().size());
            for (NbtTag value : t.values()) {
                writePayload(out, value);
            }
        } else if (tag instanceof NbtCompound t) {
            writeCompoundBody(out, t);
        } else if (tag instanceof NbtIntArray t) {
            out.writeInt(t.value().length);
            for (int v : t.value()) {
                out.writeInt(v);
            }
        } else if (tag instanceof NbtLongArray t) {
            out.writeInt(t.value().length);
            for (long v : t.value()) {
                out.writeLong(v);
            }
        } else {
            throw new IOException("Unknown NbtTag implementation: " + tag.getClass());
        }
    }
}
