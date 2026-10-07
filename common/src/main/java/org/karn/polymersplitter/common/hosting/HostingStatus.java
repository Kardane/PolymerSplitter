package org.karn.polymersplitter.common.hosting;

import java.util.Objects;

public record HostingStatus(
        Kind kind,
        String providerType,
        boolean supported,
        String message
) {
    public HostingStatus {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(message, "message");
    }

    public enum Kind {
        LOCAL,
        DISABLED,
        EXTERNAL,
        EMPTY,
        UNKNOWN
    }
}
