package org.karn.polymersplitter.common.pack;

public record SplitterConfig(
        boolean copyPackIcon,
        boolean deterministicZip
) {
    public static SplitterConfig defaults() {
        return new SplitterConfig(true, true);
    }
}
