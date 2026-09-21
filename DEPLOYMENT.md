# Deployment — Android release builds, signing & auto-update

This document covers how signed release APKs get built and published, and how the app
checks for and installs its own updates. It does **not** cover the Garmin Connect IQ
side — see [Documentation/SETUP.md](Documentation/SETUP.md) for the full toolchain
install checklist.

## Repo name used by the update checker

`UpdateChecker` polls the GitHub Releases API for this repo:

```
SvenvanDalen/climbBrooo
```

That value is hard-coded in
`android/app/src/main/java/nl/paree/climbpro/update/UpdateChecker.java`
(`REPO = "SvenvanDalen/climbBrooo"`). If the repo is ever renamed or forked under a
different owner, update that constant.

---

## Step 0 — generate a release keystore (do this locally, never in CI)

The signing key must never touch the repo or CI logs in plaintext. Generate it once,
on your own machine, with the `keytool` that ships with the JDK:

```bash
keytool -genkeypair -v -keystore release-key.jks -keyalg RSA -keysize 2048 \
    -validity 10000 -alias mijn-app-alias
```

This prompts for a keystore password, a key password, and your identity details
(name, org, etc — cosmetic, shown to nobody but you).

> **Important:** back up `release-key.jks` and both passwords somewhere safe (a
> password manager or an encrypted drive) **outside** this repo. If you lose them you
> can never sign an update under the same app identity again — Android refuses to
> install an update signed with a different key over an existing install, so every
> user would have to uninstall and reinstall from scratch.

### Upload it to GitHub Actions as secrets

1. Base64-encode the keystore file:
   ```bash
   base64 -i release-key.jks | pbcopy        # macOS
   base64 -w 0 release-key.jks > keystore-base64.txt   # Linux
   ```
2. In the repo, go to **Settings → Secrets and variables → Actions** and add:

   | Secret name | Value |
   |---|---|
   | `SIGNING_KEY` | the base64 output from step 1 |
   | `KEY_ALIAS` | the alias you chose (`mijn-app-alias` above) |
   | `KEYSTORE_PASSWORD` | the keystore password |
   | `KEY_PASSWORD` | the key password |

3. `*.jks` and `*.keystore` are already in `.gitignore` — the keystore file itself must
   never be committed. Only its base64 form lives in the `SIGNING_KEY` secret, which
   GitHub encrypts at rest and masks in logs.

---

## Step 1 — CI/CD: `.github/workflows/build-android.yml`

The release workflow does **not** run on every push to `main` — it only fires on a
push to a separate `production` branch (plus manual `workflow_dispatch` for one-off
runs). This keeps ordinary merges to `main` release-free; you decide when a build
actually ships by promoting `main` to `production`:

```bash
git fetch origin
git push origin origin/main:production
# or, from a local main checkout:
#   git checkout production && git merge main && git push origin production
```

Once `production` moves, CI:

1. Checks out the repo, sets up JDK 17, gives `gradlew` execute permission.
2. Builds the release APK: `./gradlew assembleRelease`, with `versionCode` set to the
   GitHub Actions run number (`VERSION_CODE=${{ github.run_number }}` — see
   `android/app/build.gradle`, which reads that env var and falls back to `1` for
   local builds).
3. Signs the APK with the `SIGNING_KEY`/`KEY_ALIAS`/`KEYSTORE_PASSWORD`/`KEY_PASSWORD`
   secrets from Step 0.
4. Publishes the signed APK as an asset on a new GitHub Release tagged
   `v${{ github.run_number }}`.

This means every merge to `main` produces a new, installable, monotonically
versioned release — no manual signing step, no keystore ever leaving GitHub's secret
store.

---

## Step 2 — in-app update checker

The app can check GitHub Releases for a newer build and offer to install it:

- **`UpdateChecker.java`** — calls
  `https://api.github.com/repos/SvenvanDalen/climbBrooo/releases/latest`, compares the
  release's `tag_name` (`v<run_number>`) against `BuildConfig.VERSION_CODE`, and
  invokes a callback when a newer release is available.
- **`downloadAndInstall(...)`** — hands the APK asset URL to `DownloadManager`.
- **`DownloadCompleteReceiver.java`** — listens for
  `DownloadManager.ACTION_DOWNLOAD_COMPLETE` and launches the package installer via a
  `FileProvider` URI for the downloaded APK.
- `RouteListActivity.onCreate()` (the app's launcher activity) calls
  `UpdateChecker.checkForUpdate()` once on startup and shows an `AlertDialog` asking
  the user to confirm before anything is downloaded or installed.
- On Android 8+, installing an APK from outside the Play Store requires the
  "install unknown apps" permission for this app specifically
  (`PackageManager.canRequestPackageInstalls()`). The app checks this before
  triggering an install and, if it's not yet granted, shows a clear prompt that sends
  the user to the relevant system settings screen.

**The user always has to confirm the install manually** — this is a plain, non-rooted
Android app with no device-owner/MDM privileges, so silent self-updates are not
possible (and wouldn't be desirable for a side-loaded app regardless).

### Installing the first APK manually

Before the update checker has anything to check against, install the first build by
hand:

1. Go to the repo's **Releases** page on GitHub and open the latest release.
2. Download the `.apk` asset onto the phone (or `adb push` + open it locally).
3. Tap the downloaded file. If prompted, allow "install unknown apps" for the app you
   opened it with (Files, Chrome, etc.) — Android will ask this once per source app.
4. Confirm the install. From then on, the app's own update checker will offer future
   releases automatically.
