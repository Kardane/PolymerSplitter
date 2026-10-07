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
  hosting/
    HostingStatus
    PackPushResult
  lifecycle/
    SplitCoordinator
    CoordinatorSnapshot
    SplitGeneration
    NamespaceTransition
    SplitRegistry
    SplitState
  pack/
    PackSplitter
    PackMetadata
    SplitPack
    SplitterConfig
    PackIdUtil
    HostedPackStore
    LegacyCacheMigration
    SplitRecovery
    SplitCacheIndex

versions/
  shared/                         initializer/config + shared delivery/commands/publication
  legacy/                         legacy Polymer generation-event/path adapter
  modern/                         modern Polymer generation-event/result adapter
  autohost-legacy/                PacketTweaker adapter + isolated AutoHost internal shim
  autohost-modern/                Fabric PacketContext adapter + isolated AutoHost internal shim
  pack-id-resource-location/      ResourceLocation hosted-ID adapter
  pack-id-identifier/             Identifier hosted-ID adapter
  mixin-legacy/                   1.21.x main-pack suppression
  mixin-modern/                   26.x main-pack suppression
  commands-legacy/                legacy permission predicate
  commands-modern/                modern permission predicate
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
SplitPublisher (shared)
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
        +--> parse pack.mcmeta overlay directories
        +--> classify shared root files
        +--> group base + overlay assets by namespace
        +--> SHA-256 namespace fingerprint
        +--> reuse unchanged ZIP or build deterministic ZIP
        +--> SHA-1 final ZIP
        +--> write manifest.json
        |
        v
HostedPackStore
        |
        +--> verify final ZIP SHA-1
        +--> hard-link/copy immutable hosted/<sha1>.zip
        |
        v
PolymerAutoHostBridge.registerHostedPacks
        |
        v
CoordinatorSnapshot atomic publish
        |
        +--> state = READY
        +--> generation = SplitGeneration
        +--> lastFailure = null
        +--> NamespaceTransition
        |
        v
SplitRegistry read-only view
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

`SplitCoordinator` owns one atomic `CoordinatorSnapshot` containing lifecycle state, active `SplitGeneration`, last failure, and the last namespace transition. `SplitRegistry` has no independent mutable state; it is a read-only view over the same snapshot.

The split lifecycle is:

```text
NOT_STARTED
    |
    +--> validated startup cache --> READY
    |
    +--> invalid startup cache --> FAILED
    |
    v
GENERATING
    |
    +--> success --> READY
    |
    +--> failure --> FAILED
```

At server start, after Polymer AutoHost has loaded its actual configuration/provider, PolymerSplitter classifies the provider. Startup cache restore runs only for explicitly supported local providers. It removes owned temporary files, reads `index.json`, requires an exact output-compatibility match, verifies every referenced immutable hosted blob, registers content-addressed AutoHost IDs, and only then publishes the recovered snapshot as `READY`. Missing or output-incompatible cache metadata is a cache miss and does not become active. Integrity failure in a compatible cache prevents cached publication and leaves Polymer's main pack as the fallback.

A newly generated resource-pack generation is published in this order:

1. Validate the generated Polymer ZIP.
2. Calculate its SHA-1.
3. Build or reuse all namespace packs.
4. Materialize every final ZIP into the immutable content-addressed hosted blob store.
5. Register content-addressed hosted identifiers with Polymer AutoHost.
6. Atomically commit `index.json`, which points only at immutable `hosted/<sha1>.zip` blobs.
7. Atomically publish one `CoordinatorSnapshot` containing `READY`, the new `SplitGeneration`, cleared failure state, and the computed namespace transition.
8. Best-effort cleanup may remove legacy TSV/generation artifacts, but runtime historical hosted blobs are retained.

If any publication step before the atomic snapshot update fails, the previous generation remains in the snapshot and the coordinator becomes `FAILED`. Content-addressed AutoHost IDs that were registered before a later failure are unadvertised immutable entries and cannot remap an already issued URL. Main-pack suppression is active only while the coordinator snapshot is `READY` with a non-null generation.

