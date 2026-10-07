package org.karn.polymersplitter.common.hosting;

import java.util.Objects;

public record HostingStatus(
        Kind kind,
        String providerType,
        String message
) {
    public HostingStatus {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(message, "message");
    }

    public boolean supported() {
        return kind == Kind.LOCAL;
    }

    public enum Kind {
        LOCAL,
        DISABLED,
        EXTERNAL,
        EMPTY,
        UNKNOWN
    }
}
