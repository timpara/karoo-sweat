## What this changes

<!-- Describe the behavioural effect, not just the code. -->

## Does it change the physiological model?

- [ ] No, this does not touch `model/`
- [ ] Yes

If yes, please confirm:

- [ ] I ran `./gradlew :model:test --tests '*ReferenceTable*' --rerun-tasks -i` and
      read the output, not just the pass/fail
- [ ] I have stated the source or justification for any new coefficient
- [ ] I did not tune constants purely to make an existing test band pass
- [ ] Structural tests (monotonicity, slow climb vs fast flat) still hold

Predicted effect on output:

<!-- e.g. "raises sweat estimates ~15% on clear-sky rides above 25 C" -->

## Checklist

- [ ] `./gradlew build` passes
- [ ] Tests added or updated
- [ ] Comments explain *why*, not *what*
