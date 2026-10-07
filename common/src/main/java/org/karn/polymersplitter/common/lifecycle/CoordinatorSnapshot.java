package org.karn.polymersplitter.common.lifecycle;

import java.util.Objects;

public record CoordinatorSnapshot(
        SplitState state,
        SplitGeneration generation,
        String lastFailure,
        NamespaceTransition lastTransition
) {
    public CoordinatorSnapshot {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(lastTransition, "lastTransition");

        if (state == SplitState.READY && generation == null) {
            throw new IllegalArgumentException("READY coordinator snapshot requires a generation");
        }
        if (state == SplitState.NOT_STARTED && generation != null) {
            throw new IllegalArgumentException("NOT_STARTED coordinator snapshot cannot contain a generation");
        }
    }

    public static CoordinatorSnapshot initial() {
        return new CoordinatorSnapshot(
                SplitState.NOT_STARTED,
                null,
                null,
                NamespaceTransition.empty()
        );
    }

    public String sourceHash() {
        return generation == null ? null : generation.sourceHash();
    }
}
