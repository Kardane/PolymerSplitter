package org.karn.polymersplitter.polymer;

import eu.pb4.polymer.resourcepack.api.OutputGenerator;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.impl.PolymerResourcePackMod;
import net.minecraft.commands.CommandSourceStack;
import org.karn.polymersplitter.common.lifecycle.SplitCoordinator;
import org.karn.polymersplitter.common.pack.SplitPack;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class PolymerGenerationHook {
    private static final System.Logger LOGGER = System.getLogger("PolymerSplitter");

    private PolymerGenerationHook() {
    }

    public static void register(SplitCoordinator coordinator) {
        PolymerResourcePackUtils.RESOURCE_PACK_INITIALIZED_EVENT.register(coordinator::markGenerating);
        PolymerResourcePackUtils.RESOURCE_PACK_FINISHED_EVENT.register(result -> {
            if (result instanceof OutputGenerator.Result output) {
                if (output.hadIssues()) {
                    coordinator.markFailed(new IllegalStateException(
                            "Polymer reported issues while generating the resource pack"
                    ));
                    LOGGER.log(System.Logger.Level.ERROR,
                            "Polymer resource pack generation reported issues; split registry was not replaced");
                    return;
                }

                split(coordinator, output.path());
                return;
            }

            split(coordinator, PolymerResourcePackUtils.getMainPath());
        });
    }

    public static void requestRebuild(CommandSourceStack source) {
        PolymerResourcePackMod.generateAndCall(
                source.getServer(),
                false,
                message -> source.sendSuccess(() -> message, true),
                ignored -> {
                }
        );
    }

    private static void split(SplitCoordinator coordinator, Path generatedPack) {
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

        LOGGER.log(System.Logger.Level.INFO,
                "Published split generation: packs=" + packs.size()
                        + ", total=" + formatBytes(totalSize)
                        + ", sourceSha1=" + coordinator.sourceHash());

        if (!coordinator.config().logPackSizes()) {
            return;
        }

        packs.stream()
                .sorted(Comparator.comparing(SplitPack::namespace))
                .forEach(pack -> LOGGER.log(
                        System.Logger.Level.INFO,
                        "Pack " + pack.namespace()
                                + ": " + formatBytes(pack.size())
                                + ", sha1=" + pack.sha1()
                ));
    }

    private static String formatBytes(long bytes) {
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
