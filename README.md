# PolymerSplitter

Server-side Fabric companion mod for [Polymer](https://github.com/Patbox/polymer).

PolymerSplitter splits Polymer-generated resource packs into independently cacheable packs while leaving hosting and delivery to Polymer AutoHost.

Safe files outside resource namespaces are preserved only in the primary pack (`minecraft` when present, otherwise the first namespace alphabetically). This includes `assets/icon.png`, license directories, and undeclared overlays; their original paths are retained without inventing overlay declarations. Each pack still includes `pack.mcmeta` and the configured pack icon. Entries with empty names are omitted with a warning.

**Status:** pre-0.1.0 development; Phase 10-14 stability baseline complete  
**Client mod:** not required

See [ARCHITECTURE.md](ARCHITECTURE.md) for the current runtime, cache/storage model, and version boundaries.  
See [ROADMAP.md](ROADMAP.md) for planned development after the stability baseline.

## Supported versions

| Minecraft | Build target | Polymer |
| --- | --- | --- |
| 1.21.8 | 1.21.8 | 0.13.13+1.21.8 |
| 1.21.9-1.21.10 | 1.21.10 | 0.14.4+1.21.10 |
| 1.21.11 | 1.21.11 | 0.15.2+1.21.11 |
| 26.1-26.1.2 | 26.1.2 | 0.16.5+26.1.2 |
| 26.2 | 26.2 | 0.17.5+26.2 |
| 26.3+ | 26.3 | 0.18.2+26.3 |

## AutoHost compatibility

Split delivery is supported with Polymer's built-in local providers:

```text
polymer:automatic
polymer:auto
polymer:netty
polymer:same_port
polymer:http_server
polymer:standalone
```

AutoHost disabled, `polymer:external`, `polymer:empty`, and unknown/custom providers do not enable split delivery. The original Polymer delivery path is left unsuppressed. PolymerSplitter does not upload split ZIPs to external hosting.

## Commands

Administrator commands:

| Command | Purpose |
| --- | --- |
| `/polymersplitter status` | Show split/cache/AutoHost state. |
| `/polymersplitter list` | List the active split namespaces. |
| `/polymersplitter reload` | Reload `config/polymersplitter.json`. |
| `/polymersplitter rebuild` | Ask Polymer to regenerate, then run the normal split pipeline. |
| `/polymersplitter send <targets> all` | Push every active split pack to the selected players. |
| `/polymersplitter send <targets> namespace <namespace>` | Push one active namespace pack. |

`<targets>` uses Minecraft's normal player selector argument, so selectors such as `@a`, `@p`, `@r`, and filtered selectors work.

The namespace argument offers tab completion from the current `READY` generation, including partial-name matching. Command feedback uses a cyan prefix, gray labels, highlighted values, and green/yellow/red status colors.

Examples:

```text
/polymersplitter send @a all
/polymersplitter send @p namespace minecraft
/polymersplitter send @a[tag=builders] namespace polyfactory
```

The send command re-pushes the current `READY` split generation; it does not rebuild packs. `all` means PolymerSplitter's active split packs only, not Polymer AutoHost global/external packs. Required/prompt behavior remains controlled by Polymer AutoHost.

## Configuration

The configuration file is created at `config/polymersplitter.json`.

A missing file is created with the defaults below. Missing settings use defaults in memory; startup/reload adds newly introduced settings while preserving existing fields. An empty or whitespace-only file is invalid and prevents initialization.

Use `/polymersplitter reload` to reload the file. Output-affecting settings become active for the next Polymer generation; run `/polymersplitter rebuild` after reload to apply them immediately. The current READY generation remains active until then. Changing `enabled` still requires a server restart because hook registration is a startup concern.

On POSIX filesystems, newly saved configuration and cache-index JSON files use mode `0644` so an administrator using a different SFTP account can read them. Existing files are rewritten on load only when adding the missing size option; older files saved with mode `0600` may otherwise require a one-time read-permission adjustment. These JSON files contain settings and cache metadata, not credentials.

```json
{
  "enabled": true,
  "splitMode": "namespace",
  "copyPackIcon": true,
  "deterministicZip": true,
  "logPackSizes": true,
  "minSplitPackSizeMb": 30,
  "compressionLevel": 6
}
```

Only `namespace` split mode is currently supported. Changing output-affecting options such as `copyPackIcon`, `deterministicZip`, `minSplitPackSizeMb`, or `compressionLevel` invalidates cache reuse rather than reusing ZIPs produced under different settings.

`compressionLevel` accepts `0` through `9` and is passed to Java's ZIP deflater for newly generated namespace packs. The default is `6`.

`minSplitPackSizeMb` uses MiB (1,048,576 bytes). Non-primary namespace ZIPs at or below the threshold are merged into the primary pack with their original resource paths; larger packs remain separate. The primary pack always remains, even below the threshold. Set `0` to disable size-based merging. Changing the threshold invalidates cache reuse. Merged namespaces are no longer separately sendable packs or namespace suggestions; use `send ... all` to include their resources.

## Build

JDK 25 is required to build all targets.

```bash
./gradlew build
```

Each version can also be built independently, for example:

```bash
./gradlew :mc-1.21.8:build
./gradlew :mc-1.21.10:build
./gradlew :mc-1.21.11:build
./gradlew :mc-26.1:build
./gradlew :mc-26.2:build
./gradlew :mc-26.3:build
```
