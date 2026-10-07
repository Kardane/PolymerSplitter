package org.karn.polymersplitter.common.pack;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class PackIdUtil {
    private static final String PREFIX = "polymersplitter:";

    private PackIdUtil() {
    }

    public static UUID uuidForNamespace(String namespace) {
        return UUID.nameUUIDFromBytes((PREFIX + namespace).getBytes(StandardCharsets.UTF_8));
    }
}
