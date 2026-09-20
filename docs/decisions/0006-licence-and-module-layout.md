# ADR-0006: Split licence, and a module graph the build enforces

* **Status:** accepted
* **Date:** 2026-09-20
* **Milestone:** M0

## Context

The brief locks a split licence: Apache-2.0 for the published API so that any plugin — closed source
included — can integrate, GPL-3.0-or-later for everything else so that a reseller cannot ship a
modified paywalled fork, plus a GPLv3 §7 additional permission clarifying that linking the API does
not make a consumer a derivative of the implementation. AGPL was considered and rejected there: its
§13 burdens honest server owners while doing nothing about the actual threat, which §5 already
covers.

Two mechanical problems follow. Additional permissions must come from the copyright holders, so the
exception has to exist from the first commit rather than be retrofitted across contributors. And the
module boundaries that make the split meaningful — the API not depending on the implementation, the
engine not depending on the server — are exactly the kind of rule that rots without a check.

## Decision

1. `trace-api` is Apache-2.0 (`trace-api/LICENSE`); everything else is GPL-3.0-or-later (`COPYING`)
   with the additional permission in `LICENSE-EXCEPTION.md`, adapted from the FSF "linking over a
   controlled interface" template that SPDX publishes as `GPL-3.0-interface-exception`. That file
   carries a visible note that counsel has not reviewed it yet.
2. Every source file carries an SPDX header, enforced by Spotless. Because SPDX has no identifier
   for our exception, GPL files say `SPDX-License-Identifier: GPL-3.0-or-later` and point at
   `LICENSE-EXCEPTION.md` on the next line, rather than inventing an expression scanners reject.
3. Contributions come in under the DCO (`git commit -s`), not a CLA. `CONTRIBUTING.md` states which
   licence applies to which directory.
4. The module graph is checked by `verifyModuleGraph`, wired into `check`. It resolves each module's
   compile classpath and fails if a module sees a project the spec forbids:

   | Module | May depend on |
   |---|---|
   | `trace-api` | nothing |
   | `trace-core` | `trace-api` |
   | `trace-storage-api` | `trace-api`, `trace-core` |
   | `trace-storage-sqlite` | the above plus `trace-storage-api` |
   | `trace-paper` | all of the above |
   | `trace-test-harness` | `trace-api` |

   `trace-core` has no Paper dependency at all — stronger than the brief's "no Bukkit types in the
   planner and codec packages", and the reason the engine can be property-tested without a server.
5. Modules arrive with the milestone that needs them. Empty scaffolding for `trace-importer`,
   `trace-bridge-coreprotect`, `trace-tools` and the Postgres/ClickHouse backends would imply
   progress that does not exist.

## Consequences

* A contributor cannot accidentally make the API depend on the implementation: the build fails with
  the rule quoted.
* The exception text is a draft. Until counsel reviews it, the README describes the licences and
  claims nothing beyond them.
* Trademark, which §23 of the brief leans on to deter premium forks, is an owner action and a real
  cost: "Trace" alone is a weak mark, so the distinctiveness has to come from a composite wordmark
  and logo. Nothing in this repository claims a mark that has not been applied for.

## Alternatives considered

* **All-GPL, no exception.** Rejected: an API nobody can link against is an API nobody uses.
* **All-Apache.** Rejected: it invites exactly the paywalled fork the licence plan exists to deter.
* **CLA instead of DCO.** Rejected: on a GPL project a CLA reads as a prelude to relicensing.
