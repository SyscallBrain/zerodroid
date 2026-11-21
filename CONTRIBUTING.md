# Contributing to ZeroDroid

Thanks for helping. Bug reports, ideas and pull requests are all welcome.
Questions go to [Discussions](https://github.com/SyscallBrain/zerodroid/discussions).
For security problems, please follow [SECURITY.md](SECURITY.md) instead of
opening an issue. For anything bigger than a small fix, please open an issue
first so we can agree on the approach before you spend time on it.
Everyone taking part is expected to follow the
[Code of Conduct](CODE_OF_CONDUCT.md).

## Getting started

You need JDK 21 and the Android SDK with platform 37.2 (Android Studio
installs both; set `ANDROID_HOME` for command-line builds).

```sh
git clone https://github.com/SyscallBrain/zerodroid
cd zerodroid
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

To try it you need a ZeroClaw daemon, 0.9.0 or newer, and to pair the phone
with `tools/pair-phone.sh` (see the README). Debug builds are signed with
your debug key, so they cannot update a release build: uninstall the release
first, then pair again.

Build types:

| Type | Use |
|---|---|
| `debug` | development; includes the connection diagnostics in Config |
| `perf` | measuring performance: R8 and baseline profiles like a release, signed with the debug key so it installs over a debug build |
| `release` | what the Release workflow publishes |

Crashes are written to `Download/crashlogs/` on the phone, so you can read
them without adb.

## Before you open a pull request

Run the same checks as CI:

```sh
./gradlew testDebugUnitTest assembleDebug
```

- Logic that can run on the JVM (protocol types, parsing, models) gets unit
  tests in `app/src/test/`. Fixtures captured from a real daemon go in
  `app/src/test/resources/fixtures/`, with tokens, host names and paths
  removed.
- UI changes are tried on a real phone; add screenshots to the pull request
  and refresh `docs/screenshots/` if a screen in the README changed.
- Add a line under `[Unreleased]` in [CHANGELOG.md](CHANGELOG.md).

## Code guidelines

- Talk to the daemon only through its RPC plane, with the types in
  `rpc/proto/` written from ZeroClaw's `zeroclaw-rpc-proto` crate.
- Never weaken the transport: no option to skip TLS verification, no
  plaintext tokens, no exportable keys, and never log a pairing link, token
  or payload.
- Nothing is approved without an explicit tap.
- Errors become messages on screen, not crashes.
- Code, UI text, docs and commit messages are in English.
