# Contributing

1. Open or reference an issue for non-trivial changes.
2. Work on a feature branch.
3. Add deterministic tests for behavioral changes.
4. Run `gradle clean check --warning-mode=fail`.
5. Open a pull request.
6. Do not weaken checks to make CI pass.

Keep claims evidence-based. FieldSync does not claim exactly-once delivery, consensus, or automatic conflict-free convergence.
