# Contributing to ClimbPro

Thanks for your interest! This is a personal project for the **Garmin Forerunner 255 Music**,
but issues and pull requests are welcome.

## Before you start

- Read [README.md](README.md) and [Documentation/ARCHITECTURE.md](Documentation/ARCHITECTURE.md) —
  they define the product contract.
- For larger changes, open an issue first so we can agree on the approach.
- Toolchain setup (Android Studio, Connect IQ SDK, developer key, Strava keys) is described in
  [HANDLEIDING.md](HANDLEIDING.md) and [Documentation/SETUP.md](Documentation/SETUP.md).

## Branch workflow

`main` is production, `staging` is where changes are validated first.

1. Branch off the latest `staging` (`feature/…` or `fix/…`).
2. Make and test your changes on that branch.
3. Open a pull request **into `staging`** — never into `main` directly.

## Ground rules

- **Android code is Java** (not Kotlin), Gradle Groovy DSL, JSON-file persistence (no Room/SQLite).
- **Heavy compute belongs on the phone.** The watch only renders precomputed data and matches GPS
  (`garmin-onboard` is the deliberate exception).
- **Domain rules are non-negotiable**: a climb is ≥ 800 m **and** ≥ 3 % average gradient; segments are
  8 % of the climb length; the gradient → color table lives in one place (`protocol/`).
- **Offline-first**: nothing at ride time may depend on a phone or network connection.
- **Target device only**: don't use APIs or capabilities the Forerunner 255 Music lacks.

### Changing the wire format

Update these **together, in the same PR**, or sync silently breaks:

- `protocol/schema.json` (canonical) and `protocol/examples/`
- `service/ClimbPayloadBuilder` (Android)
- the hand-written Monkey C parsers (`CommListener.mc`, `SurfaceData.mc`)
- `Documentation/ARCHITECTURE.md` and `README.md`

Never hand-edit the generated Java POJOs.

## Testing

```powershell
pwsh -File scripts\preflight.ps1   # JVM tests + Monkey C compile (+ simulator tests if available)
cd android; .\gradlew.bat test     # JVM unit tests only
pwsh -File tools\run-monkeyc-tests.ps1   # Monkey C unit tests in the Connect IQ simulator
```

Add or update tests for the behavior you change. CI runs `./gradlew test` on every pull request.

## Code of conduct

By participating you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

By contributing you agree that your contributions are licensed under the [MIT License](LICENSE).
