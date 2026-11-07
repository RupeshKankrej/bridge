# ADR 0002 — File transfer uses the LocalSend v2.1 protocol

**Status:** accepted · 2026-11-06

## Context
File sharing must work between Mac and tablet today, and with iPhone, Windows and Linux without a Bridge client on those.

## Decision
Implement the LocalSend v2.1 HTTP protocol byte-compatibly on port 53317 and advertise `_localsend._tcp`. Bridge-only extensions (resume, folder paths, progress events) are opt-in between Bridge peers and never change base-endpoint behaviour.

## Consequences
- Every existing LocalSend client interoperates on day one.
- Protocol is pinned to v2.1; an interop test runs in CI against the latest LocalSend release.
