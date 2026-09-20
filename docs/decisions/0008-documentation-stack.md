# ADR-0008: Docusaurus for the documentation site

* **Status:** accepted
* **Date:** 2026-09-20
* **Milestone:** M0 (site built from M7, published at M11)

## Context

The brief requires versioned documentation and warns that the obvious default may be wrong this
year. Checked 2026-09-19:

* **Material for MkDocs** — end of life. Critical fixes only until 2027-05-05; feature work has
  moved to its successor.
* **Zensical** (that successor) — 0.0.63, pre-1.0, and **no native versioning**: versioning works
  only through a transitional fork of `mike` that is not on PyPI.
* **Astro Starlight** — 0.42.x, pre-1.0; versioning comes from a community plugin whose own docs
  call it "still in early development".
* **VitePress** — no native versioning; the request has been open since 2020, and 2.0 is still
  alpha.
* **Docusaurus** — 3.10.2, MIT, maintained by Meta, with **built-in** versioning
  (`docs:version`, `versioned_docs/`, `versions.json`) and steady releases through 2026.

## Decision

Use **Docusaurus 3.x** with its native versioning, cut a docs version at each Trace minor release,
and keep fewer than ten live versions as its own guidance recommends.

The command reference and the configuration reference are **generated** — a Gradle task renders the
Brigadier tree and the annotated config template to Markdown under `docs/`, so they are captured by
`docs:version` like any other page and cannot drift from the build.

Page order follows the brief: the migration guide, install-and-first-five-minutes (with the
single-writer-per-data-directory constraint stated on the page, not buried), and performance-and-
tuning are written before any feature page.

## Consequences

* A Node toolchain enters a Java repository, so CI needs `setup-node` for the docs job only.
* Hosting is decided separately (Cloudflare or GitHub Pages); both serve a static Docusaurus build,
  and neither gives pull-request previews for forks without a two-workflow build-then-deploy split.
* If Zensical ships native versioning after its 0.1.0 line stabilises, revisiting is cheap: the
  content is Markdown either way.

## Alternatives considered

Recorded above. The deciding factor is that versioned documentation is a hard requirement, and
Docusaurus is the only candidate whose versioning is not a plugin or a fork.
