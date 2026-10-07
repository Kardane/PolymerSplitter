# AGENTS.md

Repository-level instructions for coding agents. Keep this file small; use it as a map to the durable documentation instead of duplicating design detail.

## Start here

Read the minimum relevant context before editing:

1. [README.md](README.md) — user-facing behavior, commands, supported versions.
2. [ARCHITECTURE.md](ARCHITECTURE.md) — runtime flow, invariants, failure semantics, version boundaries.
3. [ROADMAP.md](ROADMAP.md) — planned work and completed phase boundaries.
4. [POLYMER_COMPATIBILITY.md](POLYMER_COMPATIBILITY.md) — audited public/internal Polymer API boundary.
5. The affected `versions/mc-*/build.gradle` — exact Minecraft, Fabric, Polymer, Java, and adapter selection.

Update `ARCHITECTURE.md` in the same change when lifecycle, caching, identity, hosting, delivery, compatibility, or failure behavior changes. Keep README user-facing.

## Hard invariants

- This is a server-only Fabric companion for [Polymer](https://github.com/Patbox/polymer); vanilla clients need no PolymerSplitter mod.
- Split the final Polymer pack by resource namespace and preserve declared overlays/root metadata according to `ARCHITECTURE.md`.
- Hosting belongs to Polymer AutoHost. Do not add a separate HTTP server.
- Split delivery is enabled only for explicitly supported built-in local AutoHost providers.
- Preserve Polymer's required/prompt policy when automatically or manually pushing packs.
- Keep namespace UUIDs stable. The primary pack uses Polymer's main UUID; non-primary packs use deterministic namespace UUIDs.
- Use final ZIP SHA-1 for content metadata and content-addressed AutoHost IDs. For newly written namespace ZIPs, hash the emitted ZIP bytes in-stream; do not add a second full-file SHA-1 pass unless verifying an already-existing blob.
- Never remap an already issued content-addressed URL to different bytes.
- Treat one `CoordinatorSnapshot` (state + active generation + failure + namespace transition) as the atomic runtime snapshot. `SplitRegistry` is a read-only view of that same snapshot.
- `hosted/<sha1>.zip` is the only persistent split-ZIP store. Do not reintroduce generation-directory copies.
- Cache reuse/publication requires an explicit output-compatibility match: split algorithm version + `copyPackIcon` + `deterministicZip`. Diagnostic settings such as `logPackSizes` are not output compatibility.
- Whole-source fast reuse is allowed only when source SHA-1 matches a compatible, fully verified index. It may skip splitting/fingerprinting/index rewrite, but must still complete hosted registration before publishing READY.
- Keep cache loading explicit: `SplitCacheIndex.read()` parses `index.json` without filesystem mutation; `verify()` hashes referenced blobs. Incompatible or metadata-free old caches are cache misses, not current output. Mutation belongs only to generation, legacy blob import, or explicit cleanup.
- Publish only after content-addressed blobs, AutoHost registration, and the atomic `index.json` commit succeed.
- Broken generation/cache/provider state must degrade to Polymer's original main-pack path, never partial split delivery.
- Keep historical hosted blobs/URLs valid while a server is running. Garbage-collect unreferenced blobs only after a successful startup restore or another explicitly safe lifecycle point.
- Prefer public Polymer APIs. Direct `.impl` access is allowed only in the compatibility shims and the documented main-pack suppression Mixins listed in `POLYMER_COMPATIBILITY.md`.
- Do not put Minecraft or Polymer types in `common`.

## Version boundaries

| Target | Java | Generation | AutoHost adapter | Commands |
| --- | ---: | --- | --- | --- |
| 1.21.8 | 21 | legacy events | PacketTweaker + `ResourceLocation` ID | legacy permission |
| 1.21.9-1.21.10 | 21 | legacy events | PacketTweaker + `ResourceLocation` ID | legacy permission |
| 1.21.11 | 21 | legacy events | PacketTweaker + `Identifier` ID | modern permission |
| 26.1-26.3 | 25 | modern events | Fabric `PacketContext` + `Identifier` ID | modern permission |

Before changing a Polymer integration point, inspect the matching upstream branch: `dev/1.21.6`, `dev/1.21.9`, `dev/1.21.11`, `dev/26.1`, `dev/26.2`, or `dev/26.3`.

## Repository map

- `common` — splitter, identity, lifecycle snapshots, cache, immutable hosted blobs, provider-independent records.
- `versions/shared` — Fabric initializer/config plus shared command, hosting/delivery, and publication orchestration.
- `versions/legacy`, `versions/modern` — generation-event/path adapters only.
- `versions/autohost-legacy`, `versions/autohost-modern` — packet-context/readiness/provider adapters.
- `versions/pack-id-*` — the `ResourceLocation` / `Identifier` construction boundary.
- `versions/mixin-*` — original Polymer main-pack suppression only.
- `versions/commands-*` — permission predicate adapters only.
- `versions/mc-*` — dependency/version wiring only.
- `gradle/version-module.gradle` — shared version-module assembly.

## Validation

Validation is build-only unless the user explicitly requests otherwise.

```bash
./gradlew build --no-daemon
./gradlew :mc-1.21.8:build --no-daemon
./gradlew :mc-1.21.10:build --no-daemon
./gradlew :mc-1.21.11:build --no-daemon
./gradlew :mc-26.1:build --no-daemon
./gradlew :mc-26.2:build --no-daemon
./gradlew :mc-26.3:build --no-daemon
```

Do not start Minecraft, launch a server, or add/run unit, gameplay, functional, integration, or runtime tests unless explicitly requested. Cross-version changes must build every affected target.

## Change discipline

- Prefer one common implementation plus the smallest necessary version adapter.
- Verify upstream Polymer/Fabric APIs before widening compatibility or changing an internal compatibility shim. Delete a shim when upstream exposes a public equivalent.
- Do not copy decompiled third-party code.
- Avoid speculative abstractions; add an adapter only for a real API boundary.
- Keep documentation cross-linked and remove stale rules instead of accumulating exceptions.
