package org.karn.polymersplitter.common.pack;

public record SplitterConfig(
        boolean copyPackIcon,
        boolean deterministicZip,
        boolean logPackSizes
) {
}
