package org.karn.polymersplitter.common.pack;

public record OutputCompatibility(
        int algorithmVersion,
        boolean copyPackIcon,
        boolean deterministicZip
) {
    public static final int CURRENT_ALGORITHM_VERSION = 1;

    public OutputCompatibility {
        if (algorithmVersion <= 0) {
            throw new IllegalArgumentException("algorithmVersion must be positive");
        }
    }

    public static OutputCompatibility current(SplitterConfig config) {
        return new OutputCompatibility(
                CURRENT_ALGORITHM_VERSION,
                config.copyPackIcon(),
                config.deterministicZip()
        );
    }
}
