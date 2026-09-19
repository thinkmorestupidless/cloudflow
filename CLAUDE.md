# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A maintained fork of [Lightbend Cloudflow](https://github.com/lightbend/cloudflow), which upstream
is deprecated. Cloudflow composes streaming applications from **streamlets** (typed stream-processing
stages with inlets and outlets) wired together by a **blueprint**, packages them as images, and runs
them on Kubernetes under its own operator, with Kafka topics between the stages.

This fork runs on **Apache Pekko** — upstream ran on Akka, whose licence nakka exists to avoid — on the
same Pekko line as nakka (pekko 1.7.0, pekko-http 1.4.0, pekko-connectors-kafka 1.2.0,
pekko-management 1.2.1). **`ROADMAP.md` is the plan and the record of decisions** — read it before
changing anything structural. Next on it: what graph-projection pipelines over nakka services' Kafka
topics need from Cloudflow.

## Consumers, and what must not break

- **The `0.1.0` Akka line has existing consumers.** They pin `sbt-cloudflow` and the Cloudflow
  libraries at **`0.1.0`** from GitHub Packages, and the operator image
  `ghcr.io/thinkmorestupidless/cloudflow-operator:0.1.0`, and stay on that line until they are
  deliberately migrated. Tag `v0.1.0` marks the last Akka commit; a patch for that line branches
  from there, never from `master` once the port has begun.
- A consumer that builds the operator from a checkout of this repository, rather than pulling the
  published image, must check out the `v0.1.0` tag: `master` is on Pekko.
- **Moving a consumer to the Pekko line is a migration, not an upgrade.** Everything Cloudflow named
  after Akka now names Pekko — code (`cloudflow.pekkostream`, `PekkoStreamlet`, `CloudflowPekkoPlugin`,
  `cloudflow-pekko_3`) and wire-level names (the runtime identifier `"pekko"`, `cloudflow.runtimes.pekko`,
  the operator's `PEKKO_RUNNER_*` env vars, `cloudflow-app-pekko-role`, `/opt/pekko-entrypoint.sh`,
  `LOGLEVEL_PEKKO`, …). Commit `6815ec71` lists every one.

## Build

All source is under `core/`; run sbt from there. SBT needs at least 4GB of heap: `sbt -mem 4096`.

No credentials are needed: every dependency is on Maven Central. (`.gitignore` still lists
`.lightbend-token` and `.akka-license-key`, so a leftover local copy from the Akka days cannot be
committed; keep those entries.)

```bash
sbt test                              # every module, each at its own Scala version
sbt +test                             # what CI runs: also runs cloudflow-blueprint on 2.12, 2.13 and 3
sbt cloudflow-pekko/test              # one module
sbt scalafmtCheckAll scalafmtSbtCheck # CI checks both; scalafmtAll scalafmtSbt to fix
sbt +publishLocal cloudflow-sbt-plugin/scripted   # the sbt plugin's end-to-end tests
./scripts/build-sbt-examples.sh test  # publishLocal, then build every sbt example against it
./scripts/build-mvn-examples.sh test  # publishM2, then the Maven example
```

The scripted tests and the examples are separate, nested sbt builds against the locally published
artefacts. **Run the example scripts on a clean tree** (see the dynver note below): they compute the
version once and publish it, and each example resolves the plugin by version. The sbt examples script
runs every project, lists every failure and exits 1 if there was any — until `f2901ac7` it reported
only the last project's result, so an example could fail for months unseen.

**Images are built by Cloudflow's own `docker` task (`DockerImageBuild`), not sbt-docker's.**
sbt-docker learns the built image's id by parsing the builder's output, which fails on Docker's
containerd image store (the default for new Docker 29 installs). Ours tags with `docker build -t`
and reads the id from `--iidfile`. sbt-docker still supplies the Dockerfile DSL, staging and push;
do not remove the override when upgrading it unless a release parses that store's output.

**Publish and consume in one sbt invocation.** With uncommitted changes dynver's version carries a
timestamp to the minute, so `sbt +publishLocal` followed by a *separate* `sbt …/scripted` a minute
later looks for artefacts that were never published. `sbt +publishLocal cloudflow-sbt-plugin/scripted`
computes the version once.

The default streamlet base image is `eclipse-temurin:25-jre-alpine`, and a replacement must be
Alpine-based: the image build runs `apk` and BusyBox's `addgroup`/`adduser -S`.

`scalafmtOnCompile` is on in most modules, so compiling reformats. Copyright headers are enforced by
`sbt-header` (`project/CopyrightHeader.scala`); a missing or malformed header fails the build.

**Scala versions are per module, not a uniform cross-build.** Everything is Scala 3 **except**:
`cloudflow-sbt-plugin` and what it depends on — `cloudflow-extractor`, `cloudflow-build-support`,
`cloudflow-cr-generator`, `cloudflow-maven-plugin` — which are Scala 2.12 because an sbt 1.x plugin
must be; and `cloudflow-blueprint`, which cross-builds 2.12 / 2.13 / 3 (via `sbt-cross`) because both
worlds consume it.

**`kafka-clients` is 3.9.2, deliberately** — what pekko-connectors-kafka 1.2.0 is built and tested
against. Kafka 4 brokers accept it. Do not move it to 4.x ahead of the connector.

**Pekko families are pinned whole** (`Dependencies.pekkoFamilyOverrides`, applied build-wide). Pekko
refuses mixed versions within a family at startup, and eviction lifts only the modules a build names:
pekko-connectors-kafka brings pekko-stream 1.1.5, pekko-management an older pekko-http. Add any new
Pekko module to that list, not only to `libraryDependencies`.

