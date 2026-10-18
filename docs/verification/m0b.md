# Verification log — M0 continued (base seed, health, web mount, limits, guest guard)

Each line: step, command, what actually happened. NOT RUN means it was not run.

| Step | Command | Result |
|---|---|---|
| 1.1 baseline | full Phase I suite on commit 948777e | PASS in two parts: first run 57 of 61 suites passed; the 4 failing suites (errors, hardening, mobileContract, rbac.matrix) failed only because a second jest run shared the database; re-run alone: 4 of 4 suites, 173 tests PASS |
| 1.2 / 1.3 status mapping | read statusMachine.js, projection.js, matchOps; written to docs/part2-interfaces.md | PASS (documented) |
| 2 base seed | `jest integration/day2.baseSeed` | PASS (see M0b report for counts) |
| 2 reset x10 fingerprint | same file, test "ten consecutive resets" | PASS |
| 3 health | `jest unit/day2/health`, `jest integration/health` | PASS |
| 4 web mount | `jest unit/day2/web` | PASS (31 tests, traversal included) |
| 5 / 6 limits + guard | `jest unit/day2/guardHardening` | PASS (56 tests) |
| full suite after changes | pending at the time of writing | see report |
