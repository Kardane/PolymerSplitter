# AGENTS.md

This file is the repository-level operating guide for coding agents. Keep it short. Put durable design details in [ARCHITECTURE.md](ARCHITECTURE.md) instead of expanding this file.

## Read first

Before changing code, read:

1. `README.md` for supported versions and user-facing behavior.
2. `ARCHITECTURE.md` for runtime flow, invariants, version boundaries, and known limitations.
3. The target version's `versions/mc-*/build.gradle` before editing version-specific integration.

If a change alters architecture, lifecycle, compatibility boundaries, caching, pack identity, or failure behavior, update `ARCHITECTURE.md` in the same change.

## Project contract

PolymerSplitter is a server-only Fabric companion for [Polymer](https://github.com/Patbox/polymer).

Preserve these invariants:

- Split Polymer's final generated resource pack by `assets/<namespace>/`.
- Preserve declared resource-pack overlays by routing each overlay's `assets/<namespace>/` content into the matching namespace pack.
- Preserve ordinary root-level files in every split pack; fail safely instead of guessing unknown root-directory semantics.
- Do not implement a separate HTTP server; hosting and delivery belong to Polymer AutoHost.
- Vanilla clients must not require PolymerSplitter or another client mod.
- Keep namespace pack UUIDs stable across content changes.
- Use Polymer's main resource-pack UUID for the primary split pack (`minecraft` when present).
- Use the final split ZIP SHA-1 for Polymer/Minecraft pack metadata.
- Use content-addressed AutoHost identifiers (`packs/<namespace>/<sha1>`) and immutable hosted blobs; never remap an old content URL to new bytes.
- Do not replace the visible split registry until split generation and AutoHost registration complete.
- If splitting fails or is not ready, leave Polymer's original main pack available as fallback.
- Do not remove Polymer external/global resource packs.
- Keep ZIP generation deterministic where practical and preserve cache reuse for unchanged namespaces.
- Treat Polymer internal implementation as version-sensitive. Prefer public Polymer APIs; keep Mixins minimal.

## Version boundaries

| Target | Java | Generation | AutoHost ID/API | Mixin/commands |
| --- | ---: | --- | --- | --- |
| 1.21.8 | 21 | `versions/legacy` | `autohost-resource-location` | legacy |
| 1.21.9-1.21.10 | 21 | `versions/legacy` | `autohost-resource-location` | legacy |
| 1.21.11 | 21 | `versions/legacy` | `autohost-identifier-legacy` | legacy mixin / modern commands |
| 26.1-26.3 | 25 | `versions/modern` | `autohost-identifier-modern` | modern |

Before changing a Polymer integration point, inspect the matching upstream Polymer source. Current reference branches are `dev/1.21.6` for the 1.21.8-era API, `dev/1.21.9`, `dev/1.21.11`, `dev/26.1`, `dev/26.2`, and `dev/26.3`.

## Repository map

- `common/src/main/java`: Minecraft/Polymer-independent splitting, identity, cache, manifest, and lifecycle logic.
- `versions/shared`: shared Fabric initializer, config, and resources.
- `versions/legacy`, `versions/modern`: Polymer generation hooks.
- `versions/autohost-*`: AutoHost API adapters.
- `versions/mixin-*`: main-pack suppression only.
- `versions/commands-*`: command API adapters.
- `versions/mc-*`: dependency/version wiring only.
- `gradle/version-module.gradle`: shared version-module assembly.
- `ARCHITECTURE.md`: source of truth for system design.

Do not put Minecraft or Polymer types into `common`.

## Validation

The user wants build-only validation.

Allowed validation commands:

```bash
./gradlew build --no-daemon
./gradlew :mc-1.21.8:build --no-daemon
./gradlew :mc-1.21.10:build --no-daemon
./gradlew :mc-1.21.11:build --no-daemon
./gradlew :mc-26.1:build --no-daemon
./gradlew :mc-26.2:build --no-daemon
./gradlew :mc-26.3:build --no-daemon
```

Do not start Minecraft, run a server, run game/functional/integration tests, or add/run separate test suites unless the user explicitly asks. For cross-version changes, prefer the CI build matrix or build every affected target.

## Change discipline

- Make the smallest version-specific adapter change that preserves common code.
- Do not broaden Minecraft version ranges without verifying the matching Polymer/Fabric APIs.
- Do not copy decompiled third-party code into this repository.
- Keep README user-facing and concise; keep implementation detail in `ARCHITECTURE.md`.
- Avoid speculative abstractions. Add a new adapter only when an actual API boundary requires it.
