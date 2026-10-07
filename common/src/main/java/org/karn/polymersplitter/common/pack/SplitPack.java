package org.karn.polymersplitter.common.pack;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public record SplitPack(
        String namespace,
        Path path,
        String fingerprint,
        String sha1,
        UUID uuid,
        long size
) {
    public SplitPack {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(sha1, "sha1");
        Objects.requireNonNull(uuid, "uuid");

        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint must not be blank");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
    }
}
