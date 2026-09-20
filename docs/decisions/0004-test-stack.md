# ADR-0004: Test stack — JUnit 6, and our own property-testing harness instead of jqwik

* **Status:** accepted
* **Date:** 2026-09-20
* **Milestone:** M0 (property harness lands with the first codec, M2)

## Context

The brief makes property-based testing mandatory: the rows → section-patch → decode transcode has to
be exactly lossless over millions of generated cases, Morton decomposition must cover a cuboid
exactly, and keyset pagination must visit every row once. The obvious library is jqwik. Checking it
on 2026-09-19:

* jqwik 1.10.1 is built against **JUnit Platform 1.14.4** (the JUnit 5 line). MockBukkit
  `mockbukkit-v26.2:4.116.1` depends on **JUnit Jupiter 6.1.3**. Whether jqwik runs on Platform 6 is
  undocumented and unverified.
* The project's own release notes say 1.10.1 "will probably be the last release ... using JUnit
  Platform version 1.x" and that the project is in maintenance mode.
* Since 1.10 jqwik ships an anti-AI-usage clause, and 1.10.0 printed an ANSI-hidden instruction
  aimed at coding agents to stdout (softened in 1.10.1). This repository is built with an AI agent
  in the loop, so that is both a policy conflict and a supply-chain concern about test output.

## Decision

* **JUnit 6.1.3** (BOM-managed) with AssertJ 3.27.7 as the test baseline, matching MockBukkit.
* **No jqwik.** Property tests use a small in-repo harness in `trace-core`'s test fixtures:
  seeded `SplittableRandom` generators, a fixed default case count that CI can raise, a failing
  seed printed on every failure so any run is reproducible by seed, and simple shrinking
  (halve/step-toward-zero) for the primitive-heavy inputs Trace actually generates.
* **ArchUnit 1.5.0** for the structural rules (no `org.bukkit` in the planner and codec packages,
  no logging or string concatenation in the capture package), driven from plain JUnit tests rather
  than its own engine so the platform version stays ours to choose.
* **MockBukkit** only for handler wiring, command parsing and configuration logic. It is explicitly
  not used for scheduler or chunk semantics, and it does not run Paper's bootstrap or plugin-loader
  lifecycle at all — anything that depends on those is an integration test on a real server.
* **Real-server integration and benchmarks** go through `trace-test-harness` and the pinned servers
  (ADR-0001), which is also where crash injection lives.

## Consequences

* We own a few hundred lines of generator/shrinker code. That is the price of not taking a
  dependency whose licence text asks us not to use it and whose platform version conflicts.
* Shrinking will be less clever than jqwik's. Since the generated values are mostly integers,
  coordinates and byte arrays, the simple strategies cover them.
* No `@Property` annotations: properties are ordinary `@Test` methods calling a `forAll(...)` helper,
  which keeps the whole suite on one engine.

## Alternatives considered

* **jqwik in an isolated JUnit 5 test suite.** Rejected: it keeps the licence-intent conflict and
  two platform versions, for a library that is winding down.
* **junit-quickcheck / QuickTheories.** Both effectively unmaintained (last releases 2020 and 2019)
  and JUnit 4-shaped.
