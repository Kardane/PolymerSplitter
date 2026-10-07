package org.karn.polymersplitter.common.pack;

public record SplitterConfig(
        boolean enabled,
        boolean copyPackIcon,
        boolean deterministicZip,
        boolean logPackSizes
) {
    public static SplitterConfig defaults() {
        return new SplitterConfig(true, true, true, true);
    }
}
