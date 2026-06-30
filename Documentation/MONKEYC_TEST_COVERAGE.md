# Monkey C test coverage

How thoroughly the three watch modules (`garmin`, `garmin-widget`,
`garmin-surface`) are covered by automated unit tests, and what is deliberately
left to on-device verification.

Run everything with:

```
pwsh -File tools/run-monkeyc-tests.ps1
```

This builds each module's `monkey-test.jungle` with `--unit-test` and runs it in
the Connect IQ simulator on the `fr255m` profile. **Current status: 93 tests
pass (garmin 45, garmin-widget 30, garmin-surface 18), 0 failures, 0 errors.**

> There is no line-coverage instrument in the Connect IQ SDK. Coverage below is
> tracked by **function inventory** — every function in `*/source/*.mc` mapped to
> the test that executes it.

## What "covered" means here

Three levels, because not all watch code can be asserted the same way:

- **Asserted** — pure logic; the test feeds inputs and asserts exact outputs.
  This is the part where bugs hide and where on-watch testing is most painful, so
  it is covered to ~100%.
- **Smoke** — rendering (`onUpdate(dc)`, `drawProfile`, …). Pixels can't be
  asserted headlessly, but the draw path is **executed against a real off-screen
  `Dc`** (`Graphics.createBufferedBitmap(...).get().getDc()`), so a crash (null
  deref, bad API use) fails the test instead of only surfacing on the bike.
- **On-device only** — see the gap list below.

## Per-module summary

| Module | Functions | Executed by tests | Notes |
|---|---|---|---|
| garmin | 21 | 21 (100%) | parser, view render, compute, app lifecycle, pacing logic |
| garmin-surface | 13 | 13 (100%) | parser, GPS refine, view render, app lifecycle |
| garmin-widget | 78 | ~65 (~83%) | all logic + all view render; gap is UI navigation glue |

**Business logic + rendering: ~100% across all three modules.** The widget gap is
entirely UI/navigation/timer/transmit glue (below).

## The wire parser is now executed, not just reviewed

`CommListener` / `PhoneMessageCallback.onMessage` — the hand-written v3 payload
decoder that `CLAUDE.md` flagged as review-only — is now driven by tests that
build dictionaries mirroring `protocol/examples/*.json` and assert the decoded
`ClimbData` / `SurfaceData` fields (route mode, radius mode, segments, calib,
surfaces, targets, starred segments, plus null/wrong-version/missing-key
rejection). This is the watch-side counterpart to the JVM `ProtocolRoundTripTest`.

## Deliberate gaps (verified on-device, not headless)

These widget functions are **not** reachable from the headless test harness.
Empirically confirmed: pushing a view in a `(:test)` does **not** make
`Ui.getCurrentView()` return it, so delegate bodies that branch on the current
view cannot run, and `Ui.popView` / `Ui.pushView` / `Comm.transmit` / `Timer`
have no live context.

- **BehaviorDelegate navigation handlers** — `onBack`, `onMenu`, and the
  view-matched inner branches of `onSelect` / `onNextPage` / `onPreviousPage`
  across `ClimbListDelegate`, `RouteListDelegate`, `ClimbDetailDelegate`,
  `ActiveSetDelegate`, `SyncDelegate`. Their guard (no-op) path is executed; the
  navigating path is not.
- **`SyncView.onShow` / `onTimeout`** — start a `Timer` and `switchToView`.
- **`CommListener.handleHello`** — calls `Comm.transmit` (needs a phone).
- **`RouteListDelegate.openSavedClimb` / `splitOnLastUnderscore`** — `hidden`
  helpers only reachable through a view-matched `onSelect`.

All other functions — every data/parser/storage/index/draw method and every app
lifecycle hook — are executed by the suite.
