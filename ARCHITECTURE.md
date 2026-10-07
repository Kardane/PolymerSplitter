# PolymerSplitter Architecture

## 1. Overview & Core Invariants

PolymerSplitter is a server-only Fabric companion mod for [Polymer](https://github.com/Patbox/polymer). It intercepts Polymer's generated resource pack, splits it by resource namespace into independently cacheable packs, and delegates hosting, delivery, prompt configuration, and client tracking to Polymer AutoHost.

When only one namespace changes, clients only re-download that specific namespace pack instead of the entire monolithic pack.

### Hard Invariants

- **Server-Only:** Requires no client-side mod; compatible with vanilla Minecraft clients.
- **Polymer AutoHost Delegation:** AutoHost owns HTTP serving, packet pushing, prompt policies, and response tracking. No custom HTTP stack is introduced.
- **Single Canonical Storage:** `config/polymersplitter/generated/hosted/<sha1>.zip` is the sole persistent store for all split packs.
- **Atomic Runtime State:** `SplitCoordinator` manages an immutable `CoordinatorSnapshot` (state, active generation, failure, namespace transitions). `SplitRegistry` is a strictly read-only view.
- **Fail-Safe Degradation:** Any generation, cache, or provider failure immediately falls back to Polymer's original monolithic main pack. Partial split delivery is strictly prohibited.
- **Decoupled Common Logic:** `common` contains pure Java logic and never references Minecraft or Polymer types.

---

## 2. Module Layout & Version Matrix

### Directory Layout

```text
common/                 Platform-agnostic splitting, identity, snapshots, cache index, and hosted store.
versions/
  shared/               Fabric entrypoint, config loading, delivery orchestration, commands, suppression helper.
  legacy/               Polymer generation hook adapter for 1.21.x (Runnable-based completion).
  modern/               Polymer generation hook adapter for 26.x (OutputGenerator.Result).
  autohost-legacy/      PacketTweaker PacketContext and legacy provider readiness.
  autohost-modern/      Fabric networking PacketContext and RESOURCE_PACKS_READY integration.
  pack-id-resource-location/  ResourceLocation ID adapter (1.21.8-1.21.10).
  pack-id-identifier/         Identifier ID adapter (1.21.11, 26.x).
  mixin-legacy/         AbstractProvider#getProperties injection for 1.21.x.
  mixin-modern/         AbstractProvider#getProperties injection for 26.x.
  commands-legacy/      Legacy permission-level predicate adapter.
  commands-modern/      Modern permission object adapter.
  mc-*/                 Per-version Gradle build definitions and dependency wiring.
```

### Version Matrix

| Minecraft Target | Compile Target | Java | Polymer Branch | Generation Hook | AutoHost Context | Pack ID | Permissions |
| --- | --- | ---: | --- | --- | --- | --- | --- |
| 1.21.8 | 1.21.8 | 21 | `dev/1.21.6` | Legacy | PacketTweaker | ResourceLocation | Legacy |
| 1.21.9-1.21.10 | 1.21.10 | 21 | `dev/1.21.9` | Legacy | PacketTweaker | ResourceLocation | Legacy |
| 1.21.11 | 1.21.11 | 21 | `dev/1.21.11` | Legacy | PacketTweaker | Identifier | Modern |
| 26.1-26.1.2 | 26.1.2 | 25 | `dev/26.1` | Modern | Fabric PacketContext | Identifier | Modern |
| 26.2 | 26.2 | 25 | `dev/26.2` | Modern | Fabric PacketContext | Identifier | Modern |
| 26.3+ | 26.3 | 25 | `dev/26.3` | Modern | Fabric PacketContext | Identifier | Modern |

---

## 3. Runtime Flow & Lifecycle

### Pipeline Diagram

```text
Polymer Pack Generation
          │
          ▼
PolymerGenerationHook (legacy / modern)
          │
          ▼
SplitPublisher (shared)
          │
          ▼
SplitCoordinator
  ├── 1. Validate generated Polymer ZIP & compute source SHA-1
  ├── 2. Evaluate AutoHost provider compatibility gate
  │
  ├── [FAST PATH: Unchanged source + valid output compatibility + verified blobs]
  │     ├── Reconstruct SplitGeneration from cache
  │     ├── Re-register content-addressed IDs with AutoHost
  │     └── Publish CoordinatorSnapshot (READY)
  │
  └── [NORMAL PATH: Changed source / cache miss]
        ├── PackSplitter scans entries, overlays, and root metadata
        ├── Group assets into namespace candidates + minecraft.sounds
        ├── Apply policy (include/exclude) and size threshold (minSplitPackSizeMb)
        ├── Compute SHA-256 namespace fingerprints
        ├── Stream changed ZIPs to hosted/<sha1>.zip (computing SHA-1 in-stream)
        ├── Register content-addressed IDs with AutoHost
        ├── Atomically commit index.json (format 3)
        └── Publish CoordinatorSnapshot (READY)
          │
          ▼
SplitRegistry (Read-only view)
          │
          ▼
SEND_RESOURCE_PACK_COLLECTOR  ──► Adds split packs (ServerResourcePackInfo)
          │
          ▼
AbstractProviderMixin         ──► Suppresses original monolithic pack via MainPackSuppression
          │
          ▼
Vanilla Client Delivery
```

### Lifecycle States

- `NOT_STARTED`: Initial state. Transitions to `READY` if a compatible startup cache verifies; remains `NOT_STARTED` on cache miss; transitions to `FAILED` on corrupt cache.
- `GENERATING`: Pack splitting and publication are in progress. For 26.x, `RESOURCE_PACKS_READY` reports not-ready.
- `READY`: Generation succeeded, all blobs are verified, and AutoHost IDs are registered. Main-pack suppression is active only in this state.
- `FAILED`: Generation or publication encountered an error. Split delivery is disabled and Polymer's original monolithic pack is served.

---

## 4. Pack Splitting & Identity Model

### Namespace Partitioning

- **Base & Declared Overlays:** Assets under `assets/<namespace>/**` and declared overlay directories (`overlays.entries[].directory`) are grouped by namespace.
- **Primary Pack:** Holds all safe files outside resource namespaces (`pack.mcmeta`, licenses, undeclared overlay directories, `assets/icon.png`, `assets/.mcassetsroot`). Assigned to `minecraft` if present, otherwise the first namespace alphabetically.
- **Shared Metadata:** Original `pack.mcmeta` bytes are preserved in every pack (delegating version-range matching to vanilla Minecraft). `pack.png` is duplicated across all packs if `copyPackIcon` is enabled.
- **Empty / Unsafe Entries:** Entries with empty names are omitted with a warning; unsafe paths abort generation.

### `minecraft.sounds` Secondary Split

To isolate large audio payloads:
- OGG files under `assets/minecraft/sounds/**/*.ogg` (including declared overlays) are split into a synthetic physical pack keyed `minecraft.sounds`.
- `sounds.json` and all other `minecraft` assets remain in the primary `minecraft` pack.
- **Policy & Size Inheritance:** `minecraft.sounds` matches both `minecraft.sounds` and `minecraft` include/exclude filters (exclude wins). `minSplitPackSizeMb` applies normally; undersized sound packs merge back into the primary pack.
- **Collision Protection:** If the source pack contains an authentic resource namespace named `minecraft.sounds`, the secondary split is skipped and OGG files remain in `minecraft`.

### Identity & UUIDs

- **Primary Pack:** Uses Polymer's main UUID (`PolymerResourcePackUtils.getMainUuid()`) to maintain compatibility with mods tracking Polymer's main pack.
- **Non-Primary Packs:** Deterministic UUID derived from `UUID.nameUUIDFromBytes("polymersplitter:<namespace>".getBytes(UTF_8))`.
- **Content Hash:** Final ZIP SHA-1 passed to AutoHost metadata.

### Deterministic ZIP Generation

To prevent hash churn across rebuilds:
- Entries are sorted lexicographically by path.
- Entry timestamps are pinned to 1980-01-01 00:00:00 when `deterministicZip` is enabled.
- Bytes are written with a single reusable 64 KiB buffer per split operation.

---

## 5. Storage, Cache & Garbage Collection

### Directory Structure

```text
config/polymersplitter/generated/
  index.json          Durable format-3 index of the current generation.
  hosted/             Content-addressed storage.
    <sha1>.zip        Immutable split pack files.
```

### Format-3 `index.json` & Output Compatibility

`index.json` stores the source pack SHA-1, output-compatibility metadata, and per-namespace descriptors (fingerprint, final ZIP SHA-1, UUID, file size).

Cache reuse requires an exact match on all output-affecting settings:
```text
algorithmVersion == 3
copyPackIcon
deterministicZip
minSplitPackSizeMb
compressionLevel
includeNamespaces
excludeNamespaces
```

### Namespace Fingerprints

Fingerprints are SHA-256 hashes computed over:
1. Fingerprint schema salt.
2. `pack.mcmeta` path and bytes.
3. `pack.png` path and bytes (if configured).
4. Auxiliary/root files (for the primary namespace only).
5. All entry paths and bytes assigned to the namespace.

### Small Pack Merging (`minSplitPackSizeMb`)

Non-primary candidates with final compressed size at or below `minSplitPackSizeMb` are merged into the primary pack. Merged namespaces are removed from active pack suggestions and the send command; their resources are pushed via the primary pack.

### Blob Retention & Safe Garbage Collection

- **Runtime Invariant:** Historical content-addressed blobs are never deleted while the server is running, ensuring issued client URLs remain valid.
- **Startup Safe Point:** Unreferenced blobs are cleaned up only after a successful startup cache restore.
- **Retention Policy:** `unreferencedBlobRetentionDays` controls cleanup: `0` deletes immediately, `>0` retains blobs newer than N days, `-1` disables cleanup.

---

## 6. AutoHost Integration & Main-Pack Suppression

### Hosting Compatibility Gate

Split delivery requires an explicitly supported local AutoHost provider:
- Supported: `polymer:automatic`, `polymer:auto`, `polymer:netty`, `polymer:same_port`, `polymer:http_server`, `polymer:standalone`.
- Unsupported: `polymer:external`, `polymer:empty`, disabled, or custom providers.

When unsupported, split delivery is disabled and Polymer's original monolithic pack is delivered unsuppressed.

### Hosted Registration

Each split pack is registered with AutoHost under a content-addressed identifier:
```text
polymersplitter:packs/<namespace>/<sha1>
```

### Main-Pack Suppression

Polymer exposes its pack via `AbstractProvider#getProperties(...)`. Minimal version-specific Mixins intercept this call and invoke `MainPackSuppression`, which filters out only the original monolithic pack by verifying:
1. Pack UUID matches Polymer's main UUID.
2. URL contains AutoHost's default main-pack path.

Suppression is active **only** when the coordinator snapshot is `READY` with a valid generation.

### Targeted Push (`/polymersplitter send`)

Sends vanilla resource pack packets using properties created by the active AutoHost provider. Preserves Polymer's configured prompt and required status without re-triggering pack generation.

---

## 7. Version Adaptation & Audited Internal APIs

Shared orchestration lives in `versions/shared` (`PolymerAutoHostBridge`, `SplitDelivery`, `SplitPublisher`, `PolymerSplitterCommands`).

Direct upstream implementation imports are strictly restricted to audited shims in [POLYMER_COMPATIBILITY.md](POLYMER_COMPATIBILITY.md):

1. **AutoHost Configuration & Cleanup:** `PolymerAutoHostInternals` accesses `AutoHost.config` (to check provider type) and `AutoHost.FILES` (to clear hosted entries on server stop).
2. **Rebuild Orchestration:** `PolymerResourcePackInternals` invokes `PolymerResourcePackMod.generateAndCall(...)` and accesses legacy `useMainPath`.
3. **Main-Pack Suppression:** `AbstractProviderMixin` intercepts `AbstractProvider#getProperties(...)` to remove the monolithic pack.

---

## 8. Failure & Degradation Matrix

| Failure Condition | Split Action | Fallback Behavior |
| --- | --- | --- |
| Unsupported / disabled AutoHost provider | Bypasses split publication | Serves Polymer's original main pack |
| Invalid / missing Polymer generated ZIP | Aborts generation; marks `FAILED` | Serves Polymer's original main pack |
| Output issues reported by modern Polymer | Aborts generation; marks `FAILED` | Serves Polymer's original main pack |
| Malformed / missing `pack.mcmeta` | Aborts generation; marks `FAILED` | Serves Polymer's original main pack |
| Corrupt / unverified startup cache index | Skips cache restore; resets state | Serves Polymer's original main pack |
| Missing or corrupted hosted blob | Invalidates generation; marks `FAILED` | Serves Polymer's original main pack |
| AutoHost registration or ID failure | Rejects candidate generation; marks `FAILED` | Serves Polymer's original main pack |
| Atomic `index.json` write failure | Rejects publication; marks `FAILED` | Serves Polymer's original main pack |
| Output compatibility mismatch | Rejects cache; runs full split pipeline | Rebuilds split packs or falls back on error |
