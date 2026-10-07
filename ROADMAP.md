# PolymerSplitter Roadmap

This document tracks development after the Phase 1-9 implementation baseline.

Phases 10-14 are complete. The current runtime/storage architecture is defined by [ARCHITECTURE.md](ARCHITECTURE.md); completed-phase notes below describe the capability delivered by each phase and are kept aligned with the current implementation after later refactors.

## Release boundary

Phases 10-14 define the stability work required before the first `0.1.0` release candidate.

```text
10. Resource-pack format completeness
11. Atomic/versioned AutoHost hosting
12. Recovery and cache restore
13. AutoHost configuration hardening
14. Namespace lifecycle cleanup

--- 0.1.0 release-candidate boundary ---

15. minecraft namespace optimization
16. Small namespace grouping
17. Performance optimization
18. Advanced configuration
19. Operational commands
20. Compatibility hardening
21. Release pipeline
```

## Phase 10 — Resource-pack format completeness

**Status:** completed

**Priority:** highest

Make namespace splitting preserve the semantics of modern resource packs, not only the common `assets/<namespace>/` layout.

### Scope

- Parse and preserve `pack.mcmeta` overlay definitions.
- Route overlay resources to the matching namespace split pack.
- Define handling for root-level resource-pack entries other than:
  - `pack.mcmeta`
  - `pack.png`
  - `assets/.mcassetsroot`
- Reject or safely fall back when an unsupported structure cannot be split without changing pack semantics.
- Keep deterministic ZIP output and cache fingerprints correct for overlay content.

### Completion criteria

- Overlay paths referenced by `pack.mcmeta` are represented correctly in split packs.
- Root metadata required by the pack is not silently lost.
- Unsupported layouts fail safely to Polymer's original main pack.

### Implemented

- Reads declared overlay directories from `pack.mcmeta` without rewriting version-range metadata.
- Routes base and overlay `assets/<namespace>/...` entries into the same namespace pack.
- Preserves safe non-namespace files only in the primary pack, including root files, direct `assets/` files, and auxiliary directories.
- Omits overlay-local `pack.mcmeta` and `pack.png`, which Minecraft ignores.
- Preserves undeclared overlay directories in the primary pack without inferring declarations or applying their resources to base assets; rejects unsafe paths and malformed namespace paths.
- Includes declared overlay content in each namespace fingerprint and auxiliary/merged content only in the primary fingerprint; current output algorithm version 3 and fingerprint schema v4 invalidate prior output caches.

## Phase 11 — Atomic/versioned AutoHost hosting

**Status:** completed

Eliminate the online rebuild race caused by stable hosted identifiers being remapped before the split registry is swapped.

### Scope

- Move toward content-addressed hosted identifiers, for example:

  ```text
  polymersplitter:packs/<namespace>/<sha1>
  ```

- Keep old URLs resolving to the old generation while they may still be in flight.
- Publish all new hosted files before exposing the new registry.
- Define stale hosted-entry cleanup without breaking active downloads.
- Preserve stable Minecraft resource-pack UUIDs independently of hosted identifiers.

### Completion criteria

- An old split-pack URL never resolves to bytes from a newer generation.
- Online rebuilds do not create a mixed-generation download window.
- Previous generation files can be retired safely.

### Implemented

- AutoHost identifiers are content-addressed as `packs/<namespace>/<sha1>`.
- Namespace/Minecraft pack UUIDs remain stable and independent from the hosted identifier.
- `generated/hosted/<sha1>.zip` is the single persistent ZIP store; changed namespace ZIPs are committed atomically to that path and unchanged compatible blobs are reused in place.
- All current blobs exist before their AutoHost IDs are registered.
- Historical blobs and mappings are retained for the lifetime of a running server so issued URLs never remap to different bytes.
- After a later successful startup restore, unreferenced prior-process blobs may be garbage-collected best-effort; in-memory PolymerSplitter mappings are cleared on full server stop.

## Phase 12 — Recovery and cache restore

**Status:** completed

Make a valid previous split generation recoverable across server restarts and interrupted rebuilds.

### Scope

- Restore a valid persisted split generation when possible.
- Verify cached file existence, size, SHA-1, and metadata before reuse.
- Clean abandoned temporary files from interrupted generation.
- Recover cleanly from:
  - missing cache entries,
  - partial generation directories,
  - malformed cache metadata,
  - deleted or corrupted split ZIPs.
- Preserve fallback to Polymer's original main pack when recovery is unsafe.

### Completion criteria

- Valid cached generations survive a restart without unnecessary recompression.
- Broken cache state cannot cause partial split delivery.
- Interrupted writes do not poison future startup.

### Implemented

- Startup reads format-3 `index.json`, requires output-compatibility metadata to match, and verifies every referenced `hosted/<sha1>.zip` by size and SHA-1 before publication.
- Missing or output-incompatible caches are cache misses; malformed or corrupted compatible caches never publish partial state.
- A compatible verified cache can restore the entire generation across restart without recompression.
- Older format-2 indexes are readable only as incompatible metadata and are never relabeled as current output.
- Legacy `current-cache.tsv` data may import validated ZIP bytes into the immutable hosted store, but cannot become current without a successful generation under current compatibility settings.
- Owned temporary files from interrupted writes are removed on startup.
- Compatible unchanged namespaces can reuse verified hosted blobs without recompression.

