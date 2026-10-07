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
    private final SplitRegistry registry;
    private final PackSplitter splitter = new PackSplitter();

    private final AtomicReference<SplitState> state = new AtomicReference<>(SplitState.NOT_STARTED);
    private final AtomicReference<String> sourceHash = new AtomicReference<>();
    private final AtomicReference<String> lastFailure = new AtomicReference<>();
    private final AtomicReference<NamespaceTransition> lastTransition =
            new AtomicReference<>(NamespaceTransition.empty());

    public SplitCoordinator(Path outputRoot, SplitterConfig config, SplitRegistry registry) {
        this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot").toAbsolutePath().normalize();
        this.config = Objects.requireNonNull(config, "config");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void markGenerating() {
        state.set(SplitState.GENERATING);
        lastFailure.set(null);
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

            SplitCacheIndex.Snapshot snapshot = cached.get();
            SplitGeneration generation = SplitGeneration.create(
                    snapshot.sourceHash(),
                    snapshot.packs()
            );

            beforePublish.accept(generation.packs());

            NamespaceTransition transition = registry.replace(generation);
            sourceHash.set(generation.sourceHash());
            lastFailure.set(null);
            lastTransition.set(transition);
            state.set(SplitState.READY);

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

            Optional<SplitCacheIndex.Snapshot> previousCache = readCacheBestEffort();
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
            // It must be atomically committed before the in-memory registry advances.
            SplitCacheIndex.reconcileGeneration(outputRoot, hash, generation.packs());
            SplitCacheIndex.write(outputRoot, hash, generation.packs());

            NamespaceTransition transition = registry.replace(generation);
            sourceHash.set(hash);
            lastFailure.set(null);
            lastTransition.set(transition);
            state.set(SplitState.READY);

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
        NamespaceTransition transition = registry.clear();
        sourceHash.set(null);
        lastFailure.set(null);
        lastTransition.set(transition);
        state.set(SplitState.NOT_STARTED);
    }

    private Optional<SplitCacheIndex.Snapshot> readCacheBestEffort() {
        try {
            return SplitCacheIndex.read(outputRoot);
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
        lastFailure.set(failure.getMessage() != null ? failure.getMessage() : failure.getClass().getName());
        state.set(SplitState.FAILED);
    }

    public SplitState state() {
        return state.get();
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
        return sourceHash.get();
    }

    public String lastFailure() {
        return lastFailure.get();
    }

    public NamespaceTransition lastTransition() {
        return lastTransition.get();
    }
}
