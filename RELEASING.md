# Releasing FlatDocScanner

## One-time setup

### 1. The upload key

Release builds are signed with `keystore/flatdocscanner-upload.jks`. Its passwords are in
`keystore.properties` in the project root. **Both are git-ignored and exist only on the machine
that created them.**

**Back up both files now**, somewhere safe and offline (a password manager can hold the
`.jks` file and the passwords). With Google Play App Signing this is only the _upload_ key, so
if it is lost Google can reset it after a support request, but without it you cannot upload
until they do. Outside Play, losing it means users can never install an update.

To use the key on another computer, copy both files to the same places.

### 2. Google Play Console

1. Create the app. Package name: `io.github.ogshrug.flatdocscanner` (permanent).
2. Turn on **Play App Signing** (the default) when you upload the first bundle.
3. **Privacy policy**: publish `PRIVACY.md` somewhere public (for example GitHub Pages, or
   make the repository public and link to the file) and paste the URL.
4. **Data safety** form, from what the app actually does:
   - Does the app collect or share user data? **No.**
   - (The app has no internet permission; everything runs on the device.)
5. **App access**: all functionality is available without special access or login.
6. **Ads**: no ads.
7. **Content rating**: questionnaire answers are all "no" (utility app, no user-generated
   content shared with others, no communication features).
8. **Target audience**: 13+ is the simplest choice for a general utility.
9. **Store listing**: draft text in `docs/store-listing.md`. Screenshots: take them on a phone
   (Play needs at least two phone screenshots), plus a 512×512 icon and a 1024×500 feature
   graphic.

## Every release

1. Bump `versionCode` (must increase every upload) and `versionName` in `app/build.gradle.kts`.
2. Run the checks:
   ```bash
   ./gradlew :app:testDebugUnitTest :app:lintRelease
   ./gradlew :app:connectedDebugAndroidTest      # with a phone or emulator connected
   ```
3. Build the bundle:
   ```bash
   ./gradlew :app:bundleRelease
   ```
   Output: `app/build/outputs/bundle/release/app-release.aab`.
4. **Smoke-test the release build on a real phone before uploading.** R8 shrinking can break
   libraries that are only used by name (it broke ML Kit text recognition once; the keep
   rules are in `app/src/main/keepRules/rules.keep`). At minimum: scan a page, book mode,
   curved page, recognise text, export a PDF.
   ```bash
   ./gradlew :app:assembleRelease
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
5. Keep `app/build/outputs/mapping/release/mapping.txt` for each release: it turns crash
   stack traces from users back into readable code (Play Console accepts it as the
   deobfuscation file).
6. Upload the `.aab` to a testing track first (internal testing), then promote.