The active `SplitGeneration` contains the source hash, deterministic pack order, and exact namespace map. It is stored inside the same atomic `CoordinatorSnapshot` as the lifecycle state, so readers cannot observe a READY state paired with a different registry generation. The primary `minecraft` pack is ordered first when present; remaining namespaces are lexicographic.

Before the coordinator advances, every current namespace already resolves to an immutable content-addressed blob and `index.json` is atomically committed. An index write failure therefore prevents the new generation from becoming `READY`. No persistent generation-directory copy or manifest is created.

`NamespaceTransition` compares the previous and next snapshots and records added, removed, content-changed, and unchanged namespaces. Removed namespaces disappear from the next collector immediately because delivery reads only the current snapshot.

Hosted IDs include the content SHA-1, so partial registration before a failed publication can only add unadvertised immutable IDs; it cannot remap an already advertised URL to different bytes.

## 7. Split format

Given:

```text
pack.mcmeta
pack.png
LICENSE.txt
assets/
  minecraft/**
  polymer/**
overlay_legacy/
  assets/
    minecraft/**
    polymer/**
```

and an `overlays.entries[].directory` value of `overlay_legacy`, the splitter produces:

```text
minecraft.zip
  pack.mcmeta
  pack.png
  LICENSE.txt
  assets/minecraft/**
  overlay_legacy/assets/minecraft/**

polymer.zip
  pack.mcmeta
  pack.png
  LICENSE.txt
  assets/polymer/**
  overlay_legacy/assets/polymer/**
```

The original `pack.mcmeta` bytes are copied unchanged, so Minecraft remains responsible for deciding which overlay ranges apply to the client version. PolymerSplitter only discovers the declared overlay directories and preserves their namespace resources.

`assets/.mcassetsroot`, including the same path inside a declared overlay, is assigned to the `minecraft` pack.

Root-level files other than `pack.mcmeta` and `pack.png` are copied into every split pack. Files inside undeclared root directories, or unsupported content inside a declared overlay directory, cause the split generation to fail so Polymer's original main pack remains the fallback.

`pack.mcmeta` and `pack.png` located inside an overlay directory are omitted because Minecraft ignores those files inside overlays.

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

## 10. Cache and storage model

The persistent storage root is:

```text
config/polymersplitter/generated/
  index.json
  hosted/
    <sha1>.zip
```

`hosted/<sha1>.zip` is the single persistent copy of every split ZIP. `SplitPack.path()` always points to this content-addressed location.

`index.json` format 3 is the durable pointer to the current generation. It stores the source pack SHA-1, explicit output-compatibility metadata, and for each namespace its fingerprint, final ZIP SHA-1, deterministic UUID, and size. Paths are not stored; they are derived from the SHA-1.

Output compatibility is intentionally separate from the JSON schema version:

```text
algorithmVersion
copyPackIcon
deterministicZip
```

`logPackSizes` is diagnostic only and does not affect output compatibility. The algorithm version is bumped when split/fingerprint/output semantics change in a way that makes old blobs unsafe to reuse.

### Namespace fingerprint

The SHA-256 fingerprint includes:

- a fingerprint schema/version salt,
- `pack.mcmeta` path and bytes,
- optional `pack.png` path and bytes,
- every shared root-level file path and bytes,
- every selected base or overlay namespace entry path and bytes.

A shared metadata change therefore invalidates all namespace fingerprints.

### Whole-source unchanged fast path

After Polymer finishes generation, the coordinator always computes the final source ZIP SHA-1. Before opening the ZIP for namespace discovery, it loads the previous cache through the same output-compatibility gate used by startup restore and namespace reuse.

The entire split stage is skipped only when all of the following are true:

```text
sourceSha1 matches
AND output compatibility matches
AND index metadata is valid
AND every referenced hosted blob passes size/SHA-1 verification
```

On a hit, the cached `SplitGeneration` is reconstructed from the verified packs, the immutable hosted files are registered again with AutoHost, and one atomic `CoordinatorSnapshot` is published as `READY`. `PackSplitter.split()`, namespace enumeration, namespace fingerprints, ZIP writes, and `index.json` rewrite are all skipped.

A missing, incompatible, malformed, or unverifiable cache is a fast-path miss. Normal split generation continues; source hash equality by itself is never sufficient.

