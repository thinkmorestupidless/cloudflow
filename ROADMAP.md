# Cloudflow roadmap: Pekko, and graph pipelines for nakka

Why: nakka services publish their events to Kafka, and a central, eventually consistent graph
(Neo4j) should be built from those topics by streaming pipelines rather than by a hand-written
service. Cloudflow is the right shape for that — blueprints, typed stages, per-stage scaling, an
operator that owns the lifecycle — but this fork currently runs on **commercial Akka**
(`akka 2.10.16`, `akka-http 10.7.3`, `AKKA_LICENSE_KEY` injected into every pipeline pod), and
nakka exists on Pekko precisely so that nothing on its path carries a BSL licence.

So: first make Cloudflow Pekko-based and fit to consume nakka's topics; then build the graph
pipelines on it.

Decisions are collected at the end; the ones taken are recorded there with their consequences.

---

## Phase 0 — Housekeeping

- [x] **Reconcile the diverged branches.** Nothing to merge: `master` already holds everything on
      the old integration branch as squashed commits (`b0badb75`, `287d947e`, `af28ddae`) —
      a trial merge produced a tree identical to `master`'s. The branch is now redundant.
- [x] **Freeze the Akka line its existing consumers use.** They pin `sbt-cloudflow` / Cloudflow
      `0.1.0` from GitHub Packages. Tagged `v0.1.0` on `9a6db01b`, the last Akka commit (not yet
      pushed). Named `v0.1.0` rather than `v0.1.0-akka` because dynver versions every later
      untagged build from the nearest `v*` tag, which would have put `-akka` into Pekko versions.
- [x] **A tag-driven release process** (`28de9a9c`). There was none:
      - `publish.yml` is manual (`workflow_dispatch`, or a push to the long-gone `fix-example-tests`
        branch) and hard-codes `VERSION: "0.1.0"`, written into `core/version.sbt` by
        `setVersionFromTag` — which overrides the `sbt-dynver` already in `project/plugins.sbt`.
      - Re-publishing a version hits a 409 (GitHub Packages versions are immutable), which the
        workflow treats as success — so a Pekko build published as `0.1.0` would silently never
        appear; and GHCR tags *are* mutable, so its operator image would silently replace the Akka
        one existing consumers pull.

      Now: `release.yml` runs on a pushed `v*` tag, refuses to publish unless dynver's version is
      exactly the tag, and publishes the artefacts and the operator image. `setVersionFromTag`,
      `core/version.sbt`, the 409 workaround, `sbt-sonatype` and `sbt-pgp` are gone.
