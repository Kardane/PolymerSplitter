package org.karn.polymersplitter.common.pack;

public record OutputCompatibility(
        int algorithmVersion,
        boolean copyPackIcon,
        boolean deterministicZip,
        int minSplitPackSizeMb
) {
    public static final int CURRENT_ALGORITHM_VERSION = 3;

    public OutputCompatibility {
        if (algorithmVersion <= 0) {
            throw new IllegalArgumentException("algorithmVersion must be positive");
        }
        if (minSplitPackSizeMb < 0) {
            throw new IllegalArgumentException("minSplitPackSizeMb must be non-negative");
        }
    }

    public static OutputCompatibility current(SplitterConfig config) {
        return new OutputCompatibility(
                CURRENT_ALGORITHM_VERSION,
                config.copyPackIcon(),
                config.deterministicZip(),
                config.minSplitPackSizeMb()
        );
    }
}
