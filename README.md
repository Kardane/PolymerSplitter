# PolymerSplitter

[English](README.md) | [한국어](README.ko.md)

A server-side Fabric companion mod for [Polymer](https://github.com/Patbox/polymer) that splits generated resource packs by namespace for independent client caching, delegating hosting and delivery to Polymer AutoHost.

When resource packs update, clients only re-download modified namespaces instead of the entire monolithic pack.

**Status:** Pre-0.1.0 development (Phase 10-14 stability baseline complete)  
**Client Mod:** Not required (vanilla-compatible)

---

## Key Features

- **Namespace Splitting:** Divides Polymer's final pack into separate per-namespace ZIPs.
- **Dedicated Audio Pack:** Extracts OGG audio from `assets/minecraft/sounds/` into a standalone `minecraft.sounds` pack (`sounds.json` remains in the primary pack).
- **Primary Pack Preservation:** Root metadata (`pack.mcmeta`, `pack.png`), licenses, undeclared overlays, and safe non-namespace files are preserved in the primary pack (`minecraft`, or the first alphabetically).
- **Deterministic & Content-Addressed:** Fixed timestamps (1980-01-01), sorted entry order, and in-stream SHA-1 hashing prevent hash churn and provide instant cache validation.
- **Zero-Friction Fallback:** Degrades seamlessly to Polymer's original monolithic pack delivery on any generation, cache, or provider issue.

---

## Supported Versions

| Minecraft | Build Target | Java | Polymer |
| --- | --- | ---: | --- |
| 1.21.8 | 1.21.8 | 21 | 0.13.13+1.21.8 |
| 1.21.9-1.21.10 | 1.21.10 | 21 | 0.14.4+1.21.10 |
| 1.21.11 | 1.21.11 | 21 | 0.15.2+1.21.11 |
| 26.1-26.1.2 | 26.1.2 | 25 | 0.16.5+26.1.2 |
| 26.2 | 26.2 | 25 | 0.17.5+26.2 |
| 26.3+ | 26.3 | 25 | 0.18.2+26.3 |

---

## AutoHost Compatibility

PolymerSplitter integrates directly with Polymer's built-in local AutoHost providers:

- `polymer:automatic`
- `polymer:auto`
- `polymer:netty`
- `polymer:same_port`
- `polymer:http_server`
- `polymer:standalone`

*Note:* If AutoHost is disabled, set to `polymer:external` or `polymer:empty`, or using an unknown/custom provider, split delivery is disabled and Polymer's original delivery path remains unsuppressed. External uploads are not supported.

---

## Commands

All commands require administrator permissions (level 2+ or modern permission predicate).

| Command | Description |
| --- | --- |
| `/polymersplitter status` | Display current split state, cache status, and active AutoHost provider. |
| `/polymersplitter list` | List active split namespaces and their pack sizes. |
| `/polymersplitter reload` | Reload configuration from `config/polymersplitter.json`. |
| `/polymersplitter rebuild` | Trigger Polymer pack regeneration followed by split publication. |
| `/polymersplitter send <targets> all` | Push all active split packs to specified players. |
| `/polymersplitter send <targets> namespace <namespace>` | Push a single active namespace pack to specified players. |

`<targets>` supports standard vanilla player selectors (`@a`, `@p`, `@r`, or target filters). Tab completion is available for active namespaces in the `READY` generation.

---

## Configuration

Configuration is located at `config/polymersplitter.json` and generated with defaults on first launch:

```json
{
  "enabled": true,
  "splitMode": "namespace",
  "copyPackIcon": true,
  "deterministicZip": true,
  "logPackSizes": true,
  "minSplitPackSizeMb": 30,
  "compressionLevel": 6,
  "includeNamespaces": [],
  "excludeNamespaces": [],
  "unreferencedBlobRetentionDays": 0
}
```

### Options

| Setting | Type | Default | Description |
| --- | --- | ---: | --- |
| `enabled` | boolean | `true` | Enables PolymerSplitter. Requires a server restart if changed. |
| `splitMode` | string | `"namespace"` | Splitting strategy. Currently only `"namespace"` is supported. |
| `copyPackIcon` | boolean | `true` | Includes `pack.png` in every split pack. |
| `deterministicZip` | boolean | `true` | Enforces fixed 1980-01-01 timestamps and sorted entry ordering. |
| `logPackSizes` | boolean | `true` | Logs uncompressed and compressed pack sizes during generation. |
| `minSplitPackSizeMb` | integer | `30` | Minimum compressed size (MiB) for non-primary packs. Packs at or below this are merged into the primary pack (`0` disables merging). |
| `compressionLevel` | integer | `6` | ZIP deflate compression level (`0`-`9`). |
| `includeNamespaces` | string[] | `[]` | Allowlist of namespaces to keep separate. If non-empty, unlisted namespaces merge into the primary pack. |
| `excludeNamespaces` | string[] | `[]` | Denylist of namespaces to force-merge into the primary pack. Exclude takes precedence over include. |
| `unreferencedBlobRetentionDays` | integer | `0` | Startup GC retention for unreferenced blobs: `0` deletes immediately on startup, `>0` retains for N days, `-1` disables GC. |

*Behavior Notes:*
- Policy filters never discard assets; excluded or undersized packs are merged into the primary pack.
- `minecraft.sounds` matches both its synthetic key and the `minecraft` source namespace.
- Changes to output-affecting settings (`copyPackIcon`, `deterministicZip`, `minSplitPackSizeMb`, `compressionLevel`, namespace filters) invalidate cache reuse and take effect on the next generation. Use `/polymersplitter rebuild` to apply immediately.

---

## Building

Requires JDK 25.

```bash
# Build all targets
./gradlew build

# Build a specific target
./gradlew :mc-1.21.8:build
./gradlew :mc-1.21.10:build
./gradlew :mc-1.21.11:build
./gradlew :mc-26.1:build
./gradlew :mc-26.2:build
./gradlew :mc-26.3:build
```

---

## Documentation

- [README.ko.md](README.ko.md) - 한국어 문서 (Korean documentation).
- [ARCHITECTURE.md](ARCHITECTURE.md) - Runtime flow, cache invariants, storage model, and internal design.
- [AGENTS.md](AGENTS.md) - Repository rules, hard invariants, and development guidelines for coding agents.
- [POLYMER_COMPATIBILITY.md](POLYMER_COMPATIBILITY.md) - Audited upstream Polymer API boundaries.
- [ROADMAP.md](ROADMAP.md) - Completed milestones and upcoming architecture goals.
