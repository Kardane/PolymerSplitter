package org.karn.polymersplitter.util;

public final class Format {
    private Format() {
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB"};
        int unit = -1;

        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit + 1 < units.length);

        return String.format("%.1f %s", value, units[unit]);
    }
}
