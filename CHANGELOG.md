# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.9.8] - 2026-11-26

### Added

- New "Show reasoning" switch in a chat's Display settings (the session sheet):
  off hides the model's thinking (Thought rows) from the transcript while the
  reply, tool calls and the header's live "thinking" status stay as they were.
  The choice is remembered for every chat.

## [0.9.7] - 2026-11-25

### Added

- Long-pressing a chat in the Chats list opens a menu with Rename and Delete:
  a chat can now be renamed right from the list (`session/rename`, no need to
  open it first), and a chat already open in the app shows the new name at once.
- A new chat is named automatically from its first message (first non-blank
  line, whitespace collapsed, capped at 60 characters) with `session/rename`,
  so it no longer sits in the Chats list as "Chat with <agent>". Code sessions
  (the daemon cannot rename those), image-only first messages and chats the
  user already renamed keep their name.

## [0.9.6] - 2026-11-24

### Added

- Chat answers the agent's ask_user/poll questions with choice cards
  (single choice or multi-select poll) sent back over the RPC channel. The
  socket advertises the elicitation capability on initialize and re-attaches
  open sessions on reconnect, so questions keep arriving.
- New Skills menu from the Home shortcuts (hideable in Config > Phone >
  Home): pick an agent and see its skills grouped by bundle with a stable
  per-bundle color chip, search them, and edit a bundle skill's SKILL.md
  frontmatter fields and Markdown body with save and delete through the
  daemon's skills RPC. Workspace skills are listed read-only.

## [0.9.5] - 2026-11-23

### Fixed

- The Home CPU tile shows the daemon's CPU figure as zerocode does: summed
  across cores, like `top`, with one decimal rounded the same way. It used to
  divide it by the number of cores, so it read a fraction of zerocode's
  number for the same daemon. The bar still shows the share of all cores.

## [0.9.4] - 2026-11-23

### Fixed

- Bottom sheets with a text field (New chat, the chat's agent and model
  pickers, the automation scheduler, running a workflow) stay above the
  keyboard; typing in their search or input used to hide the sheet behind it.
- Screens under the tabs end at the top of the keyboard and the tab bar steps
  aside while typing, so a field near the bottom (the last field of a config
  section) scrolls into view instead of staying under the keyboard. A focused
  field shows its whole box, not just the cursor line.

### Changed

- Compose BOM 2026.11.01 and Gradle 9.9.2. Text fields and bottom sheets
  moved to the current Compose APIs (state-based text fields,
  `rememberBottomSheetState`); typing, the keyboard's Done/Go key and
  inserting `{{steps.N}}` in workflow instructions behave as before.

## [0.9.3] - 2026-11-23

### Added

- File edits as a diff: when an agent edits or writes a file (`file_edit`,
  `file_write`), the tool call opens with the file's path, the +/− line
  counts and a line diff, removed lines in red and added lines in green,
  with three lines of context and longer unchanged runs folded. A file
  written from scratch shows every line as added. The raw arguments are
  still there behind "Show arguments". Works in Code and in chats, live and
  in reopened history.

## [0.9.2] - 2026-11-23

### Fixed

- Chat: selecting text across inline code (the blue `code` spans) now shows
  the selection; the span's opaque background used to paint over it. The
  colours are unchanged.
- Config → ZeroDroid & pairing → Home: the four switches have room between
  them, so a two-line caption no longer touches the next row or the switch.
  They are now named by what they show (Workflows, Canvas, Automation,
  Doctor) instead of "Show … card".

## [0.9.1] - 2026-11-23

### Added

- Doctor: run ZeroClaw's doctor from the phone and read its findings grouped
  by category, filtered by severity. A finding about a channel or a model,
  TTS or transcription provider has a Fix button that opens that entry in
  Config, and Back walks up through the section it landed in. Home shows the
  last run (the doctor never runs on its own) and its shortcut can be hidden.

### Changed

- Home: Automation, Workflows, Canvas and Doctor are one list of shortcuts
  instead of four full-width cards, with the status pill on the right and
  whatever needs a look (red first, then amber) at the top.
- Home: the memory, CPU, uptime and sessions tiles in each row are the same
  height, and the memory and CPU bars sit on the same line.
- Home: with pinned agents, three other agents are listed before "See all"
  instead of six.
- The CPU tile shows one decimal under 10 % and "<0.1%" for a trace load, so
  an idle daemon no longer reads as 0 %.

### Fixed

- Up next: a long job name no longer runs into its time.

### Daemon

- In Termux on a phone without root, ZeroClaw cannot read `/proc/stat` and
  reports 0 % CPU and no core count. The `zerodroid/android-process-cpu`
  patch (included in `android-master-zerodroid`) fixes it; see the README.

## [0.9.0] - 2026-11-22

### Added

- Automation: the daemon's scheduled jobs on the phone, as on the web
  dashboard's Cron page. Create shell or agent jobs with a cron schedule and
  timezone (agent jobs also take a model, session, allowed tools and
  memory), deliver the result to one of the agent's channels, edit, run now,
  pause, resume and delete jobs, and read each run's status, duration and
  full output. The scheduler's own settings are there too. A Home card shows
  the job count and any failed or degraded last runs, and can be hidden.
  Automation needs the `zerodroid/cron-rpc-parity` daemon patch (included in
  `android-master-zerodroid`); without it the app lists and runs jobs but
  refuses, with a message, whatever the daemon can't do over RPC. If you
  don't want to patch the daemon, don't use Automation and hide its card.

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

[Unreleased]: https://github.com/SyscallBrain/zerodroid/compare/v0.9.8...HEAD
[0.9.8]: https://github.com/SyscallBrain/zerodroid/compare/v0.9.7...v0.9.8
[0.9.7]: https://github.com/SyscallBrain/zerodroid/compare/v0.9.6...v0.9.7
[0.9.6]: https://github.com/SyscallBrain/zerodroid/compare/v0.9.5...v0.9.6
[0.9.5]: https://github.com/SyscallBrain/zerodroid/compare/v0.9.4...v0.9.5
[0.9.4]: https://github.com/SyscallBrain/zerodroid/compare/v0.9.3...v0.9.4
[0.9.3]: https://github.com/SyscallBrain/zerodroid/compare/v0.9.2...v0.9.3
[0.9.2]: https://github.com/SyscallBrain/zerodroid/compare/v0.9.1...v0.9.2
[0.9.1]: https://github.com/SyscallBrain/zerodroid/compare/v0.9.0...v0.9.1
[0.9.0]: https://github.com/SyscallBrain/zerodroid/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/SyscallBrain/zerodroid/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/SyscallBrain/zerodroid/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/SyscallBrain/zerodroid/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/SyscallBrain/zerodroid/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/SyscallBrain/zerodroid/releases/tag/v0.4.1