## Phase 13 — AutoHost configuration hardening

**Status:** completed

Make behavior explicit across Polymer AutoHost provider configurations.

### Scope

- Detect and report relevant provider configuration.
- Verify expected behavior for:
  - same-port / Netty,
  - standalone HTTP,
  - disabled AutoHost,
  - empty provider,
  - external provider.
- Emit a clear warning for external hosting because PolymerSplitter does not upload split ZIPs.
- Avoid silently advertising unusable split URLs.
- Keep Polymer's required-pack and prompt behavior unchanged.

### Completion criteria

- Unsupported or incomplete hosting configurations fail clearly.
- Locally hosted modes continue to use Polymer AutoHost only.
- External-provider limitations are visible to operators.

### Implemented

- Detects the actual AutoHost configuration after server startup rather than relying on pre-start defaults.
- Explicitly supports built-in local providers: automatic/auto, Netty/same-port, and standalone/http-server.
- Blocks split delivery for disabled AutoHost, external, empty, and unknown/custom providers.
- Dynamic compatibility gating prevents split collection and main-pack suppression if provider configuration is unsupported.
- Split publication is skipped before compression/publication work when the active provider is unsupported.
- Startup cache restore runs only when the resolved AutoHost provider can serve registered split files.
- `/polymersplitter status` reports provider type, compatibility class, and support state.
- `/polymersplitter rebuild` refuses a split rebuild when the current hosting provider is unsupported.
- Operators using `polymer:external` receive a clear warning that PolymerSplitter does not upload generated split ZIPs.

## Phase 14 — Namespace lifecycle cleanup

**Status:** completed

Handle namespaces appearing and disappearing between generations.

### Scope

- Remove stale namespace publication state when a namespace disappears.
- Keep deterministic UUIDs stable if a namespace later returns.
- Reconcile cache metadata with actual generation contents.
- Ensure deleted namespaces are not sent to clients.
- Coordinate hosted-entry retirement with Phase 11's versioned hosting strategy.

### Completion criteria

- Registry, cache, hosted paths, and files describe the same generation.
- Removed namespaces cannot leak into later delivery.
- Reintroduced namespaces retain their deterministic identity.

### Implemented

- Runtime state is published as one immutable `CoordinatorSnapshot` containing lifecycle state, active `SplitGeneration`, failure state, and the namespace transition; `SplitRegistry` is a read-only view of that same snapshot.
- Publication computes a `NamespaceTransition` with added, removed, changed, and unchanged namespace sets.
- Removed namespaces disappear atomically from the active generation and therefore from all subsequent resource-pack collection.
- Format-3 `index.json` is the atomic durable pointer to the exact current namespace set and its immutable hosted blobs.
- Namespace pack order is deterministic with `minecraft` first and all other namespaces lexicographic.
- Historical content-addressed AutoHost mappings remain during a running server to protect in-flight URLs, but are not advertised by the current collector.
- On full server stop, PolymerSplitter-owned AutoHost mappings and in-memory state are cleared while `index.json` and hosted blobs remain for validated restart recovery.
- Same-JVM server restarts therefore begin from `NOT_STARTED` instead of retaining stale in-memory state.
- Deterministic namespace UUID derivation remains unchanged, so a namespace removed and later reintroduced receives the same intrinsic UUID.

## Phase 15 — `minecraft` namespace optimization

**Status:** in progress

Reduce the remaining large-pack problem caused by many Polymer mods writing into `assets/minecraft`.

The first implementation is intentionally conservative: move only clearly separable binary sound payloads out of the primary pack while keeping definition/control files in `minecraft`.

### Investigation

Measure the distribution of:

- models,
- textures,
- atlases,
- fonts,
- sounds,
- blockstates,
- item definitions,
- shaders,
- other generated resources.

### Constraints

Do not implement naive file-type splitting. Cross-resource references such as model-to-texture, item-to-model, font providers, atlases, and other dependencies can make independent packs semantically unsafe.

### Implemented first step

- Base and declared-overlay `assets/minecraft/sounds/**/*.ogg` entries are moved into a synthetic physical pack keyed as `minecraft.sounds`.
- `assets/minecraft/sounds.json` and every non-OGG `minecraft` resource stay in the primary `minecraft` pack.
- The sound pack has its own deterministic UUID, fingerprint, SHA-1, content-addressed AutoHost ID, cache entry, and targeted-send key.
- `minecraft.sounds` participates in the same include/exclude and size-threshold policy as other non-primary packs. It may be addressed directly as `minecraft.sounds`, and it also inherits policy from its source namespace `minecraft`; exclude wins over include.
- If a real resource namespace named `minecraft.sounds` already exists, synthetic sound splitting is skipped rather than colliding with it.
- Output algorithm version 7 and physical-pack fingerprint schema v5 invalidate incompatible caches.

