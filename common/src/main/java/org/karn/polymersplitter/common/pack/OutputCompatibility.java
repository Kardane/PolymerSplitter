package org.karn.polymersplitter.common.pack;

public record OutputCompatibility(
        int algorithmVersion,
        boolean copyPackIcon,
        boolean deterministicZip,
        int minSplitPackSizeMb,
        int compressionLevel
) {
    public static final int CURRENT_ALGORITHM_VERSION = 4;

    public OutputCompatibility {
        if (algorithmVersion <= 0) {
            throw new IllegalArgumentException("algorithmVersion must be positive");
        }
        if (minSplitPackSizeMb < 0) {
            throw new IllegalArgumentException("minSplitPackSizeMb must be non-negative");
        }
        if (compressionLevel < 0 || compressionLevel > 9) {
            throw new IllegalArgumentException("compressionLevel must be between 0 and 9");
        }
    }

    public static OutputCompatibility current(SplitterConfig config) {
        return new OutputCompatibility(
                CURRENT_ALGORITHM_VERSION,
                config.copyPackIcon(),
                config.deterministicZip(),
                config.minSplitPackSizeMb(),
                config.compressionLevel()
        );
    }
}