### New generation

For each namespace:

1. Read the previous index only if its output-compatibility key exactly matches the current splitter configuration/algorithm.
2. Verify every referenced blob before it becomes a reuse candidate.
3. Compute the namespace fingerprint.
4. If the compatible verified cache has the same fingerprint, reuse the existing immutable blob directly; no hard link or copy is created.
5. Otherwise write one temporary ZIP inside `hosted/`.
6. Compute the final ZIP SHA-1.
7. Atomically move the temporary ZIP to `hosted/<sha1>.zip`.
8. Register the content-addressed hosted ID.
9. After all namespaces are ready, atomically replace `index.json` with the current compatibility key.

A failed publication can leave an unreferenced immutable blob, but cannot remap an issued URL or advance the current index. A whole-source fast-path hit creates no new blob and does not rewrite the index.

### Startup read and verification

Normal format-3 startup is deliberately read-only until publication:

1. `SplitCacheIndex.read(...)` parses and validates `index.json` metadata without mutating files and compares its output-compatibility key with the current key.
2. An incompatible cache is treated as a reuse/restore miss; its hosted blobs are preserved and are not relabeled as current output.
3. Only a compatible cache reaches `SplitCacheIndex.verify(...)`, which verifies every referenced hosted blob's size and SHA-1.
4. Only after the entire compatible index verifies are the blobs registered with AutoHost and the coordinator snapshot restored.

There is no normal cache-repair phase because the content-addressed blob is the canonical file. A missing/corrupted referenced blob invalidates the cached generation and falls back to Polymer's normal main pack.

### One-time legacy migration

If format-3 `index.json` is absent but legacy `current-cache.tsv` exists, `LegacyCacheMigration` may validate and promote legacy generation ZIPs into immutable `hosted/<sha1>.zip` blobs. Legacy metadata has no output-compatibility key, so it is never rewritten as a current compatible index and cannot restore or reuse a generation by itself.

Likewise, the previous format-2 `index.json` is readable for explicit incompatibility classification but lacks output compatibility and is therefore a cache miss. Hosted blobs are preserved. A current format-3 index is written only after a successful normal generation under the current output compatibility.

### Retention and garbage collection

Historical content-addressed blobs are retained for the entire running server so already-issued URLs remain valid. They are not garbage-collected during rebuilds.

After a later server start successfully restores the current `index.json`, blobs not referenced by that index are safe to remove because prior-process AutoHost mappings have been cleared. Cleanup is best-effort and never changes the active index.

Interrupted temporary files are removed on startup. Legacy generation directories and `current-cache.tsv` are cleanup-only artifacts after format-2 index commit.


## 11. Polymer AutoHost integration

Each namespace generation is registered under a content-addressed hosted identifier:

```text
polymersplitter:packs/<namespace>/<sha1>
```

The Minecraft resource-pack UUID remains namespace-stable; only the hosted identifier changes when ZIP content changes. This separates client pack identity from immutable content routing.

Before registration, `HostedPackStore` resolves each current pack to its canonical `hosted/<sha1>.zip` path. New blobs were already atomically committed by `PackSplitter`; verified cached blobs are reused in place.

The AutoHost adapter calls:

- `AutoHostUtils.registerHostedFile(...)`
- `ResourcePackDataProvider.createProperties(...)`
- provider URL generation with the split SHA-1

This preserves Polymer's configured hosting mode, prompt, required-pack behavior, and resource-pack response handling.

Automatic delivery stays inside Polymer AutoHost's collector/task flow. The explicit `/polymersplitter send` command is the exception: its version adapter sends Minecraft's resource-pack push packet using properties produced by the active AutoHost provider.

### Hosting compatibility gate

Split delivery is enabled only for Polymer's built-in local providers:

- `polymer:automatic`
- `polymer:auto`
- `polymer:netty`
- `polymer:same_port`
- `polymer:http_server`
- `polymer:standalone`

The following configurations are intentionally unsupported for split delivery:

- AutoHost disabled,
- `polymer:external`,
- `polymer:empty`,
- unknown/custom provider types.

