# SETUP — Phase 0 follow-ups

The repository is scaffolded but not yet buildable end-to-end. A few steps need to happen on this machine before `./gradlew assembleDebug` or `monkeyc` will run. None of these can safely be automated from inside Claude Code (they require licenses, signed-in accounts, or large downloads).

## Android side

1. **Install Android Studio** (Hedgehog 2023.1+ recommended). It bundles the JDK and Android SDK.
2. **Open the `android/` folder in Android Studio.** It will offer to:
   - Download the missing SDK platforms (set `compileSdk = 34`, `minSdk = 26`).
   - Install the Gradle wrapper at the version Android Gradle Plugin 8.5.2 expects.
   - Sync the project. The first sync will create `local.properties` with `sdk.dir` filled in.
3. **Copy `android/local.properties.example` → `android/local.properties`** and fill in:
   - `sdk.dir` (Android Studio sets this automatically on first sync; verify the path).
   - `strava.client.id` + `strava.client.secret` (see Strava section below).
4. **First build** — from inside Android Studio: Build → Make Project. From the command line (after the wrapper is in place):
   ```powershell
   cd android
   .\gradlew.bat assembleDebug
   .\gradlew.bat test
   ```

## Garmin Connect IQ side

1. **Install the Connect IQ SDK**: https://developer.garmin.com/connect-iq/sdk/
2. **Generate a developer key** (one-time, per Garmin's docs):
   ```powershell
   openssl genrsa -out developer_key.pem 4096
   openssl pkcs8 -topk8 -inform PEM -outform DER -in developer_key.pem -out garmin\developer_key -nocrypt
   ```
   `garmin/developer_key` is gitignored.
3. **Install the VS Code "Monkey C" extension** (easiest path). Open the `garmin/` folder; the extension picks up `monkey.jungle` and lets you build + simulate from the editor.
4. **Build from the command line**:
   ```powershell
   cd garmin
   monkeyc -o ClimbPro.prg -f monkey.jungle -y developer_key -d fr255m
   ```
5. **Run in the simulator**: launch the Connect IQ Simulator from the SDK; load `ClimbPro.prg`; pick the Forerunner 255 Music device profile.

## Strava API registration

Strava integration (Phase 3) needs an OAuth client registered with Strava:

1. Go to https://www.strava.com/settings/api while signed in.
2. Create an application:
   - **Authorization Callback Domain**: `localhost` (for development) — adjust later for production.
   - **Application Name**: ClimbPro (or your preferred name).
3. Copy the **Client ID** and **Client Secret** into `android/local.properties`.
4. Note Strava's rate limits: 100 requests / 15 min, 1000 / day. Sync code uses caching to stay well under these.

## Verifying Phase 0

After the above, you should be able to:

- [ ] Run `.\gradlew.bat assembleDebug` and get an APK (`android/app/build/outputs/apk/debug/app-debug.apk`).
- [ ] Run `.\gradlew.bat test` and see "BUILD SUCCESSFUL" (no tests yet — empty test set passes trivially).
- [ ] Build `garmin/ClimbPro.prg` via `monkeyc` and launch it in the simulator. The datafield shows "ClimbPro" centered on the FR255 Music profile.

When all three boxes are checked, Phase 0 is done. Phase 1 (shared protocol) is next.
