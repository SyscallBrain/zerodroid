# Release checklist

1. The gates are green: `./gradlew testDebugUnitTest assembleDebug`. The
   Release workflow also runs CI for the exact tag and blocks the release on it.
2. `versionCode` and `versionName` are bumped in `app/build.gradle.kts`.
   The Release workflow fails if the tag is not `v` + `versionName`.
3. `CHANGELOG.md` has the release section `## [X.Y.Z] - YYYY-MM-DD` (rename
   `[Unreleased]` and start a new empty one) and the compare links at the end;
   the Release workflow uses the section as the release notes and fails
   without it.
4. Screenshots in `docs/screenshots/` match the app if the UI changed.
5. Tag `vX.Y.Z` and push the tag. The Release workflow builds the R8 release
   APK, signs it, checks the signature, writes `SHA256SUMS`, attests the build
   provenance and publishes the GitHub release with `zerodroid-X.Y.Z.apk`.
   Running the workflow by hand (Actions, Release, Run workflow) does
   everything except publish; the APK is in the `dist` artifact.

## Signing key

Every release must be signed with the same key, or Android refuses to update
the app over the installed one. Keep the keystore and its passwords backed up
outside the repository.

### Once

Create the keystore (keep it out of the repository; `*.jks` is ignored):

```sh
keytool -genkeypair -v -keystore ~/keys/zerodroid-release.jks \
  -alias zerodroid -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Tiago Correia"
```

Add four repository secrets (Settings, Secrets and variables, Actions):

| Secret | Value |
|---|---|
| `ZERODROID_KEYSTORE_BASE64` | `base64 -w0 ~/keys/zerodroid-release.jks` |
| `ZERODROID_KEYSTORE_PASSWORD` | the keystore password |
| `ZERODROID_KEY_ALIAS` | `zerodroid` |
| `ZERODROID_KEY_PASSWORD` | the key password |

With the GitHub CLI:

```sh
base64 -w0 ~/keys/zerodroid-release.jks | gh secret set ZERODROID_KEYSTORE_BASE64
gh secret set ZERODROID_KEYSTORE_PASSWORD
gh secret set ZERODROID_KEY_ALIAS --body zerodroid
gh secret set ZERODROID_KEY_PASSWORD
```

### Signed builds on your machine

Add the same values to `~/.gradle/gradle.properties` (never to the
repository's `gradle.properties`):

```properties
zerodroid.release.storeFile=/home/you/keys/zerodroid-release.jks
zerodroid.release.storePassword=…
zerodroid.release.keyAlias=zerodroid
zerodroid.release.keyPassword=…
```

Then `./gradlew assembleRelease` writes a signed
`app/build/outputs/apk/release/app-release.apk`. Without them the release
APK is unsigned.
