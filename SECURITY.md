# Security policy

ZeroDroid holds a client certificate and a bearer token for your ZeroClaw
daemon, and can start agent turns, answer approvals and change the daemon's
configuration, so security reports are taken seriously.

## Reporting a vulnerability

Please report vulnerabilities privately through
[GitHub's security advisories](https://github.com/SyscallBrain/zerodroid/security/advisories/new),
not in a public issue. Include the ZeroDroid version (Config → ZeroDroid &
pairing), the ZeroClaw version, your phone and Android version, and the steps
to reproduce. Never include a real pairing link or token.

You can expect a first answer within a week. Once a fix is released, the
advisory is published with credit to you, unless you prefer otherwise.

## Scope

In scope: the app and `tools/pair-phone.sh`. For example, a way to pair
without the user confirming the code, to read the token or the client key, to
get the app to trust a server whose CA it did not pin, or to approve an action
without an explicit tap.

ZeroDroid is a client for the ZeroClaw daemon. Problems in the daemon itself,
its RPC plane, enrollment or gateway belong to
[ZeroClaw's security policy](https://github.com/zeroclaw-labs/zeroclaw/security/policy).

## Supported versions

Only the latest release receives security fixes.