The `external` provider only constructs URLs; PolymerSplitter has no upload mechanism for externally hosted split ZIPs. Unknown/custom providers are rejected conservatively because the AutoHost API does not guarantee that an arbitrary provider serves files registered through `registerHostedFile(...)`.

The compatibility gate is evaluated when publishing split packs, collecting resource packs, suppressing Polymer's main pack, and reporting command status. If configuration becomes unsupported, PolymerSplitter stops advertising split packs and stops suppressing the original main pack.

Provider-specific readiness remains owned by Polymer AutoHost. PolymerSplitter does not duplicate AutoHost's per-connection readiness checks.

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
AND coordinator.snapshot.state == READY
AND coordinator.snapshot.generation != null
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
/polymersplitter send <targets> all
/polymersplitter send <targets> namespace <namespace>
```

`rebuild` invokes Polymer's resource-pack generation API; the normal generation-finished hook performs split/cache/publication.

### Targeted resource-pack push

The `send` command is a delivery operation only. It requires an active `READY` `SplitGeneration` and a Phase 13-supported local AutoHost provider.

`targets` is Minecraft's standard player selector argument, so one player or multiple players can be selected through vanilla selectors. `all` selects every pack from the current generation in its deterministic order; `namespace <name>` selects exactly one current namespace.

The version-specific AutoHost bridge:

1. resolves the active AutoHost provider and per-player packet context,
2. checks provider readiness,
3. calls `ResourcePackDataProvider.createProperties(...)` with the same effective UUID/content SHA-1 used by normal split delivery,
4. sends Minecraft's resource-pack push packet to each ready target.

Manual push deliberately preserves the generated pack's `isRequired` and prompt fields. It does not force `required=true`, bypass AutoHost policy, regenerate files, or include Polymer global/external packs.

For a primary namespace such as `minecraft`, manual push uses Polymer's main UUID. Other namespaces keep their deterministic namespace UUID, so re-pushing a pack addresses the same client-side resource-pack identity as automatic delivery.

The command reports targeted players, ready players, selected packs, and packet count. Players for whom the provider is not ready are skipped rather than receiving a guessed/unusable URL.


## 16. Version adaptation rules

Version-specific source now contains only actual upstream API boundaries. Shared orchestration lives in `versions/shared`.

### Shared orchestration

- `PolymerAutoHostBridge`: hosting gate, immutable hosted-file materialization, pack selection, and public bridge API.
- `HostingPolicy`: provider-type classification.
- `SplitDelivery`: primary pack selection, effective UUIDs, targeted pack selection, and push accounting.
- `SplitPublisher`: generation publication and logging.
- `PolymerSplitterCommands`: the complete command tree and handlers.

### Generation adapters

`versions/legacy/PolymerGenerationHook` handles Runnable-style completion events. Its unavoidable internal rebuild trigger and legacy generated-output-path fallback are isolated in `PolymerResourcePackInternals`.

`versions/modern/PolymerGenerationHook` handles `OutputGenerator.Result` and `hadIssues()`. Its unavoidable internal rebuild trigger is isolated in `PolymerResourcePackInternals`.

Both delegate actual split publication to shared `SplitPublisher`.

### AutoHost adapters

`versions/autohost-legacy/AutoHostAccess` is shared by 1.21.8 through 1.21.11 and owns PacketTweaker `PacketContext`, legacy provider readiness, collector property creation, and packet push.

`versions/autohost-modern/AutoHostAccess` owns the corresponding 26.x Fabric networking `PacketContext` boundary plus `RESOURCE_PACKS_READY`.

Both delegate the two unavoidable implementation accesses—AutoHost config inspection and server-stop hosted-map cleanup—to a small `PolymerAutoHostInternals` shim. Normal delivery code does not import `eu.pb4.polymer.autohost.impl.*`.

Identifier construction is independent of the context boundary:

- 1.21.8-1.21.10: `pack-id-resource-location/PackIdentifier`.
- 1.21.11 and 26.x: `pack-id-identifier/PackIdentifier`.

### Command permission adapters

The command tree is shared. `versions/commands-legacy/CommandPermissions` uses the legacy permission-level API; `versions/commands-modern/CommandPermissions` uses the modern permission object API.

### Mixins

Mixins remain version-specific only for original Polymer main-pack suppression. Do not move general integration logic into Mixins.

### Polymer internal API boundary

The audited internal-API inventory and upgrade checklist live in [POLYMER_COMPATIBILITY.md](POLYMER_COMPATIBILITY.md).

The current direct implementation dependencies are intentionally limited to:

- `PolymerAutoHostInternals`: `AutoHost.config` and `AutoHost.FILES`, because the reviewed public AutoHost API has no provider-type/config accessor and no hosted-file unregister operation.
- `PolymerResourcePackInternals`: `PolymerResourcePackMod.generateAndCall(...)`, plus legacy `useMainPath`; `PolymerResourcePackMod` is explicitly marked `@ApiStatus.Internal` upstream.
- `AbstractProviderMixin`: the internal `AbstractProvider#getProperties(...)` target, because the public collector API can add packs but cannot remove/replace Polymer's original main pack.

