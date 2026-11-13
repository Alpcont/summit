# Android app template

Starting point for Alp's small personal Android apps. Built entirely on GitHub —
no Android Studio or terminal needed. Every push builds an APK and publishes it as a release.

## Start a new app
1. Click **Use this template → Create a new repository** and name it (e.g. `water-tracker`).
2. Open the new repo with Claude and describe the app. Claude first renames the template
   (app name, package) following `CLAUDE.md`, then builds the app.
3. Install the APK from the repo's **Releases** page on your phone:
   `https://github.com/Alpcont/<repo>/releases/latest/download/<AppName>.apk`

## What's inside
- `CLAUDE.md` — rules Claude follows in every app (read automatically).
- `.github/workflows/build.yml` — builds, verifies and publishes the APK on every push.
- `app/` — a minimal Kotlin app (one screen, light/dark theme, placeholder icon).
- `app/debug.keystore` — fixed signing key, so updates install over the previous version.

## Phone setup (once)
Allow installing apps from your browser / file manager when Android asks.
