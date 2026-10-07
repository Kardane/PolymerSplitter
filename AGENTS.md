# AGENTS.md

Repository instructions for coding agents. Keep this document concise; treat it as an operational map to the durable documentation rather than duplicating design specifications.

---

## 1. Documentation Map

Read the minimal relevant context before making changes:

1. [README.md](README.md) - User-facing behavior, commands, and supported versions.
2. [ARCHITECTURE.md](ARCHITECTURE.md) - Runtime flow, invariants, storage/cache model, and version boundaries.
3. [ROADMAP.md](ROADMAP.md) - Planned work and completed phase boundaries.
4. [POLYMER_COMPATIBILITY.md](POLYMER_COMPATIBILITY.md) - Audited public and internal Polymer API boundaries.
5. `versions/mc-*/build.gradle` - Target-specific Minecraft, Fabric, Polymer, Java, and adapter dependencies.

*Rule:* Update [ARCHITECTURE.md](ARCHITECTURE.md) in the same change whenever lifecycle, caching, identity, hosting, delivery, compatibility, or failure behavior changes. Keep [README.md](README.md) user-facing.

---

## 2. Hard Invariants

- **Server-Only:** Companion mod for Polymer on Fabric. Vanilla clients need no mod.
- **AutoHost Delegation:** Hosting and delivery belong strictly to Polymer AutoHost. Do not introduce a custom HTTP server. Split delivery is enabled only for supported local providers.
- **Granularity:** Split by resource namespace only. The sole secondary split is `minecraft.sounds` for OGG audio; do not broaden splitting by file type without explicit pack-stack safety rules.
- **Stable Identity:** The primary pack (`minecraft` or first alphabetically) uses Polymer's main UUID (`PolymerResourcePackUtils.getMainUuid()`). Non-primary packs and `minecraft.sounds` use deterministic UUIDs derived from their pack key.
- **In-Stream Hashing:** Newly written namespace ZIPs compute SHA-1 in-stream while emitting bytes. Do not add a second full-file hashing pass.
- **Immutable Content Routing:** Never remap an already issued content-addressed URL to different bytes.
- **Atomic Runtime State:** One `CoordinatorSnapshot` (state, active generation, failure, namespace transitions) represents the atomic runtime state. `SplitRegistry` is a read-only view.
- **Single Canonical Store:** `hosted/<sha1>.zip` is the only persistent split-ZIP store. Never reintroduce generation-directory copies.
- **Output-Compatibility Gate:** Cache reuse requires an exact match on: algorithm version + `copyPackIcon` + `deterministicZip` + `minSplitPackSizeMb` + `compressionLevel` + normalized namespace policy.
- **Merge-Only Policy:** Policy filters never discard resources. Undersized or excluded non-primary packs merge into the primary pack (exclude wins).
- **Safe Blob Retention:** Historical blobs remain valid while the server is running. Garbage collection runs only at a safe startup restore point.
- **Graceful Fallback:** Any generation, cache, or provider error must degrade cleanly to Polymer's original monolithic pack delivery, never partial split delivery.
- **Decoupled Common:** `common` must never reference Minecraft or Polymer types.

---

## 3. Version Boundaries & Repository Map

| Target | Java | Generation Hook | AutoHost Adapter | Pack ID Adapter | Permissions |
| --- | ---: | --- | --- | --- | --- |
| 1.21.8 | 21 | Legacy (Runnable) | PacketTweaker | ResourceLocation | Legacy |
| 1.21.9-1.21.10 | 21 | Legacy (Runnable) | PacketTweaker | ResourceLocation | Legacy |
| 1.21.11 | 21 | Legacy (Runnable) | PacketTweaker | Identifier | Modern |
| 26.1-26.3 | 25 | Modern (Result) | Fabric PacketContext | Identifier | Modern |

### Repository Map

- `common` - Platform-agnostic splitter, identities, snapshots, cache index, immutable blob store.
- `versions/shared` - Fabric entrypoint/config, delivery orchestration, command tree, AutoHost internal shim, main-pack suppression helper.
- `versions/legacy`, `versions/modern` - Generation-event hook adapters only.
- `versions/autohost-legacy`, `versions/autohost-modern` - Networking packet context and readiness adapters only.
- `versions/pack-id-*` - `ResourceLocation` vs `Identifier` construction boundaries.
- `versions/mixin-*` - Minimal `AbstractProvider#getProperties(...)` injection signatures for main-pack suppression.
- `versions/commands-*` - Permission predicate adapters only.
- `versions/mc-*` - Per-version dependency and build wiring.

---

## 4. Validation Rules

Validation is **build-only** unless explicitly requested otherwise:

```bash
./gradlew build --no-daemon
./gradlew :mc-1.21.8:build --no-daemon
./gradlew :mc-1.21.10:build --no-daemon
./gradlew :mc-1.21.11:build --no-daemon
./gradlew :mc-26.1:build --no-daemon
./gradlew :mc-26.2:build --no-daemon
./gradlew :mc-26.3:build --no-daemon
```

*Policy:* Do not launch Minecraft, start a server, or run unit/gameplay/integration tests unless explicitly requested. Cross-version changes must successfully build every affected target.

---

## 5. Change Discipline

- Prefer one shared implementation plus the minimal necessary version adapter.
- Inspect upstream Polymer development branches (`dev/1.21.6`, `dev/1.21.9`, `dev/1.21.11`, `dev/26.1`, `dev/26.2`, `dev/26.3`) before changing integration points.
- Never copy decompiled third-party code.
- Avoid speculative abstractions; introduce adapters only for proven upstream API boundaries.
- Delete compatibility shims whenever upstream exposes a public equivalent.
