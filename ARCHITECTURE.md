# PolymerSplitter Architecture

## 1. Purpose

PolymerSplitter is a server-only Fabric companion mod for [Polymer](https://github.com/Patbox/polymer). It takes Polymer's final generated resource pack, splits it into independently cacheable namespace packs, and delegates hosting, delivery, required-pack behavior, and client response tracking to Polymer AutoHost.

The design optimizes for one outcome: when only one namespace changes, clients should download that namespace pack again instead of re-downloading the entire Polymer resource pack.

## 2. Goals and non-goals

### Goals

- Split the final Polymer-generated resource pack by `assets/<namespace>/`.
- Preserve vanilla-client compatibility.
- Reuse Polymer AutoHost instead of creating another HTTP stack.
- Give each namespace a stable identity and content-specific SHA-1.
- Reuse unchanged namespace ZIPs across generations and server restarts.
- Replace Polymer's monolithic main pack only when split generation is ready.
- Fall back to Polymer's normal main pack when splitting fails.
- Keep Minecraft/Polymer version differences isolated from common Java logic.

### Non-goals

- Custom HTTP hosting.
- Client-side PolymerSplitter code.
- File-type or dependency-graph splitting inside one namespace.
- Resource-pack obfuscation.
- Generic support for non-Polymer resource-pack generators.
- Automatic upload to Polymer AutoHost's external provider.

## 3. Repository structure

```text
common/
  lifecycle/
    SplitCoordinator
    SplitRegistry
    SplitState
  pack/
    PackSplitter
    SplitPack
    SplitterConfig
    PackHashUtil
    PackIdUtil
    SplitPackManifest
    SplitCacheIndex

versions/
  shared/                         shared Fabric initializer/config/resources
  legacy/                         1.21.x Polymer generation hook
  modern/                         26.x Polymer generation hook
  autohost-resource-location/     1.21.8-1.21.10 AutoHost adapter
  autohost-identifier-legacy/     1.21.11 AutoHost adapter
  autohost-identifier-modern/     26.x AutoHost adapter
  mixin-legacy/                   1.21.x main-pack suppression
  mixin-modern/                   26.x main-pack suppression
  commands-legacy/                1.21.8-1.21.10 commands
  commands-modern/                1.21.11+ commands
  mc-*/                           dependency/version wiring
```

The version modules include `common/src/main/java` directly in their source sets. The standalone `common` Gradle project exists for independent build validation; runtime version JARs do not require a separate common JAR.

## 4. Version matrix

| Minecraft compatibility | Compile target | Java | Polymer |
| --- | --- | ---: | --- |
| 1.21.8 | 1.21.8 | 21 | 0.13.13+1.21.8 |
| 1.21.9-1.21.10 | 1.21.10 | 21 | 0.14.4+1.21.10 |
| 1.21.11 | 1.21.11 | 21 | 0.15.2+1.21.11 |
| 26.1-26.1.2 | 26.1.2 | 25 | 0.16.5+26.1.2 |
| 26.2 | 26.2 | 25 | 0.17.5+26.2 |
| 26.3 build target | 26.3 | 25 | 0.18.2+26.3 |

The 26.3 Fabric metadata currently accepts `>=26.3`; only the configured build target is compile-validated by this repository.

## 5. Runtime data flow

```text
Polymer resource-pack generation
        |
        v
PolymerGenerationHook
        |
        v
SplitCoordinator
        |
        +--> SHA-1 of full Polymer output
        |
        +--> load previous SplitCacheIndex
        |
        v
PackSplitter
        |
        +--> read pack.mcmeta / optional pack.png
        +--> group assets by namespace
        +--> SHA-256 namespace fingerprint
        +--> reuse unchanged ZIP or build deterministic ZIP
        +--> SHA-1 final ZIP
        +--> write manifest.json
        |
        v
PolymerAutoHostBridge.registerHostedPacks
        |
        v
SplitRegistry.replace(...)
        |
        v
state = READY
        |
        v
SEND_RESOURCE_PACK_COLLECTOR
        |
        +--> ServerResourcePackInfo per namespace
        |
        v
AbstractProviderMixin removes original Polymer main pack
        |
        v
Polymer AutoHost / AutoHostTask
        |
        v
Vanilla client
```

## 6. Lifecycle and publication

`SplitCoordinator` owns the split lifecycle:

```text
NOT_STARTED
    |
    v
GENERATING
    |
    +--> success --> READY
    |
    +--> failure --> FAILED
```

A generation is published in this order:

1. Validate the generated Polymer ZIP.
2. Calculate its SHA-1.
3. Build or reuse all namespace packs.
4. Register the resulting files with Polymer AutoHost.
5. Atomically replace the in-memory `SplitRegistry` list.
6. Mark the coordinator `READY`.
7. Persist cache metadata and opportunistically clean old generations.

If steps 1-4 fail, the previous registry remains intact and the coordinator becomes `FAILED`. Main-pack suppression is active only while the coordinator is `READY` and the registry is non-empty.

The registry swap is atomic. Polymer AutoHost's internal hosted-file map is not an atomic multi-entry transaction; see Known limitations.

## 7. Split format

Given:

```text
pack.mcmeta
pack.png
assets/
  minecraft/**
  polymer/**
  example/**
```

the splitter produces:

```text
minecraft.zip
  pack.mcmeta
  pack.png
  assets/minecraft/**

polymer.zip
  pack.mcmeta
  pack.png
  assets/polymer/**

example.zip
  pack.mcmeta
  pack.png
  assets/example/**
```

`assets/.mcassetsroot` is assigned to the `minecraft` pack.

All other root entries are currently ignored except `pack.mcmeta` and optional `pack.png`.

## 8. Pack identity

Each `SplitPack` contains:

- namespace
- output path
- SHA-256 source fingerprint
- SHA-1 final ZIP hash
- UUID
- file size

### Stable namespace UUID

Non-primary namespaces use a deterministic UUID derived from:

```text
polymersplitter:<namespace>
```

The UUID remains stable when content changes.

### Polymer main UUID

The primary split pack uses `PolymerResourcePackUtils.getMainUuid()`.

Primary selection:

1. `minecraft`, if present.
2. Otherwise the first split pack.

This preserves Polymer code paths that identify the loaded main resource pack by Polymer's main UUID.

### SHA-1

The final generated or reused ZIP SHA-1 is passed to Polymer's resource-pack metadata. It changes only when the actual split ZIP changes.

## 9. Deterministic ZIP generation

For cache stability, namespace ZIP generation:

- sorts ZIP entries by path,
- uses a fixed 1980-01-01 ZIP timestamp when deterministic mode is enabled,
- uses the same root metadata for each namespace,
- writes to a temporary file,
- replaces the target with an atomic move when supported.

Deterministic output is intended to avoid hash churn from filesystem timestamps and traversal order.

## 10. Cache model

Persistent cache metadata lives under:

```text
config/polymersplitter/generated/current-cache.tsv
```

Generation data lives under:

```text
config/polymersplitter/generated/generation-<source-sha1>/
```

### Namespace fingerprint

The SHA-256 fingerprint includes:

- `pack.mcmeta` path and bytes,
- optional `pack.png` path and bytes,
- every selected namespace entry path and bytes.

A shared metadata change therefore invalidates all namespace fingerprints.

### Reuse

If namespace and fingerprint match a previous cached pack and the previous file still has the expected size:

1. Reuse its SHA-1 and size.
2. Materialize it into the new generation using a hard link.
3. Fall back to a file copy if hard linking is unavailable.

### Retention

After successful publication, the cache keeps the current generation and the immediately previous generation. Older `generation-<sha1>` directories are removed on a best-effort basis.

Cache-index read/write/cleanup failures do not invalidate an otherwise valid split generation.

## 11. Polymer AutoHost integration

Each namespace is registered under a stable hosted identifier:

```text
polymersplitter:packs/<namespace>
```

The AutoHost adapter calls:

- `AutoHostUtils.registerHostedFile(...)`
- `ResourcePackDataProvider.createProperties(...)`
- provider URL generation with the split SHA-1

This preserves Polymer's configured hosting mode, prompt, required-pack behavior, and resource-pack response handling.

The mod does not send resource-pack packets directly.

### Supported hosting expectation

Polymer's local hosting modes such as same-port/Netty and standalone HTTP can resolve files registered by PolymerSplitter.

Polymer's `external` provider only constructs external URLs. PolymerSplitter does not upload generated split ZIPs to an external service, so operators using that provider must arrange for those files to be served externally.

## 12. Main-pack replacement

Polymer normally exposes its monolithic generated resource pack.

PolymerSplitter adds namespace packs to `SEND_RESOURCE_PACK_COLLECTOR`, then a minimal version-specific Mixin intercepts the final property collection and removes only the original Polymer main pack.

The removal check requires both:

- Polymer's main pack UUID, and
- a URL containing Polymer AutoHost's default main-pack path.

This avoids removing the primary split pack, which intentionally reuses Polymer's main UUID, and avoids removing unrelated external/global resource packs.

Suppression is disabled unless:

```text
enabled
AND coordinator.state == READY
AND split registry is not empty
```

Therefore failed or incomplete split generation falls back to Polymer's main pack.

## 13. Readiness

For 26.x, PolymerSplitter also contributes to `RESOURCE_PACKS_READY` and reports not-ready while split generation is in `GENERATING`.

For legacy 1.21.x, Polymer's provider has its own generation-ready state. The split collector emits nothing unless the split coordinator is `READY`; fallback main-pack behavior remains available otherwise.

## 14. Configuration

`config/polymersplitter.json` currently exposes:

```json
{
  "enabled": true,
  "splitMode": "namespace",
  "copyPackIcon": true,
  "deterministicZip": true,
  "logPackSizes": true
}
```

Only `namespace` split mode is supported.

When disabled, PolymerSplitter does not register its split collector/generation hook, and Polymer AutoHost keeps its normal behavior.

## 15. Commands

Administrator commands:

```text
/polymersplitter status
/polymersplitter list
/polymersplitter rebuild
```

`rebuild` invokes Polymer's resource-pack generation API. The normal generation-finished hook then runs the same split/cache/publish pipeline; there is no separate rebuild implementation.

## 16. Version adaptation rules

Version-specific code exists only where upstream APIs differ.

### 1.21.x generation

`versions/legacy` handles Runnable-style resource-pack completion events and resolves Polymer's generated server ZIP path.

### 26.x generation

`versions/modern` consumes `OutputGenerator.Result`, including Polymer's `hadIssues()` signal.

### AutoHost identifier/context APIs

- 1.21.8-1.21.10: `ResourceLocation`-based adapter.
- 1.21.11: `Identifier` with PacketTweaker `PacketContext`.
- 26.x: `Identifier` with Fabric networking `PacketContext`.

### Mixins

Mixins exist only to suppress Polymer's original main pack. Do not move general integration logic into Mixins.

## 17. Failure semantics

The desired failure mode is degradation to normal Polymer behavior, not partial split delivery.

- Invalid/missing Polymer output: split generation fails.
- Empty namespace set: split generation fails.
- Namespace ZIP failure: whole new generation is not published.
- AutoHost registration failure: whole new registry is not published.
- Cache metadata failure: log/cache degradation only; valid packs remain usable.
- Old generation cleanup failure: ignored for delivery purposes.
- Polymer reports output issues on modern versions: split generation is marked failed.

## 18. Known limitations

### Namespace granularity

Many Polymer mods contribute resources to `assets/minecraft`. As a result, `minecraft.zip` can remain large even when other namespaces split efficiently.

### Resource-pack overlays and extra root content

The current splitter copies `pack.mcmeta` and optionally `pack.png`, and handles `assets/.mcassetsroot`. Other root-level resource-pack content and overlay-directory semantics are not currently redistributed into namespace packs.

### AutoHost hosted-path remapping

Polymer AutoHost strips its optional `+<hash>.zip` suffix before resolving the registered hosted identifier. PolymerSplitter reuses a stable identifier per namespace.

During an online rebuild, registering a new generation remaps that identifier before the in-memory split registry is swapped. Consequently, an old in-flight URL can resolve to the newly registered file. Keeping the previous generation on disk does not provide versioned URL routing by itself.

A future solution would require content-addressed hosted identifiers or an upstream AutoHost API that can atomically/versionedly register multiple paths.

### External provider

The external provider is URL construction, not file upload. External deployment requires an operator-managed publication mechanism.

## 19. Build validation

The repository intentionally uses build-only validation.

CI builds these targets independently:

```text
common
1.21.8
1.21.9-1.21.10
1.21.11
26.1
26.2
26.3
```

No Minecraft runtime, server, functional, integration, or gameplay tests are part of the repository validation policy unless explicitly requested.
