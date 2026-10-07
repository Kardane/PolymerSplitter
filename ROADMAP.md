# PolymerSplitter Roadmap

This document tracks development after the Phase 1-9 implementation baseline.

The immediate goal is to reach a reliable first release candidate before adding more aggressive splitting or optimization features.

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
- Copies ordinary root-level files into every namespace pack.
- Omits overlay-local `pack.mcmeta` and `pack.png`, which Minecraft ignores.
- Rejects undeclared/unknown root directories and unsupported overlay content so the normal Polymer main pack remains the fallback.
- Includes overlay/shared-root content in namespace fingerprints and bumps the fingerprint schema to invalidate incompatible old cache entries.

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
- Final ZIPs are verified and materialized into immutable `generated/hosted/<sha1>.zip` blobs before AutoHost registration.
- Hosted blobs prefer hard links and fall back to copies.
- All blobs are materialized before new AutoHost IDs are registered.
- Generation-directory cleanup can retire old generations without invalidating issued URLs.
- Hosted blobs are intentionally retained because supported Polymer AutoHost versions do not expose a public unregister API; safe garbage collection is deferred to later lifecycle/operations work.

## Phase 12 — Recovery and cache restore

Make a valid previous split generation recoverable across server restarts and interrupted rebuilds.

### Scope

- Restore a valid registry from `current-cache.tsv` when possible.
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

## Phase 13 — AutoHost configuration hardening

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

## Phase 14 — Namespace lifecycle cleanup

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

## Phase 15 — `minecraft` namespace optimization

Reduce the remaining large-pack problem caused by many Polymer mods writing into `assets/minecraft`.

This phase requires dependency-aware design before implementation.

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

### Completion criteria

A secondary split strategy is implemented only if its dependency rules are explicit and safe.

## Phase 16 — Small namespace grouping

Avoid generating excessive numbers of tiny resource packs.

### Scope

- Define a configurable grouping threshold.
- Combine small namespaces into deterministic group packs.
- Keep large namespaces independent.
- Preserve stable group identity when membership is unchanged.
- Define cache invalidation when group membership changes.

Possible output:

```text
minecraft.zip
polyfactory.zip
large_mod.zip
small-namespaces.zip
```

## Phase 17 — Performance optimization

Optimize generation after correctness and lifecycle behavior are stable.

### Scope

- Reduce duplicate ZIP reads between fingerprinting and compression.
- Consider bounded parallel namespace compression.
- Keep memory bounded and streaming-based.
- Add generation timing metrics.
- Add cache hit/miss counts.
- Measure large-pack behavior before choosing concurrency defaults.

### Constraints

- Do not introduce unbounded worker pools.
- Do not replace streaming I/O with whole-file heap buffering.
- Deterministic output must remain stable.

## Phase 18 — Advanced configuration

Expose additional policy only after the underlying semantics are implemented.

Candidates:

- `includeNamespaces`
- `excludeNamespaces`
- small-pack grouping threshold
- generation retention count
- ZIP compression level
- configuration reload

Excluded namespaces must not simply disappear from the resource pack; they must remain available through a safe fallback or grouped pack strategy.

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
- current generation directory,
- source pack size,
- split generation duration,
- hosting mode,
- previous generation retained.

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
