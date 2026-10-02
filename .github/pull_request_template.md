## Summary

<!-- What does this change and why? Link the issue: "Closes #123". -->

## Checklist

- [ ] Branch is based on the latest `staging`, and this PR targets `staging` (not `main`)
- [ ] `./gradlew test` passes (or `pwsh -File scripts\preflight.ps1`)
- [ ] Watch modules still compile for `fr255m` (if Monkey C changed)
- [ ] Wire format changed? `protocol/schema.json`, `protocol/examples/`, `ClimbPayloadBuilder` **and** the Monkey C parsers are updated together
- [ ] Architecture or wire format changed? `Documentation/ARCHITECTURE.md` and `README.md` are updated
- [ ] Domain rules respected (climb ≥ 800 m and ≥ 3 %, 8 % segments, one shared color table, offline-first)

## How was this tested?

<!-- Unit tests, simulator, real device (phone + watch)… -->
