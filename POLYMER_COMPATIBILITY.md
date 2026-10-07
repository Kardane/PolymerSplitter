# Polymer API Compatibility

This document is the source of truth for Polymer API dependencies that are not fully covered by public/stable API.

Last reviewed against the supported upstream Polymer development branches:

- `dev/1.21.6` for Minecraft 1.21.8
- `dev/1.21.9` for Minecraft 1.21.9-1.21.10
- `dev/1.21.11`
- `dev/26.1`
- `dev/26.2`
- `dev/26.3`

## Public API used normally

PolymerSplitter should prefer these surfaces whenever they cover the required behavior:

- `PolymerResourcePackUtils`
  - generation lifecycle events
  - main pack path and UUID
  - public resource-pack creation/build API
- `OutputGenerator.Result` on modern versions
- `AutoHostUtils`
  - `registerHostedFile(...)`
  - `SEND_RESOURCE_PACK_COLLECTOR`
  - `RESOURCE_PACKS_READY` where available
  - default hosted-pack identifier/path helpers
- `ResourcePackDataProvider`
  - active provider lookup
  - readiness
  - `createProperties(...)`
  - provider URL construction

Do not replace these with implementation-class access.

## Internal compatibility shims

The supported Polymer branches do not currently expose public equivalents for every behavior PolymerSplitter needs. Direct implementation access is therefore restricted to the following files.

### AutoHost configuration and unregister

File:

```text
versions/shared/.../PolymerAutoHostInternals.java
```

The reviewed 1.21.8 through 26.3 Polymer branches expose the same `AutoHost.config`, `AutoHost.FILES`, `config.enabled`, and `config.type` field contracts, so this implementation dependency is shared rather than duplicated by networking context.

Internal Polymer surface:

```text
eu.pb4.polymer.autohost.impl.AutoHost.config
eu.pb4.polymer.autohost.impl.AutoHost.FILES
```

Why it remains:

- There is no public AutoHost config/provider-type accessor. Phase 13 needs the configured provider type to reject `external`, `empty`, disabled, and unknown providers instead of advertising split URLs that may not be served.
- `AutoHostUtils.registerHostedFile(...)` is public, but no matching public unregister/clear API exists in the reviewed branches. Phase 14 uses cleanup only after full server stop.

Do not read `AutoHost.config` or mutate `AutoHost.FILES` anywhere else.

### Resource-pack rebuild trigger and legacy output-path fallback

Files:

```text
versions/legacy/.../PolymerResourcePackInternals.java
versions/modern/.../PolymerResourcePackInternals.java
```

Internal Polymer surface:

```text
eu.pb4.polymer.resourcepack.impl.PolymerResourcePackMod.generateAndCall(...)
eu.pb4.polymer.resourcepack.impl.PolymerResourcePackMod.useMainPath   // legacy only
```

`PolymerResourcePackMod` is explicitly annotated `@ApiStatus.Internal` in the reviewed upstream branches.

Why it remains:

- Public `PolymerResourcePackUtils.buildMain(...)` can build a pack, but it does not replace Polymer's generation lock, async scheduling, user-facing generation messages, or callback orchestration used by `generateAndCall(...)`. Reimplementing that behavior would create a second generation lifecycle.
- Legacy completion events do not provide the actual output path. Polymer may fall back to `*_server.zip` when the normal main path cannot be replaced; `useMainPath` is the upstream state that records that decision.
- Modern completion returns `OutputGenerator.Result`, so modern publication does not need `useMainPath`.

Do not import `PolymerResourcePackMod` outside these compatibility shims.

### Main-pack suppression Mixin

Files:

```text
versions/mixin-legacy/.../AbstractProviderMixin.java
versions/mixin-modern/.../AbstractProviderMixin.java
versions/shared/.../MainPackSuppression.java
```

Internal Polymer target:

```text
eu.pb4.polymer.autohost.impl.providers.AbstractProvider#getProperties(...)
```

Why it remains:

- The public collector API is additive. It can add split packs but does not expose a public remove/replace hook for Polymer's original monolithic main pack.
- Suppression must happen after the provider has assembled its final property collection so unrelated global/external packs are preserved.

The two Mixins exist only because `getProperties(...)` has different legacy/modern context parameters. They delegate the readiness check and original-main-pack identification/removal to shared `MainPackSuppression`. Do not move general delivery or hosting logic into either Mixin or the suppression helper.

## Alternatives intentionally rejected

- Reflection to hide implementation imports: removes compile-time checking without reducing runtime coupling.
- Reading Polymer's config file directly: duplicates upstream config path/schema/normalization behavior and is more fragile than one isolated implementation accessor.
- Inferring provider type from provider implementation classes: still depends on implementation types and loses exact configured-provider identity.
- Calling `buildMain(...)` directly for `/polymersplitter rebuild`: bypasses Polymer's generation orchestration.
- Clearing hosted mappings during runtime: can invalidate already-issued URLs. Cleanup remains server-stop-only.

## Upgrade checklist

When changing the minimum Polymer version or adding a Minecraft/Polymer target:

1. Check whether public APIs now exist for AutoHost config access, hosted-file unregister, rebuild requests, or final main-pack replacement.
2. Re-check `PolymerResourcePackMod.generateAndCall(...)` and legacy `useMainPath` signatures/semantics.
3. Re-check `AbstractProvider#getProperties(...)` signature and return type.
4. Re-check that `AutoHost.config`, `AutoHost.FILES`, `config.enabled`, and `config.type` still share one contract across every supported branch before keeping the shared compatibility shim.
5. Prefer deleting a compatibility shim when a public upstream API becomes available.
6. Build `common` and every supported Minecraft target after any compatibility change.

No new direct import from a Polymer `.impl` package should be added outside the compatibility shims or the two version-specific suppression Mixins without updating this document and `ARCHITECTURE.md`.
