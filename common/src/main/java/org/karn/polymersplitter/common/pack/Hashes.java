package org.karn.polymersplitter.common.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class Hashes {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private Hashes() {
    }

    public static String sha1(Path path) throws IOException {
        MessageDigest digest = digest("SHA-1");

        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        return hex(digest.digest());
    }

    public static MessageDigest sha1() {
        return digest("SHA-1");
    }

    public static MessageDigest sha256() {
        return digest("SHA-256");
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    public static boolean isSha1(String value) {
        return value != null && SHA1.matcher(value).matches();
    }

    public static boolean isSha256(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }
}