### Remaining investigation

Measure whether textures or other resource classes justify further subdivision. Do not add more internal `minecraft` partitions without explicit safety rules.

### Completion criteria

A broader secondary split strategy is implemented only if its dependency and pack-stack rules are explicit and safe.

## Phase 16 — Small namespace grouping

Avoid generating excessive numbers of tiny resource packs.

### Implemented baseline

- `minSplitPackSizeMb` defaults to 30 MiB; `0` disables merging.
- Merge non-primary namespaces whose final compressed ZIP size is at or below the threshold into the existing primary pack, preserving resource paths and overlay declarations.
- Keep larger namespaces independent and retain stable primary/namespace identities.
- Include merged content in the primary fingerprint and the threshold in output compatibility.

### Optional follow-up

Dedicated group packs may be considered if measurements justify their extra identity and delivery complexity. The current implementation uses the primary pack rather than a separate `small-namespaces.zip`.

## Phase 17 — Performance optimization

Optimize generation after correctness and lifecycle behavior are stable.

### Partial optimization work already completed during refactoring

- Whole-source unchanged fast path skips namespace enumeration, fingerprinting, compression, and index rewrite when source SHA-1, output compatibility, and all hosted blobs verify.
- Newly written namespace ZIPs compute final SHA-1 in-stream instead of rereading the completed temporary ZIP solely for hashing.
- One reusable 64 KiB I/O buffer is shared across fingerprint reads and ZIP-entry copies within a split invocation.

### Remaining scope

- Reduce duplicate source-ZIP reads between namespace fingerprinting and compression where this can be done without whole-file buffering.
- Consider bounded parallel namespace compression.
- Add generation timing metrics.
- Add cache hit/miss counts.
- Measure large-pack behavior before choosing concurrency defaults.

### Constraints

- Do not introduce unbounded worker pools.
- Do not replace streaming I/O with whole-file heap buffering.
- Deterministic output must remain stable.

## Phase 18 — Advanced configuration

**Status:** completed

Expose additional policy only after the underlying semantics are implemented.

### Implemented

- `minSplitPackSizeMb` controls small-namespace merging and participates in output compatibility.
- `compressionLevel` controls ZIP deflate level from `0` through `9` and participates in output compatibility.
- `includeNamespaces` optionally restricts which non-primary namespaces may remain independent; all others are safely merged into the primary pack.
- `excludeNamespaces` forces matching non-primary namespaces into the primary pack and takes precedence over the include list.
- Namespace policy never drops resources, and the primary namespace always remains.
- `/polymersplitter reload` reloads configuration without discarding the current READY generation.
- Diagnostic-only settings such as `logPackSizes` take effect immediately after reload.
- Output-affecting settings take effect on the next Polymer generation; operators can use `/polymersplitter rebuild` to apply them immediately.
- `enabled` remains startup-scoped and reload reports when a restart is required.
- `unreferencedBlobRetentionDays` defines hosted-blob retention at the safe startup cleanup point: `0` removes unreferenced blobs on the next successful restore, positive values retain them by age, and `-1` disables automatic blob GC.
- Retention policy is intentionally blob-based rather than generation-count-based because the current storage model has one active index and immutable content-addressed blobs.

## Phase 19 — Operational commands

Expand administrator visibility and maintenance controls.

Candidates:

```text
/polymersplitter cache
/polymersplitter clean
/polymersplitter config
```

Potential status additions:

- cache hits/misses,
- current index/source SHA-1,
- hosted blob count/bytes,
- source pack size,
- split generation duration,
- hosting mode,
- last namespace transition.

Commands must continue to use Polymer's generation lifecycle rather than implementing a parallel resource-pack pipeline.

## Phase 20 — Compatibility hardening

Tighten declared compatibility to what is actually compiled and understood.

### Scope

- Revisit open-ended Minecraft ranges such as `>=26.3`.
- Add a new adapter when Polymer or Fabric APIs actually diverge.
- Keep compile targets and Fabric metadata aligned.
- Verify Polymer minimum versions against the integration points used.
- Update `AGENTS.md` and `ARCHITECTURE.md` when compatibility boundaries change.

## Phase 21 — Release pipeline

Prepare the first public release.

### Scope

- Establish semantic versioning.
- Add `CHANGELOG.md`.
- Add release-oriented GitHub Actions.
- Produce version-specific JAR artifacts.
- Add source/homepage/issues metadata to `fabric.mod.json`.
- Document installation and required Polymer dependencies.
- Create a release checklist.

Suggested first public version:

```text
0.1.0
```

## Validation policy

Repository validation remains build-only unless explicitly changed.

For cross-version work, use the independent CI build matrix:

```text
common
1.21.8
1.21.9-1.21.10
1.21.11
26.1
26.2
26.3
```

Do not add or run Minecraft runtime, gameplay, functional, integration, or separate test suites unless explicitly requested.
