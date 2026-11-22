# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.8.0] - 2026-11-22

### Added

- Canvas: what agents draw with the `canvas` tool in the phone's chats and
  code sessions is saved on the phone, per daemon, with up to 50 frames per
  canvas, so it survives daemon restarts (the daemon keeps canvases in
  memory only). Open them from a Home card (which can be hidden), from the
  canvas tool call while it is on screen, or from a canvas icon at the top
  of the chat that drew them. HTML and SVG open in a sandboxed web view with
  pinch to zoom, and pages wider than the phone are zoomed out to fit;
  Markdown and text are shown natively. Chat history comes back from the
  daemon as text only, so in chats a frame is saved only when the app sees
  it live.

## [0.7.0] - 2026-11-22

### Added

- Workflows: build, edit and run the daemon's SOPs from the phone. Steps sit
  in a vertical flow wired by tapping (next step, failure path, branches by
  condition, steps that wait for others), each step opens in a sheet with its
  instructions, suggested tools and the outputs of earlier steps, and runs and
  their approvals are followed live. The app can turn the daemon's SOP engine
  on, and a setting hides the Workflows card on Home.

### Changed

- The chat header shows only what the agent is doing. The model (without
  the provider prefix) and the reasoning level now sit above the composer,
  where long model ids are no longer cut off, and context usage is a ring
  around Send.

### Fixed

- Unpairing and pairing the same daemon again no longer loses the chats and
  code sessions started from the phone: the app keeps its identity on that
  daemon across an unpair. If the daemon refuses the identity, the app gets
  a fresh one instead of stopping at an authentication error.

## [0.6.0] - 2026-11-22

### Added

- Sign in with OIDC, like `zerocode login`, for daemons behind an identity
  provider: enter the host and the provider alias, approve in the browser, and
  no pairing link is needed. A gateway with a certificate Android does not
  trust shows its SHA-256 fingerprint, and ZeroDroid pins that exact
  certificate once you accept it.
- A banner, and a notification while an agent works in the background, asks
  you to renew the sign-in five minutes before it expires. Renewing keeps a
  running turn going.

## [0.5.0] - 2026-11-22

### Added

- A notification when an agent finishes a turn started on this phone while
  the app is in the background: the start of its reply, or why it failed or
  stopped. Tapping it opens the chat, straight away even when the app was
  closed. Turns you stop yourself stay silent.
  It has its own "Finished turns" channel.

### Fixed

- An undismissable "Waiting for your approval…" notification could stay
  behind after a turn ended, with nothing to approve.
- Tall bottom sheets, such as the Code session menu, bounced on their own
  when dragged near the top of the screen.

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

[Unreleased]: https://github.com/SyscallBrain/zerodroid/compare/v0.8.0...HEAD
[0.8.0]: https://github.com/SyscallBrain/zerodroid/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/SyscallBrain/zerodroid/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/SyscallBrain/zerodroid/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/SyscallBrain/zerodroid/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/SyscallBrain/zerodroid/releases/tag/v0.4.1