Public `PolymerResourcePackUtils.buildMain(...)` was not substituted for `generateAndCall(...)`: it does not provide the same generation lock, async execution, messaging, and callback orchestration. Reflection and direct parsing of Polymer's config file are also intentionally avoided because they reduce compile-time safety or duplicate upstream behavior without removing runtime coupling.


## 17. Failure semantics

The desired failure mode is degradation to normal Polymer behavior, not partial split delivery.

- Invalid/missing Polymer output: split generation fails.
- Missing cache metadata or output-incompatible format-2/format-3 metadata: cached publication/reuse is skipped as a cache miss.
- Malformed current index metadata: cached publication is skipped.
- Any cached namespace with an invalid UUID, size, fingerprint, SHA-1, or missing/corrupted hosted blob invalidates the entire startup restore.
- Interrupted temporary files are cleaned on startup on a best-effort basis.
- Invalid legacy TSV/generation data is not partially migrated.
- Invalid `pack.mcmeta` overlay metadata: split generation fails.
- Unsupported root/overlay directory content: split generation fails.
- Empty namespace set: split generation fails.
- Namespace ZIP failure: whole new generation is not published.
- AutoHost registration failure: whole new registry is not published.
- Disabled, external, empty, or unknown/custom AutoHost provider: split publication is blocked and the original Polymer delivery path is not suppressed.
- `index.json` write failure during publication: the new generation is not published and the coordinator becomes `FAILED`.
- Compatible-cache integrity verification failure during startup restore: cached publication is skipped and Polymer's main pack remains the fallback.
- Compatible-cache verification failure during a generated-pack rebuild: whole-source/namespace reuse is skipped and normal splitting continues.
- Legacy metadata/blob import failure is a cache miss; current generation can still proceed without reuse.
- Legacy-artifact or unreferenced-blob cleanup failure: ignored for delivery purposes.
- Polymer reports output issues on modern versions: split generation is marked failed.

## 18. Known limitations

### Namespace granularity

Many Polymer mods contribute resources to `assets/minecraft`. As a result, `minecraft.zip` can remain large even when other namespaces split efficiently.

### Conservative handling of unknown pack directories

Declared resource-pack overlays are preserved and routed by namespace, and ordinary root-level files are copied to every split pack. Unknown root directories or unsupported files inside an overlay are intentionally rejected instead of guessed. This can cause a valid-but-unrecognized future pack layout to fall back to Polymer's original main pack until explicit support is added.

### Namespace and hosted-entry retirement

Within a running server, historical content-addressed AutoHost mappings and hosted blobs are retained so an already issued URL continues to resolve to the same bytes. They are not part of the active namespace set: collectors read only the current `SplitGeneration`.

When the Minecraft server fully stops, PolymerSplitter removes only its own `polymersplitter/packs/*` entries from AutoHost's in-memory hosted-file map and resets its in-memory coordinator/registry to `NOT_STARTED`. `index.json` and immutable blobs remain available for validated recovery on the next server start, including same-JVM restarts.

After a successful later startup restore, unreferenced hosted blobs from prior processes are garbage-collected on a best-effort basis. Runtime rebuilds never perform this GC.

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