- [x] **Point CI at the right branch** (`28de9a9c`). `push.yml` and `release-drafter.yml` now
      trigger on `master`. Deleted rather than retargeted: the docs publisher (Java 8, pushes to
      cloudflow.io), the native CLI build (retired `ubuntu-20.04` runner, GraalVM 20.1), and the
      FOSSA job (Lightbend's API key).
- [ ] **Rebuild the native CLI job** on a current runner and GraalVM, if a native
      `kubectl-cloudflow` binary is still wanted.
- [x] **Rewrite `CLAUDE.md`** (`4324450f`).
- [x] **Baseline before the port** (2026-09-19, `4324450f`, JDK 21 on arm64 macOS, Docker 29.5):
      | Stage | Result |
      |---|---|
      | `scalafmtCheckAll` | passes |
      | `sbt +test` | **all green** — 822 tests (`cloudflow-akka` 301; `cloudflow-blueprint` 89 on each of 2.12, 2.13, 3; the rest across the other modules) |
      | `+publishLocal cloudflow-sbt-plugin/scripted` | **1 of 7 pass** (`app-graph-generation`). The other six fail building an image: the default streamlet base image `adoptopenjdk/openjdk8:alpine` (`CloudflowBasePlugin.scala:66`; `openjdk11:alpine` in the `base-image` test) has no arm64 manifest. Environmental on Apple silicon; CI runs amd64 and has not been checked. |
      | `scripts/build-sbt-examples.sh test` | passes — every example builds, tests and verifies its blueprint |

      Two things that look like failures and are not: the scripted tests and examples need
      `LIGHTBEND_COMMERCIAL_TOKEN` *exported* — `core/.lightbend-token` is read only by the core
      build, not by the nested builds, which then fail to resolve commercial Akka. And the examples
      script runs `scalafmtAll` before checking, so it rewrites six example sources in place; revert
      them afterwards.
- [x] **Replace the default streamlet base image** with `eclipse-temurin:25-jre-alpine` (Java 25,
      amd64 and arm64), in the sbt plugin, the maven archetype, the mvn example and the docs. It
      must stay Alpine-based: the image build runs `apk add bash curl` and BusyBox's
      `addgroup`/`adduser -S`. The `base-image` scripted test now overrides with
      `eclipse-temurin:21-jre-alpine`, so it still proves the override is honoured.
      All 7 scripted tests now pass on arm64 (see the next item).
- [x] **`buildApp` failed on Docker's containerd image store** — the default for new Docker 29
      installs, so it hit users building streamlet images, not just our tests. sbt-docker reads the
      built image's id from the builder's output: 1.9.0 recognised nothing (`Could not parse Docker
      image id`); 1.11.0 took the config digest, which that store does not know as an image. The fix
      on sbt-docker `master` is in no release. Fixed in Cloudflow's plugin instead: its `docker` task
      (`DockerImageBuild`) tags with `docker build -t` and reads the id from `--iidfile`, so nothing
      is parsed. All 7 scripted tests pass on arm64 with BuildKit on the containerd store *and* with
      the legacy builder. Not exercised: pushing (`dockerBuildAndPush`), which no test does; it
      pushes by name and is unchanged.
- [ ] **Upgrade the Prometheus JMX agent** fetched into every streamlet image —
      `jmx_prometheus_javaagent` 0.11.0, from 2018. It works on Java 25 (verified: the agent serves
      JVM metrics) but warns that `sun.misc.Unsafe` methods it calls will be removed in a future JDK.
- [ ] **Align the operator's image**, still `eclipse-temurin:11-jre-focal`, with Java 25 — after the
      Pekko port, since the operator should first run on the runtime it will ship with.

## Phase 1 — Port to Apache Pekko

### Dependencies

| Akka artefact (now) | Pekko replacement |
|---|---|
| `akka-actor`, `-stream`, `-cluster`, `-cluster-sharding-typed`, `-discovery`, `-slf4j`, `-protobuf`, testkits (2.10.16) | `pekko-*` 1.x |
| `akka-http`, `akka-http-spray-json` (10.7.3) | `pekko-http` 1.x |
| `akka-stream-kafka`, `-cluster-sharding`, `-testkit` (Alpakka Kafka 8.0.0) | `pekko-connectors-kafka` 1.x |
| `akka-grpc-runtime` (2.5.10) | `pekko-grpc` 1.x |
| `akka-management`, `-cluster-bootstrap`, `akka-discovery-kubernetes-api` (1.6.4) | `pekko-management` 1.x |

- [ ] **Risk to check first: `kafka-clients` 4.1.0.** The fork moved to Kafka 4 clients alongside
      Alpakka Kafka 8. Confirm which `kafka-clients` the current `pekko-connectors-kafka` is built
      and tested against before committing to it; if it is 3.x, either pin 3.x or verify 4.x at
      runtime.
- [ ] **Audit for Akka 2.7–2.10 APIs.** Pekko 1.x descends from Akka 2.6. The fork has lived on
      2.10, so anything added since 2.6 will not exist; the compiler will find them, but budget
      for it.
- [ ] Mirror nakka's trap: pekko-management pulls an older `pekko-http`, and eviction lifts only
      part of the family — pin the whole `pekko-http` family with `dependencyOverrides`.
- [ ] Remove `project/LightbendCredentials.scala`, the commercial resolvers in `build.sbt` and
      `CloudflowBasePlugin.scala`, and the `AKKA_LICENSE_KEY` injection (`9ec0ff8c`).

### Code

About 90 source files import real Akka library packages (streams and Kafka dominate:
`akka.stream.scaladsl` ×34, `akka.kafka.ConsumerMessage` ×15, `akka.actor` ×13).

- [ ] Mechanical rename `akka.` → `org.apache.pekko.` in library imports, and `akka { }` →
      `pekko { }` in every HOCON file the runner, operator and testkit ship.
- [ ] **Cloudflow's *own* code under `akka.*` packages** — `akka.cli.cloudflow` (36 files),
      `akka.datap.crd`, `akka.kube.actions`, `akka.cloudflow.config`. These are not Akka at all;
      after the port they would be misleading. Move to `cloudflow.cli`, `cloudflow.crd`,
      `cloudflow.kube.actions`, `cloudflow.config`.
- [ ] **Rename the user-facing API to Pekko** (decided): `cloudflow-akka` → `cloudflow-pekko`,
      `cloudflow-akka-testkit`, `cloudflow-akka-util`, `cloudflow-akka-tests` likewise; the
      `cloudflow.akkastream` package → `cloudflow.pekkostream`; `AkkaStreamlet`,
      `AkkaServerStreamlet`, `AkkaStreamletLogic`, `AkkaRunner` and the rest → `Pekko*`; the sbt
      plugin's `CloudflowAkkaPlugin` → `CloudflowPekkoPlugin`; and the runtime identifier `"akka"`
      (`AkkaRunner.Runtime`, carried in every application descriptor) → `"pekko"`. This breaks
      existing streamlets and deployed `CloudflowApplication`s, which is acceptable because existing
      consumers stay on the frozen Akka line.
- [ ] **DECISION — the CRD group `cloudflow.lightbend.com`.** Changing it orphans every existing
      resource on every cluster. Probably keep it for now and revisit.
- [ ] Port the examples, docs (`docs/docs-source`), the maven plugin and archetype, and the
      integration test projects (`cloudflow-it`, `cloudflow-new-it`).
- [ ] Operator and runner images build and deploy on kind with no licence secret present.

### Consumers of the fork

- [ ] **Existing consumers stay on Akka for now** (decided). They keep consuming the frozen `0.1.0`
      Akka artefacts and operator image; migrating them to the Pekko line is a later, separate piece
      of work. Nothing on `master` may overwrite what they pull (see Phase 0).

## Phase 2 — What the graph pipelines need from Cloudflow

These come straight from the design: nakka publishes CloudEvents to Kafka with the attributes in
**headers** and `ce-subject` as the **record key**; the graph sink must be idempotent, keep
per-entity order, and be rebuildable.

- [ ] **Record metadata on inlets.** Today every source decodes `record.value` alone
      (`AkkaStreamletContextImpl`); keys and headers are dropped, so a streamlet cannot see
      `ce-type` or `ce-subject`. Add a metadata-carrying source — e.g.
      `sourceWithCommittableContext` yielding `Record[T](key, headers, value, partition, offset)` —
      and the same in the testkit.
- [ ] **Key-preserving outlets and header propagation.** The default partitioner is
      `RoundRobinPartitioner` (`StreamletPort.scala`, `ExternalOutlet.scala`), and a partitioner is
      `T => String` — it cannot see the inbound key. A round-robin hop destroys per-entity order on
      the first stage. Make it possible (ideally the default for `Record`-based flows) to emit with
      the upstream key and headers intact.
- [ ] **A JSON codec** (`cloudflow-json`, jsoniter-scala, to match nakka) next to the Avro and
      Protobuf ones, including whatever schema definition blueprint verification needs to check
      inlet/outlet compatibility.
- [ ] **Consuming topics Cloudflow does not own.** `managed = false` exists
      (`ApplicationDescriptor.scala:193`; the operator skips creation in `TopicActions`). Verify end
      to end against a nakka topic: per-topic bootstrap servers, consumer-group naming, and
      starting from `earliest` for a new pipeline.
- [ ] **Rebuild support.** A CLI command to reset a streamlet's (or an application's) consumer
      groups to earliest, refusing while it is running — the rebuild runbook depends on it.
