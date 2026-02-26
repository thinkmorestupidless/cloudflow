# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Status

**Cloudflow is deprecated.** Existing customers are directed to contact Lightbend support. No new feature development is expected.

## Build System

The primary build tool is **SBT**. All source code lives under `core/`. Run SBT commands from `core/` or use the top-level scripts.

```bash
# Build scripts (run from repo root)
./scripts/build-core.sh compile       # Compile core modules
./scripts/build-core.sh test          # Run tests with scalafmt check
./scripts/build-core.sh publishLocal  # Publish to local Maven repo

./scripts/build-sbt-examples.sh test
./scripts/build-mvn-examples.sh test
```

```bash
# Direct SBT commands (run from core/)
sbt compile
sbt test
sbt cloudflow-akka/test               # Test a specific module
sbt +publishLocal                     # Cross-publish for Scala 2.12 and 2.13

# SBT plugin scripted tests (end-to-end plugin tests)
sbt +publishLocal cloudflow-sbt-plugin/scripted

# GraalVM native image CLI
sbt regenerateGraalVMConfig           # Regenerate GraalVM reflection config
```

SBT needs at least 4GB of heap: `sbt -mem 4096`

## Code Formatting

Scalafmt is enforced on every CI build. `scalafmtOnCompile := true` is set globally, so formatting runs automatically on compile.

```bash
sbt scalafmtCheckAll        # Check Scala source formatting
sbt scalafmtSbtCheck        # Check .sbt file formatting
sbt scalafmt                # Auto-format Scala sources
sbt scalafmtSbt             # Auto-format .sbt files
```

Config: `core/.scalafmt.conf`

## Copyright Headers

All source files must have a copyright header (`2016-2026 Lightbend Inc.`). This is enforced by `sbt-header`. The plugin will fail the build if headers are missing or malformed.

## Architecture

Cloudflow is a Kubernetes-native framework for composing distributed stream processing applications from **Streamlets** connected via **Blueprints**.

### Core Abstractions

- **Streamlet** (`cloudflow-streamlets`): Base trait defining a stream processing unit with typed Inlets and Outlets. Schemas are enforced at compile time via Avro or Protobuf.
- **Blueprint** (`cloudflow-blueprint`): Defines how Streamlets connect. Validated at compile time by the SBT plugin.
- **Runner** (`cloudflow-runner`): Bootstraps a Streamlet in a container; reads config injected by the operator.

### Module Organization (under `core/`)

| Module | Purpose |
|---|---|
| `cloudflow-streamlets` | Core Streamlet API (no runtime dep) |
| `cloudflow-avro` / `cloudflow-proto` | Schema codec support |
| `cloudflow-blueprint` | Blueprint model and validation |
| `cloudflow-akka` | Akka Streams runtime implementation |
| `cloudflow-akka-testkit` | Test harness for Akka Streamlets |
| `cloudflow-akka-util` | HTTP/gRPC server support for Akka Streamlets |
| `cloudflow-runner` | Container entry point (generic runner) |
| `cloudflow-localrunner` | Sandbox runner for local development |
| `cloudflow-operator` | Kubernetes operator (manages CloudflowApplication CRDs) |
| `cloudflow-crd` | CRD model classes |
| `cloudflow-config` | Hocon-based configuration model shared between operator and runner |
| `cloudflow-cli` | `kubectl cloudflow` plugin (compiled to GraalVM native image) |
| `cloudflow-sbt-plugin` | SBT plugin: blueprint validation, Docker image building |
| `cloudflow-maven-plugin` | Maven equivalent of the SBT plugin |
| `cloudflow-extractor` | Streamlet metadata extraction (used by build plugins) |
| `cloudflow-it` | Kubernetes integration tests (requires a live cluster) |

### Dependency Flow

```
cloudflow-streamlets
    └── cloudflow-avro / cloudflow-proto
        └── cloudflow-blueprint
            └── cloudflow-akka (runtime)
                └── cloudflow-runner (container bootstrap)
                    └── cloudflow-operator (K8s lifecycle)

cloudflow-extractor → cloudflow-sbt-plugin / cloudflow-maven-plugin
cloudflow-config    → (shared by operator + runner)
cloudflow-cli       → (standalone native binary, talks to K8s API)
```

### Cross-Compilation

The project cross-compiles for **Scala 2.12** and **2.13**. Dependencies are defined in `core/project/Dependencies.scala`. Common settings (organization, scala version, compiler flags) are in `core/project/Common.scala`.

### CLI Native Image

`cloudflow-cli` is compiled to a native binary (`kubectl-cloudflow`) using GraalVM native-image. Reflection config lives under `cloudflow-cli/src/main/resources/META-INF/native-image/`. Run `sbt regenerateGraalVMConfig` after adding new reflectively-accessed classes.

## Documentation

Docs use Antora. Build locally:

```bash
cd docs && make html   # Requires Docker
```

## Integration Tests

Integration tests in `cloudflow-it/` require a live GKE cluster. They are not run locally; CI uses a dedicated cluster configured via environment variables.
