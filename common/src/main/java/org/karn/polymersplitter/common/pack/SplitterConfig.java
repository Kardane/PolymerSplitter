package org.karn.polymersplitter.common.pack;

public record SplitterConfig(
        boolean copyPackIcon,
        boolean deterministicZip,
        boolean logPackSizes,
        int minSplitPackSizeMb,
        int compressionLevel
) {
    public SplitterConfig {
        if (minSplitPackSizeMb < 0) {
            throw new IllegalArgumentException("minSplitPackSizeMb must be non-negative");
        }
        if (compressionLevel < 0 || compressionLevel > 9) {
            throw new IllegalArgumentException("compressionLevel must be between 0 and 9");
        }
    }

    public long minSplitPackSizeBytes() {
        return minSplitPackSizeMb * 1024L * 1024L;
    }
}
