package dev.pluginsync.core.scan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scans a directory for jar files and computes their SHA-256 hashes. */
public final class ModsFolderScanner {

    private ModsFolderScanner() {
    }

    public record ScannedFile(String fileName, String sha256, long size) {
    }

    /**
     * Scans {@code dir} (non-recursive) for {@code *.jar} files, computing a streaming SHA-256
     * digest for each. Returns an empty map if the directory does not exist.
     */
    public static Map<String, ScannedFile> scan(Path dir) throws IOException {
        Map<String, ScannedFile> result = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String fileName = file.getFileName().toString();
                result.put(fileName, new ScannedFile(fileName, sha256Hex(file), Files.size(file)));
            }
        }
        return result;
    }

    /**
     * Lists {@code *.jar} file names in {@code dir}, sorted, without hashing anything. For callers
     * that only need to know which mods exist - hashing a whole modpack just to read its file names
     * is a lot of I/O for nothing. Returns an empty list if the directory does not exist.
     */
    public static List<String> listJarNames(Path dir) throws IOException {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    names.add(file.getFileName().toString());
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    public static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
