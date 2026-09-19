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
      | `scripts/build-sbt-examples.sh test` | **recorded as passing, which was wrong** — the script reported only the last example's result. `sensor-data-scala` was failing (Scala 3: implicit vals need explicit types), locally and in CI. Found and fixed in Phase 1 (`f2901ac7`, `5b1d49bb`). |

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
- [ ] **Align the operator's image**, still `eclipse-temurin:11-jre-focal`, with Java 25 — tracked
      under Phase 1's follow-ups now that the port is done.

## Phase 1 — Port to Apache Pekko ✅

Done on branch `phase-1-pekko`, in five commits:

| Commit | What |
|---|---|
| `27821f38` | Dependencies and licensing: Pekko 1.7.0 / pekko-http 1.4.0 / pekko-connectors-kafka 1.2.0 / pekko-grpc 1.2.0 / pekko-management 1.2.1 (nakka's line); library packages and HOCON |
| `bad80824` | Cloudflow's own `akka.*` packages → `cloudflow.cli`, `cloudflow.crd`, `cloudflow.kube`, `cloudflow.config` |
| `6815ec71` | User-facing API and wire-level names → Pekko (the commit message lists every renamed name) |
| `f2901ac7` | `build-sbt-examples.sh` fails when any example fails |
| `5b1d49bb` | Examples and docs include paths |

- [x] **`kafka-clients`**: pinned to **3.9.2**, what pekko-connectors-kafka 1.2.0 is built and tested
      against (it had been 4.1.0 with Alpakka Kafka 8). Kafka 4 brokers accept 3.9 clients.
- [x] **Akka 2.7–2.10 APIs**: the only one in use was a rename, `AkkaManagement` → `PekkoManagement`.
- [x] **Pekko families pinned whole** (`Dependencies.pekkoFamilyOverrides`, build-wide):
      pekko-connectors-kafka 1.2.0 brings pekko-stream 1.1.5, pekko-management an older pekko-http.
      The runtime classpath has one version per family and no Akka jar.
- [x] **Licensing gone**: Lightbend resolvers and token (build, sbt plugin, workflows, examples),
      the licence key for forked JVMs, and the operator's `AKKA_LICENSE_KEY` env var.
- [x] **Mechanical rename** of library imports and config. One silent bug caught in review, not by any
      test: HOCON inside a Scala string (`pekko.discovery.kubernetes-api.pod-label-selector`) had been
      rewritten as a package name, which Pekko would have ignored.
- [x] **Cloudflow's own `akka.*` packages** moved, with the CLI's GraalVM configs; dead
      `akka.cli.microservice.*` reflect entries dropped.
- [x] **User-facing API renamed to Pekko**, including the runtime identifier and every wire-level name.
- [ ] **DECISION — the CRD group `cloudflow.lightbend.com`.** Kept. Changing it orphans every existing
      resource on every cluster.
- [x] **Examples and doc include paths** ported; the docs build resolves every page.
- [x] **Images**: streamlet images run `/opt/pekko-entrypoint.sh` on Java 25 with Pekko jars only; the
      operator image starts its Pekko ActorSystem and HTTP server and fails only at the Kubernetes
      API, as it should without a cluster. Deploying it is Phase 3.

Verified at the end: `sbt +test` all green (822), scripted 7/7, 12/12 sbt examples, the Maven example,
the docs build — with no Lightbend token anywhere.

### Follow-ups from the port

- [ ] **The docs' prose** still describes Akka streamlets, `AkkaStreamlet`, `CloudflowAkkaPlugin` and
      so on. Upstream's site content; needs a real rewrite rather than a find-and-replace, because some
      of its "Akka" is correct history (doc.akka.io links, release notes).
- [ ] **Align the operator image** with Java 25 (see Phase 0) — nothing blocks it now.
- [ ] Two doc includes point at Avro schemas that do not exist (`Measurements.avsc`,
      `InvalidMetric.avsc` in `sensor-data-scala`); broken before the port.
- [ ] Test sources in several modules have no copyright header, so `headerCheckAll` fails; CI runs only
      `headerCheck` (main sources). Broken before the port.

### Consumers of the fork

- [ ] **Existing consumers stay on Akka for now** (decided). They keep consuming the frozen `0.1.0`
      Akka artefacts and operator image; migrating them to the Pekko line is a later, separate piece
      of work. Nothing on `master` may overwrite what they pull (see Phase 0). The migration is the
      list of renamed names in `6815ec71`.

## Phase 2 — What the graph pipelines need from Cloudflow

These come straight from the design: nakka publishes CloudEvents to Kafka with the attributes in
**headers** and `ce-subject` as the **record key**; the graph sink must be idempotent, keep
per-entity order, and be rebuildable.

- [x] **Record metadata on inlets, and key-preserving outlets** (branch `phase-2-record-metadata`).
      `Record[T](value, key: Option[String], headers)` and `Header` in `cloudflow-streamlets`; four
      logic methods with Java variants — `recordSourceWithCommittableContext`, `plainRecordSource`,
      `committableRecordSink`, `plainRecordSink`. A record sink writes the record's own key and its
      headers in order, falling back to the outlet's partitioner only for a record with no key, so a
      stage that reads records and writes them on keeps every entity on one partition without anyone
      writing a partitioner. The value API is untouched. Testkit: `inletAsRecordTap`,
      `inletFromRecordSource`, `outletAsRecordTap`.
      Verified against real Kafka (`RecordKafkaSpec`): 50 records over 10 keys, CloudEvents-style and
      binary headers, through a relay streamlet; a plain Kafka consumer finds every key and header
      byte intact, header order kept, each key on one partition in order. Replacing the relay's record
      API with the value API fails the test.
- [ ] **Record variants still missing**: the sharded sources (`shardedSourceWithCommittableContext`,
      `shardedPlainSource`), `flexiFlow`, `sinkRef`, and record taps in the *Java* testkit. None is
      needed by the graph pipelines as planned; add each when something is.
- [x] **A JSON codec** (branch `phase-2-json-codec`): module `cloudflow-json`, `JsonInlet` /
      `JsonOutlet` over a jsoniter-scala `JsonValueCodec` (2.40.1, as nakka), offered to builds as
      `Cloudflow.library.CloudflowJson`. JSON has no schema, so a port declares a *schema name* — the
      contract — defaulting to the element type's class name and pinned with `withSchemaName`; the
      fingerprint is the name's SHA-256, so blueprint verification connects equal names, refuses
      different ones (a new contract version is a new name) and never connects JSON to another format.
      Verified against real Kafka (`JsonKafkaSpec`): CloudEvents written by a *plain Kafka producer*,
      as nakka writes them — subject as key, `ce_*` headers, JSON body — read by a streamlet as
      records, with a malformed body skipped and the stream carrying on. Scala only: a
      `JsonValueCodec` comes from Scala macros.
- [x] **Consuming topics Cloudflow does not own** (branch `phase-2-unmanaged-topics`). No code change
      was needed; nothing had tested it. Now: a blueprint topic declared `managed = false` with only
      consumers, its own `topic.name` and `bootstrap.servers`, verifies and reaches the consuming
      streamlet's port mapping with its name, brokers and `consumer-config` intact
      (`UnmanagedTopicSpec`, Scala 2.12/2.13/3); the operator creates no topic for it while still
      creating the app's own (`TopicActionsSpec`, mutation-checked against dropping the `managed`
      filter); and `JsonKafkaSpec` already consumes a topic a plain Kafka producer wrote. Answers
      recorded in `CLAUDE.md`: brokers come from the topic, a named cluster or the default one;
      the consumer group is `<appId>.<streamletRef>.<inlet>`; committable sources start from
      `earliest`. What is still unproven is the same thing on a live cluster — Phase 3.
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
