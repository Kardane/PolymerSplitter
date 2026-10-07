package org.karn.polymersplitter.polymer;

import org.karn.polymersplitter.common.lifecycle.SplitCoordinator;
import org.karn.polymersplitter.common.pack.SplitPack;
import org.karn.polymersplitter.util.Format;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class SplitPublisher {
    private static final System.Logger LOGGER = System.getLogger("PolymerSplitter");

    private SplitPublisher() {
    }

    static void publish(SplitCoordinator coordinator, Path generatedPack) {
        var hosting = PolymerAutoHostBridge.currentHostingStatus();
        if (!hosting.supported()) {
            coordinator.markFailed(new IllegalStateException(hosting.message()));
            LOGGER.log(System.Logger.Level.WARNING,
                    "Skipping split-pack publication for AutoHost provider '"
                            + hosting.providerType() + "': " + hosting.message());
            return;
        }

        try {
            List<SplitPack> packs = coordinator.process(
                    generatedPack,
                    hostedPacks -> PolymerAutoHostBridge.registerHostedPacks(
                            coordinator.outputRoot(),
                            hostedPacks
                    )
            );
            logGeneration(coordinator, packs);
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.ERROR,
                    "Failed to split or register Polymer resource pack; existing split registry was kept", e);
        }
    }

    private static void logGeneration(SplitCoordinator coordinator, List<SplitPack> packs) {
        long totalSize = packs.stream().mapToLong(SplitPack::size).sum();
        var transition = coordinator.lastTransition();

        LOGGER.log(System.Logger.Level.INFO,
                "Published split generation: packs=" + packs.size()
                        + ", total=" + Format.formatBytes(totalSize)
                        + ", sourceSha1=" + coordinator.sourceHash()
                        + ", added=" + transition.added().size()
                        + ", removed=" + transition.removed().size()
                        + ", changed=" + transition.changed().size()
                        + ", unchanged=" + transition.unchanged().size());

        if (!transition.removed().isEmpty()) {
            LOGGER.log(System.Logger.Level.INFO,
                    "Retired namespaces: " + String.join(", ", transition.removed()));
        }

        if (!coordinator.config().logPackSizes()) {
            return;
        }

        packs.stream()
                .sorted(Comparator.comparing(SplitPack::namespace))
                .forEach(pack -> LOGGER.log(
                        System.Logger.Level.INFO,
                        "Pack " + pack.namespace()
                                + ": " + Format.formatBytes(pack.size())
                                + ", sha1=" + pack.sha1()
                ));
    }
}
