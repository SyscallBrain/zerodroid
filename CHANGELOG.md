# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- A notification when an agent finishes a turn started on this phone while
  the app is in the background: the start of its reply, or why it failed or
  stopped. Tapping it opens the chat. Turns you stop yourself stay silent.
  It has its own "Finished turns" channel.

### Fixed

- An undismissable "Waiting for your approval…" notification could stay
  behind after a turn ended, with nothing to approve.

## [0.4.1] - 2026-11-21

First public release.

### Added

- Pairing with a ZeroClaw daemon through `tools/pair-phone.sh`: a deep link
  in Termux on the same phone, or a link to paste or type for a daemon on
  another machine. Mutual TLS 1.3 with a key kept in the Android Keystore, a
  pinned CA, an encrypted token, and a short code to compare before pairing.
- Home: daemon status (memory, CPU, uptime, sessions), running subsystems and
  channels, spend, the next scheduled jobs and the agents, with pinned agents
  at the top.
- Chats with any agent across many sessions: streamed Markdown answers, tool
  calls with their arguments and output, steering and stopping a running
  turn, images, rename, delete and search, and the model in use.
- Code: sessions rooted in a folder on the daemon's machine, with a folder
  browser.
- Approvals in the Inbox and from the notification.
- A foreground service that keeps a running turn connected while the app is
  in the background.
- Config: every daemon setting, grouped, searchable and editable, with a
  restart banner after reloading the daemon.
- Release APKs built, signed and published by GitHub Actions, with
  `SHA256SUMS` and signed build provenance.

[Unreleased]: https://github.com/SyscallBrain/zerodroid/compare/v0.4.1...HEAD
[0.4.1]: https://github.com/SyscallBrain/zerodroid/releases/tag/v0.4.1