- [ ] **Commit after the side effect.** `committableSink(committerSettings)` already exists; add a
      documented, tested pattern for a sink-only streamlet that commits only after an external
      transaction (Neo4j) has committed.
- [ ] **Consumer-lag metrics per streamlet** — the graph's staleness *is* this number, and nothing
      else will tell us it is falling behind.

## Phase 3 — Running beside nakka

- [ ] Kafka in the nakka kind cluster (nakka's deploy currently installs CNPG, cert-manager and
      Envoy Gateway, but no Kafka); the Cloudflow operator needs a default Kafka configuration at
      install.
- [ ] Install the Cloudflow operator from `kustomization/` alongside nakka's, with images loaded
      by `kind load` as nakka's are.
- [ ] Verify the operator (fabric8 6.13.4) against the Kubernetes version nakka's suites use
      (k3s v1.35.1).
- [ ] A nakka sample service publishing to a topic, consumed by a trivial blueprint, end to end
      on kind.

## Phase 4 — Graph projection pipelines (next project; headlines only)

- The versioned **graph-delta schema**: `MergeNode` / `MergeEdge` / `Tombstone`, state-shaped
  (never increments), each carrying a global id and the source entity's sequence number as its
  version, self-describing in the body.
- A generic **`Neo4jMergeSink`** streamlet: `UNWIND` batches per partition, version-guarded `MERGE`
  (`WHERE coalesce(n._v, -1) < d.v`), tombstones instead of deletes, offsets committed after the
  Neo4j transaction.
- **Per-domain mapping blueprints** (nakka events → deltas), with id normalisation and PII
  redaction stages; or a nakka-side `GraphPublisher` for domains that publish deltas themselves.
- Compacted delta topics keyed by node/edge id, so the graph is rebuildable from the topic alone.

---

## Decisions

| # | Question | Decision |
|---|---|---|
| 1 | Base branch | `master`, which already contains the old integration branch. |
| 2 | Existing consumers of 0.1.0 | Stay on the frozen Akka line; migrate later. |
| 3 | API names | Renamed to Pekko throughout, runtime identifier included. |
| 4 | CRD group `cloudflow.lightbend.com` | Still open — recommendation is to keep it for now. |
