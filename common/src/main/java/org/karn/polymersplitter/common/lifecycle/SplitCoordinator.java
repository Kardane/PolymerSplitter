package org.karn.polymersplitter.common.lifecycle;

import org.karn.polymersplitter.common.pack.Hashes;
import org.karn.polymersplitter.common.pack.LegacyCacheMigration;
import org.karn.polymersplitter.common.pack.OutputCompatibility;
import org.karn.polymersplitter.common.pack.PackSplitter;
import org.karn.polymersplitter.common.pack.SplitCacheIndex;
import org.karn.polymersplitter.common.pack.SplitPack;
import org.karn.polymersplitter.common.pack.SplitRecovery;
import org.karn.polymersplitter.common.pack.SplitterConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class SplitCoordinator {
    private final Path outputRoot;
    private final SplitterConfig config;
    private final OutputCompatibility outputCompatibility;
    private final PackSplitter splitter = new PackSplitter();
    private final AtomicReference<CoordinatorSnapshot> snapshot =
            new AtomicReference<>(CoordinatorSnapshot.initial());
    private final SplitRegistry registry = new SplitRegistry(snapshot);

    public SplitCoordinator(Path outputRoot, SplitterConfig config) {
        this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot").toAbsolutePath().normalize();
        this.config = Objects.requireNonNull(config, "config");
        this.outputCompatibility = OutputCompatibility.current(config);
    }

    public void markGenerating() {
        snapshot.updateAndGet(current -> new CoordinatorSnapshot(
                SplitState.GENERATING,
                current.generation(),
                null,
                current.lastTransition()
        ));
    }

    public synchronized Optional<List<SplitPack>> restore(
            Consumer<List<SplitPack>> beforePublish
    ) throws IOException {
        Objects.requireNonNull(beforePublish, "beforePublish");

        try {
            importLegacyBlobsBestEffort();

            Optional<SplitCacheIndex.Snapshot> cached =
                    loadCompatibleVerifiedCache();
            if (cached.isEmpty()) {
                return Optional.empty();
            }

            SplitCacheIndex.Snapshot verified = cached.get();
            SplitGeneration generation = SplitGeneration.create(
                    verified.sourceHash(),
                    verified.packs()
            );

            beforePublish.accept(generation.packs());

            snapshot.updateAndGet(current -> new CoordinatorSnapshot(
                    SplitState.READY,
                    generation,
                    null,
                    NamespaceTransition.between(current.generation(), generation)
            ));

            cleanupLegacyArtifactsBestEffort();
            return Optional.of(generation.packs());
        } catch (IOException | RuntimeException e) {
            markFailed(e);
            throw e;
        }
    }

    public synchronized List<SplitPack> process(
            Path generatedPack,
            Consumer<List<SplitPack>> beforePublish
    ) throws IOException {
        Objects.requireNonNull(generatedPack, "generatedPack");
        Objects.requireNonNull(beforePublish, "beforePublish");
        markGenerating();

        Path normalizedSource = generatedPack.toAbsolutePath().normalize();

        try {
            if (!Files.isRegularFile(normalizedSource)) {
                throw new IOException("Generated Polymer resource pack does not exist: " + normalizedSource);
            }

            String hash = Hashes.sha1(normalizedSource);

            Optional<SplitCacheIndex.Snapshot> previousCache =
                    loadCompatibleVerifiedCacheBestEffort();

            if (previousCache.isPresent()
                    && previousCache.get().sourceHash().equals(hash)) {
                SplitGeneration generation = SplitGeneration.create(
                        hash,
                        previousCache.get().packs()
                );

                // The source bytes, output compatibility, index metadata, and every
                // referenced immutable blob already match. Re-register the hosted
                // files, but skip namespace discovery/fingerprinting/ZIP generation
                // and leave the durable index untouched.
                beforePublish.accept(generation.packs());

                snapshot.updateAndGet(current -> new CoordinatorSnapshot(
                        SplitState.READY,
                        generation,
                        null,
                        NamespaceTransition.between(current.generation(), generation)
                ));

                cleanupLegacyArtifactsBestEffort();
                return generation.packs();
            }

            List<SplitPack> reusablePacks = previousCache
                    .map(SplitCacheIndex.Snapshot::packs)
                    .orElseGet(List::of);

            List<SplitPack> packs = splitter.split(
                    normalizedSource,
                    outputRoot,
                    config,
                    reusablePacks
            );

            if (packs.isEmpty()) {
                throw new IOException("Generated Polymer resource pack contains no resource namespaces");
            }

            SplitGeneration generation = SplitGeneration.create(hash, packs);

            beforePublish.accept(generation.packs());

            SplitCacheIndex.write(
                    outputRoot,
                    hash,
                    generation.packs(),
                    outputCompatibility
            );

            snapshot.updateAndGet(current -> new CoordinatorSnapshot(
                    SplitState.READY,
                    generation,
                    null,
                    NamespaceTransition.between(current.generation(), generation)
            ));

            cleanupLegacyArtifactsBestEffort();
            return generation.packs();
        } catch (IOException | RuntimeException e) {
            markFailed(e);
            throw e;
        }
    }

    public synchronized void resetForServerStop() {
        snapshot.updateAndGet(current -> new CoordinatorSnapshot(
                SplitState.NOT_STARTED,
                null,
                null,
                NamespaceTransition.between(current.generation(), null)
        ));
    }

    private Optional<SplitCacheIndex.Snapshot> loadCompatibleVerifiedCache()
            throws IOException {
        Optional<SplitCacheIndex.ReadResult> cached =
                SplitCacheIndex.read(outputRoot, outputCompatibility);

        if (cached.isEmpty() || !cached.get().isCompatible()) {
            return Optional.empty();
        }

        return Optional.of(
                SplitCacheIndex.verify(outputRoot, cached.get().snapshot())
        );
    }

    private Optional<SplitCacheIndex.Snapshot> loadCompatibleVerifiedCacheBestEffort() {
        try {
            return loadCompatibleVerifiedCache();
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private void importLegacyBlobsBestEffort() {
        try {
            LegacyCacheMigration.importBlobsIfNeeded(outputRoot);
        } catch (IOException | RuntimeException ignored) {
            // Legacy metadata cannot prove current output compatibility.
            // Failed import is therefore a cache miss, not a publication failure.
        }
    }

    private void cleanupLegacyArtifactsBestEffort() {
        try {
            SplitRecovery.cleanupLegacyArtifacts(outputRoot);
        } catch (IOException | RuntimeException ignored) {
            // Legacy files are no longer authoritative once current index.json is committed.
        }
    }

    public void markFailed(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        String message = failure.getMessage() != null
                ? failure.getMessage()
                : failure.getClass().getName();

        snapshot.updateAndGet(current -> new CoordinatorSnapshot(
                SplitState.FAILED,
                current.generation(),
                message,
                current.lastTransition()
        ));
    }

    public CoordinatorSnapshot snapshot() {
        return snapshot.get();
    }

    public SplitState state() {
        return snapshot().state();
    }

    public SplitRegistry registry() {
        return registry;
    }

    public SplitterConfig config() {
        return config;
    }

    public OutputCompatibility outputCompatibility() {
        return outputCompatibility;
    }

    public Path outputRoot() {
        return outputRoot;
    }

    public String sourceHash() {
        return snapshot().sourceHash();
    }

    public String lastFailure() {
        return snapshot().lastFailure();
    }

    public NamespaceTransition lastTransition() {
        return snapshot().lastTransition();
    }
}
