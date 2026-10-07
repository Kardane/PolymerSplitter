package org.karn.polymersplitter.common.lifecycle;

import org.karn.polymersplitter.common.pack.Hashes;
import org.karn.polymersplitter.common.pack.PackSplitter;
import org.karn.polymersplitter.common.pack.SplitCacheIndex;
import org.karn.polymersplitter.common.pack.SplitPack;
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
    private final PackSplitter splitter = new PackSplitter();
    private final AtomicReference<CoordinatorSnapshot> snapshot =
            new AtomicReference<>(CoordinatorSnapshot.initial());
    private final SplitRegistry registry = new SplitRegistry(snapshot);

    public SplitCoordinator(Path outputRoot, SplitterConfig config) {
        this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot").toAbsolutePath().normalize();
        this.config = Objects.requireNonNull(config, "config");
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
            Optional<SplitCacheIndex.Snapshot> cached = SplitCacheIndex.read(outputRoot);
            if (cached.isEmpty()) {
                return Optional.empty();
            }

            SplitCacheIndex.Verification verification =
                    SplitCacheIndex.verify(outputRoot, cached.get());
            SplitCacheIndex.Snapshot repaired =
                    SplitCacheIndex.repair(outputRoot, verification);

            SplitGeneration generation = SplitGeneration.create(
                    repaired.sourceHash(),
                    repaired.packs()
            );

            beforePublish.accept(generation.packs());

            snapshot.updateAndGet(current -> new CoordinatorSnapshot(
                    SplitState.READY,
                    generation,
                    null,
                    NamespaceTransition.between(current.generation(), generation)
            ));

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
            Path generationDirectory = outputRoot.resolve("generation-" + hash);

            Optional<SplitCacheIndex.Snapshot> previousCache = loadCacheForReuseBestEffort();
            List<SplitPack> reusablePacks = previousCache
                    .map(SplitCacheIndex.Snapshot::packs)
                    .orElseGet(List::of);

            List<SplitPack> packs = splitter.split(
                    normalizedSource,
                    generationDirectory,
                    config,
                    reusablePacks
            );

            if (packs.isEmpty()) {
                throw new IOException("Generated Polymer resource pack contains no resource namespaces");
            }

            SplitGeneration generation = SplitGeneration.create(hash, packs);

            // External publication (for example immutable AutoHost registration) must
            // complete before persistent/current generation state becomes visible.
            beforePublish.accept(generation.packs());

            // The cache index is part of publication state, not best-effort metadata.
            // It must be atomically committed before the coordinator snapshot advances.
            SplitCacheIndex.reconcileGeneration(outputRoot, hash, generation.packs());
            SplitCacheIndex.write(outputRoot, hash, generation.packs());

            snapshot.updateAndGet(current -> new CoordinatorSnapshot(
                    SplitState.READY,
                    generation,
                    null,
                    NamespaceTransition.between(current.generation(), generation)
            ));

            cleanupOldGenerationsBestEffort(
                    hash,
                    previousCache.map(SplitCacheIndex.Snapshot::sourceHash).orElse(null)
            );

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

    private Optional<SplitCacheIndex.Snapshot> loadCacheForReuseBestEffort() {
        try {
            Optional<SplitCacheIndex.Snapshot> cached = SplitCacheIndex.read(outputRoot);
            if (cached.isEmpty()) {
                return Optional.empty();
            }

            SplitCacheIndex.Verification verification =
                    SplitCacheIndex.verify(outputRoot, cached.get());
            return Optional.of(SplitCacheIndex.repair(outputRoot, verification));
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private void cleanupOldGenerationsBestEffort(String currentHash, String previousHash) {
        try {
            SplitCacheIndex.cleanupOldGenerations(outputRoot, currentHash, previousHash);
        } catch (IOException | RuntimeException ignored) {
            // Old generation cleanup is opportunistic and must not affect pack delivery.
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
