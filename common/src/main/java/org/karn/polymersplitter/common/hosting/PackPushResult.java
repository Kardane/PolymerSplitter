package org.karn.polymersplitter.common.hosting;

public record PackPushResult(
        int targetedPlayers,
        int readyPlayers,
        int selectedPacks,
        int packetsSent
) {
    public PackPushResult {
        if (targetedPlayers < 0
                || readyPlayers < 0
                || selectedPacks < 0
                || packetsSent < 0) {
            throw new IllegalArgumentException("Pack push counts must not be negative");
        }
        if (readyPlayers > targetedPlayers) {
            throw new IllegalArgumentException("readyPlayers cannot exceed targetedPlayers");
        }
    }

    public int skippedPlayers() {
        return targetedPlayers - readyPlayers;
    }
}
