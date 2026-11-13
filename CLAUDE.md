# CLAUDE.md — Alp's personal Android apps

Guidelines for building small personal Android apps. Core rules adapted from
Forrest Chang's karpathy-guidelines (MIT); project setup taken from Alpcont/walkbreak.

## About me
- I don't write code and don't use a terminal or Android Studio. Everything happens on
  GitHub: you write the code, GitHub Actions builds the APK, I install it from the release link.
- Devices: Samsung Galaxy S23 Ultra (main) and Galaxy Tab S9 FE+. Apps must look right on
  both, in light and dark mode.
- Explain decisions and anything I need to do in short, plain language.

## 1. Think before coding
- Before writing code, restate what the app should do in 3–5 bullets and list your assumptions.
- If something unclear would change what you build, ask me (max 3 questions). For small
  details, pick the simplest option and tell me what you picked.
- If my idea has a much simpler or better version, say so before building.

## 2. Simplicity first
- Build the smallest version that does the job. No features I didn't ask for; put ideas in
  the README under "Ideas for later" instead.
- Prefer Android platform APIs. Add a library only if it saves a lot of code, and say why.
- No accounts, servers, analytics, ads or INTERNET permission unless I ask. Data stays on
  the phone (SharedPreferences for settings; a database only if truly needed).
- Fewer files is better. A small app can be 2–4 Kotlin files.

## 3. Surgical changes
- When changing an existing app, touch only what the request needs. Don't reformat, rename
  or "clean up" unrelated code.
- Updates must keep existing features and saved user data working.
- If you notice an unrelated bug, tell me; don't fix it unasked.

## 4. Goal-driven execution
- Define "done" as checkable steps (e.g. "APK builds on Actions; reminder fires with screen off").
- After every change the GitHub Actions build must pass. If it fails, read the log and fix it
  before telling me it's done.
- When finished, tell me: what changed, the download link, and a short checklist to test on my phone.

## Project setup (this repo is the template; copied from walkbreak)
- Kotlin, XML layouts + Views (not Compose unless I ask), Material theme with dark mode (values-night).
- Gradle Kotlin DSL; AGP 8.7.x, Kotlin 2.0.x, compileSdk/targetSdk 35, minSdk 26, Java 17.
  Don't upgrade versions unless the build requires it.
- No Gradle wrapper jar; the workflow installs Gradle 8.9 via gradle/actions/setup-gradle.
- Signing: app/debug.keystore, already set in signingConfigs, so each new APK installs as an
  update over the old one. Never change or regenerate it.
- Increase versionCode by 1 (and update versionName) on every change, or the update may not install.
- .github/workflows/build.yml builds on every push, verifies the APK and publishes a GitHub Release
  marked latest. The APK is named after rootProject.name, so this link always gives the newest APK:
  https://github.com/Alpcont/<repo>/releases/latest/download/<AppName>.apk
- README: what the app does (one paragraph), the download link, phone setup tips, "Ideas for later".

## First task in a new repo made from this template
If the app is still named MyApp / com.alp.template, rename it before building features:
- rootProject.name in settings.gradle.kts and app_name in strings.xml → the app's name (no spaces in rootProject.name).
- namespace and applicationId in app/build.gradle.kts → com.alp.<appname> (lowercase), and move
  the Kotlin files to the matching folder and package.
- Replace the placeholder launcher icon (ic_launcher_foreground/monochrome) with a simple one that fits the app.
- Rewrite README.md for the app. Skip this section in the template repo itself.

## Android gotchas
- Using notifications → request POST_NOTIFICATIONS at runtime (Android 13+).
- Must keep running with the screen off → foreground service with the right
  foregroundServiceType declared in the manifest.
- Samsung kills background apps aggressively: if the app relies on background work, show a
  hint to set Battery → Unrestricted.
- Exact alarms need SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM handling; check before relying on them.

## Tradeoff
These rules favor care over speed. For trivial changes (a text, a color), just do it.
