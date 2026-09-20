# Contributing to Trace

Thanks for considering a contribution. This project is early; the fastest way to help is to read
the design record in [docs/decisions](docs/decisions) before writing code, because most of the
architecture is already decided and written down.

## Developer Certificate of Origin

Trace uses the [Developer Certificate of Origin 1.1](DCO) — not a CLA. Every commit must carry a
`Signed-off-by` line whose name and email match the commit author:

```
Signed-off-by: Jane Developer <jane@example.org>
```

`git commit -s` adds it for you. Sign-off certifies that you have the right to submit the work
under the project's licence; it is separate from GPG/SSH commit signing, which is optional.

## Licensing of contributions

* Changes to `trace-api/` are contributed under the Apache License 2.0.
* Changes anywhere else are contributed under GPL-3.0-or-later **with** the additional permission
  in [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md).

Every source file carries an SPDX header; the build enforces it.

## Clean-room rule (please read — it is not negotiable)

Trace must remain free of copied expression from the projects it interoperates with. While working
on Trace, do **not** open, quote, paste or transcribe source code, build files, database DDL or
resource files from CoreProtect, WorldEdit, FastAsyncWorldEdit, LogBlock or Prism — including
GitHub file views, `raw.githubusercontent.com` URLs, code search results, and forum posts that
paste their source.

What you may use: their published documentation and javadoc, README and release notes, listing
pages, and anything under Apache-2.0/MIT/BSD. Paper, CraftBukkit, Folia and Adventure source is
fine — we compile against them.

If you learn an external fact this way, add a line to
[docs/decisions/provenance.md](docs/decisions/provenance.md) saying what you read, where, and when.
If a task seems to require forbidden source, stop and open an issue instead.

## Honesty rule

No invented numbers. Any performance claim in code comments, commit messages, pull requests,
documentation or the README must come from a run of the in-repo harness, with the raw result
committed. "Should be faster" is not a reason to merge.

## Commits and pull requests

* [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `perf:`, `docs:`,
  `test:`, `build:`, `refactor:`, `chore:`. One concern per commit.
* A commit that touches the capture hot path states its allocation-gate result.
* Run `./gradlew build spotlessApply` before pushing.
* New behaviour comes with tests. For the correctness properties listed in the design record, write
  the test first — they define behaviour that cannot be retrofitted.

## Building

JDK 25 is required (Minecraft 26.x runs on Java 25). The Gradle wrapper pins its own version, so
`./gradlew build` is all you need. `./gradlew runServer` and `./gradlew runFolia` start a
development server; both accept the Minecraft EULA only because you configure them to.

## Code of conduct

Participation is governed by the [Contributor Covenant](CODE_OF_CONDUCT.md). Report problems to
info@gravaxis.in.
