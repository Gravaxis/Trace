## What this changes

<!-- One paragraph. Link the issue if there is one. -->

## Checklist

- [ ] Commits are signed off (`git commit -s`) — see [CONTRIBUTING.md](../CONTRIBUTING.md).
- [ ] `./gradlew build` passes, including Spotless, Error Prone/NullAway and `verifyModuleGraph`.
- [ ] Tests cover the change. Behaviour listed in the design record has a test written first.
- [ ] No source of CoreProtect, WorldEdit, FAWE, LogBlock or Prism was consulted. Any external fact
      used is recorded in `docs/decisions/provenance.md`.
- [ ] No performance claim without a harness run, and the raw result is committed with it.
- [ ] If this touches the capture hot path: the allocation gate result is stated below.
- [ ] If this changes a documented behaviour: the docs change is in this PR.

## Allocation gate / benchmark result

<!-- Paste the relevant numbers and the results directory, or write "not applicable". -->