**Integration tests.** `cloudflow-it` and `cloudflow-new-it` need a live Kubernetes cluster and are
not part of `sbt test`.

## Versions and releases

The version comes from git alone, via **sbt-dynver**: exactly `0.2.0` on a commit tagged `v0.2.0`,
`0.2.0-3-abc1234` three commits later, with a date suffix when the tree is dirty. The separator is
`-`, set once in `build.sbt`, because Docker image tags cannot contain dynver's default `+`.

**A release is a pushed `v*` tag.** `.github/workflows/release.yml` refuses to publish unless
dynver's version is exactly the tag, then publishes the artefacts to GitHub Packages and the
operator image to GHCR. Nothing else publishes. Two reasons it is this strict:

- GitHub Packages versions are immutable; republishing one is a 409.
- GHCR tags are **mutable**. Publishing an existing version would silently replace an image a
  consumer pins — which is exactly what republishing `0.1.0` from a Pekko `master` would have done
  to the Akka line's consumers.

Never re-tag `v0.1.0`, and never publish by hand.

CI runs on pull requests and on pushes to `master` (`push.yml`, `build-pr.yaml`). The sbt and mvn
example jobs are `continue-on-error`, so a red example does not block a merge — look at them.

## Architecture

### Core abstractions

- **Streamlet** (`cloudflow-streamlets`): a stream-processing unit with typed inlets and outlets. No
  runtime dependency.
- **Codec** (`cloudflow-avro`, `cloudflow-proto`): an inlet or outlet's schema and (de)serialiser.
- **Blueprint** (`cloudflow-blueprint`): how streamlets connect, and which Kafka topics sit between
  them; verified at build time by the sbt plugin.
- **Runtime**: how a streamlet executes. Only the **Pekko** runtime is built here (`cloudflow-pekko`).
  The CLI and sbt plugin also carry hooks for **Spark** and **Flink** runtimes, which are *not* built
  in this repo — default storage mounts (`WithConfiguration`), `runtimes/{spark,flink}` resources, and
  test fixtures. Those are an extension point for external runtimes, not dead code; leave them.
- **Operator** (`cloudflow-operator`): watches `CloudflowApplication` custom resources and creates
  the topics, deployments and configuration for each streamlet.
- **CLI** (`cloudflow-cli`): the `kubectl cloudflow` plugin.

### Modules (`core/`)

| Module | Purpose |
|---|---|
| `cloudflow-streamlets` | Streamlet API (no runtime dependency) |
| `cloudflow-avro` / `cloudflow-proto` | Codecs |
| `cloudflow-blueprint` | Blueprint model and verification (2.12 / 2.13 / 3) |
| `cloudflow-pekko` | Pekko Streams runtime for streamlets |
| `cloudflow-pekko-util` | HTTP and gRPC server streamlets |
| `cloudflow-pekko-testkit` / `cloudflow-pekko-tests` | Test harness for Pekko streamlets, and its tests |
| `cloudflow-runner` / `cloudflow-runner-config` | Container entrypoint that boots a streamlet from operator-injected config |
| `cloudflow-localrunner` | Runs a whole application locally (sandbox mode) |
| `cloudflow-crd` | `CloudflowApplication` custom-resource model |
| `cloudflow-config` | HOCON configuration model shared by the operator, CLI and runner |
| `cloudflow-operator` | Kubernetes operator; `kube-actions` is vendored in, ported to fabric8 6.x |
| `cloudflow-cli` | `kubectl cloudflow` |
| `cloudflow-sbt-plugin` / `cloudflow-maven-plugin` | Build plugins: blueprint verification, image building (2.12) |
| `cloudflow-extractor`, `cloudflow-build-support`, `cloudflow-cr-generator` | Streamlet metadata extraction and CR generation for the build plugins (2.12) |
| `cloudflow-maven-archetype` | Maven project template |
| `cloudflow-it`, `cloudflow-new-it`, `cloudflow-new-it-library` | Integration tests against a live cluster |
| `tooling` | GraalVM reflection-config generation for the CLI (`regenerateGraalVMConfig`) |

### What still says Akka, and why

A handful of lines keep "Akka" on purpose, because they record history rather than name anything:
the `kube-actions` vendoring attributions, "copied from Akka internals", `akka-stream-contrib`,
`doc.akka.io` links, old release notes, and the docs' prose (upstream's site, not yet revisited).
`tooling/…/akka-microservices-crd.yml` is Lightbend's Akka Microservices CRD, not Cloudflow's.

The CRD group is still `cloudflow.lightbend.com`; changing it orphans every existing resource.

**HOCON inside a Scala string is config, not code.** The port's one silent bug was a key built in a
string (`pekko.discovery.kubernetes-api.pod-label-selector`) rewritten as if it were a package name —
Pekko ignores an unknown key without complaint. Config roots are `pekko`; `org.apache.pekko.` belongs
only in class names.

## Things that behave differently from what you would assume

- **Streamlet sources decode `record.value` only.** Keys and headers are dropped
  (`PekkoStreamletContextImpl`), so a streamlet cannot see CloudEvents attributes carried in Kafka
  headers.
- **The default outlet partitioner is `RoundRobinPartitioner`**, and a partitioner is `T => String`
  — it cannot see the inbound key. A round-robin hop destroys per-key ordering at the first stage.
- **`managed = false` on a blueprint topic** stops the operator creating it (`TopicActions`), which
  is how a blueprint consumes a topic Cloudflow does not own.

These three are the gaps `ROADMAP.md` Phase 2 closes.
