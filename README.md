# PolymerSplitter

Server-side Fabric companion mod for [Polymer](https://github.com/Patbox/polymer).

PolymerSplitter splits Polymer-generated resource packs into independently cacheable packs while leaving hosting and delivery to Polymer AutoHost.

**Status:** Phase 12 startup recovery and cache validation implemented  
**Client mod:** not required

See [ARCHITECTURE.md](ARCHITECTURE.md) for the runtime design and version boundaries.  
See [ROADMAP.md](ROADMAP.md) for the next development phases.

## Supported versions

| Minecraft | Build target | Polymer |
| --- | --- | --- |
| 1.21.8 | 1.21.8 | 0.13.13+1.21.8 |
| 1.21.9-1.21.10 | 1.21.10 | 0.14.4+1.21.10 |
| 1.21.11 | 1.21.11 | 0.15.2+1.21.11 |
| 26.1-26.1.2 | 26.1.2 | 0.16.5+26.1.2 |
| 26.2 | 26.2 | 0.17.5+26.2 |
| 26.3+ | 26.3 | 0.18.2+26.3 |

## Commands

```text
/polymersplitter status
/polymersplitter list
/polymersplitter rebuild
```

## Configuration

The configuration file is created at `config/polymersplitter.json`.

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
