package org.karn.polymersplitter.common.pack;

public record SplitterConfig(
        boolean copyPackIcon,
        boolean deterministicZip,
        boolean logPackSizes,
        int minSplitPackSizeMb
) {
    public SplitterConfig {
        if (minSplitPackSizeMb < 0) {
            throw new IllegalArgumentException("minSplitPackSizeMb must be non-negative");
        }
    }

    public long minSplitPackSizeBytes() {
        return minSplitPackSizeMb * 1024L * 1024L;
    }
}
