package org.karn.polymersplitter.common.lifecycle;

import org.karn.polymersplitter.common.pack.PackHashUtil;
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
    private final PackSplitter splitter;

    private final AtomicReference<SplitState> state = new AtomicReference<>(SplitState.NOT_STARTED);
    private final AtomicReference<Path> sourcePack = new AtomicReference<>();
    private final AtomicReference<String> sourceHash = new AtomicReference<>();
    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    public SplitCoordinator(Path outputRoot, SplitterConfig config, SplitRegistry registry) {
        this(outputRoot, config, registry, new PackSplitter());
    }

    SplitCoordinator(
            Path outputRoot,
            SplitterConfig config,
            SplitRegistry registry,
            PackSplitter splitter
    ) {
        this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot").toAbsolutePath().normalize();
        this.config = Objects.requireNonNull(config, "config");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.splitter = Objects.requireNonNull(splitter, "splitter");
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
            List<SplitPack> packs = List.copyOf(snapshot.packs());

            beforePublish.accept(packs);

            registry.replace(packs);
            sourcePack.set(null);
            sourceHash.set(snapshot.sourceHash());
            lastFailure.set(null);
            state.set(SplitState.READY);

            return Optional.of(packs);
        } catch (IOException | RuntimeException e) {
            markFailed(e);
            throw e;
        }
    }

    public synchronized List<SplitPack> process(Path generatedPack) throws IOException {
        return process(generatedPack, packs -> {
        });
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

            String hash = PackHashUtil.sha1(normalizedSource);
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

            List<SplitPack> immutablePacks = List.copyOf(packs);

            // External publication (for example AutoHost registration) must complete
            // before the new generation becomes visible to readers.
            beforePublish.accept(immutablePacks);

            registry.replace(immutablePacks);
            sourcePack.set(normalizedSource);
            sourceHash.set(hash);
            lastFailure.set(null);
            state.set(SplitState.READY);

            persistCacheBestEffort(hash, immutablePacks);
            cleanupOldGenerationsBestEffort(
                    hash,
                    previousCache.map(SplitCacheIndex.Snapshot::sourceHash).orElse(null)
            );

            return immutablePacks;
        } catch (IOException | RuntimeException e) {
            markFailed(e);
            throw e;
        }
    }

    private Optional<SplitCacheIndex.Snapshot> readCacheBestEffort() {
        try {
            return SplitCacheIndex.read(outputRoot);
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private void persistCacheBestEffort(String hash, List<SplitPack> packs) {
        try {
            SplitCacheIndex.write(outputRoot, hash, packs);
        } catch (IOException | RuntimeException ignored) {
            // Cache metadata must never invalidate an otherwise usable split generation.
        }
    }

    private void cleanupOldGenerationsBestEffort(String currentHash, String previousHash) {
        try {
            SplitCacheIndex.cleanupOldGenerations(outputRoot, currentHash, previousHash);
        } catch (IOException | RuntimeException ignored) {
            // Old cache cleanup is opportunistic and must not affect pack delivery.
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

    public Path sourcePack() {
        return sourcePack.get();
    }

    public String sourceHash() {
        return sourceHash.get();
    }

    public String lastFailure() {
        return lastFailure.get();
    }
}
