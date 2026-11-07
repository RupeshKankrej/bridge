# ADR 0007 — LocalSend over a StreamPort, multicast discovery, client-certificate trust

**Status:** accepted · 2026-11-07

## Context
M1.4 asked for the LocalSend v2.1 server and client in `core` with Ktor (CIO), advertised as `_localsend._tcp`, with
auto-accept for paired peers and a Bridge-only resume extension. Checking that against the platforms and the
LocalSend protocol (https://github.com/localsend/protocol):
- The Ktor CIO server has no TLS on any platform, and the CIO client supports only TLS 1.2 (ADR 0005). LocalSend
  uses HTTPS by default and hard rule 5 requires TLS 1.3.
- LocalSend discovers devices with a multicast UDP announce to 224.0.0.167:53317, answered by `POST /register` or a
  multicast reply. It doesn't use mDNS, so `_localsend._tcp` would never make Bridge show up in LocalSend.
- Over HTTPS, LocalSend ignores the `fingerprint` field in request bodies, and LocalSend clients present no TLS
  certificate. A claimed fingerprint can't prove a sender is a paired peer: anyone on the LAN could claim to be the
  Mac and push files to the tablet without a prompt.

## Decision
- `core` owns the LocalSend logic and a minimal HTTP/1.1 reader and writer (Content-Length and chunked bodies,
  server keep-alive, one request per client connection). TLS comes from a new **`StreamPort`** (callback byte
  streams, like `TransportPort`): `NWListener`/`NWConnection` with TLS on macOS, `SSLServerSocket`/`SSLSocket` on
  Android. No Ktor; no new dependency.
- A **`MulticastPort`** joins LocalSend's group. `_localsend._tcp` is not advertised.
- **Two ports** (amended in M1.4b): 53317 is plain LocalSend and never asks for a client certificate, so every
  offer there gets the Accept / Decline prompt. 53319 serves the same API but requires a client certificate, and
  refuses (403) any certificate not in the trusted list. A Bridge device sends to a paired peer on 53319,
  presenting its identity certificate; that peer auto-accepts and allows resume. The client side pins each server
  to the fingerprint it announced. The first version asked for an *optional* client certificate on one port, but
  `sec_protocol_options_set_peer_authentication_optional` is `API_UNAVAILABLE(macos)`: a Network.framework server
  either requires a certificate (locking out LocalSend) or never asks for one.
- Resume is opt-in by the server, per session: the `prepare-upload` response on 53319 carries
  `X-Bridge-Resume: 1`. Then `HEAD /upload?…` returns `Upload-Offset` (bytes received so far), and `POST /upload`
  with an `Upload-Offset` header appends from there; a mismatch gets 409 with the right offset. Header names follow
  the IETF resumable-uploads draft rather than `Range`, which is a response-side header. The receiver keeps a
  trusted session with a partial file for 60 s without uploads; the sender retries every 2 s, up to 30 times in a
  row without progress.
- A **`FileStorePort`** writes into the receive folder: a temporary name, moved into place on success, deleted
  otherwise. `OutgoingFile`/`FileSource` read files to send. Both are blocking and called off the main thread.
- Swift names: the stream type is `ByteStream` (Foundation already has `Stream`) and `StreamPort` dials with
  `dial`, because a second `connect(host:port:callback:)` would clash with `TransportPort`'s Objective-C selector.
- The interop test runs `core` with JDK TLS adapters against the official LocalSend app, with a person clicking
  in LocalSend. LocalSend has no CLI, so it isn't in CI.

## Consequences
- Byte-level LocalSend compatibility is ours to keep: `FilesContractTest` covers raw LocalSend requests (chunked
  upload, keep-alive, wrong token), `/info` fields, prompts, path sanitising and resume.
- LocalSend devices in HTTP mode (encryption off) aren't listed. Not supported: the PIN, the `sha256` check
  (optional in the protocol), the reverse/download API, and progress events on the control channel.
- Discovered LocalSend devices never expire from the list; a refresh re-announces.
- On macOS, Swift must convert between `Data` and Kotlin `ByteArray` with a bulk copy (a helper in `appleMain`,
  M1.4b), not byte by byte, to reach 40 MB/s.
- Ports in use: 53317 (LocalSend TCP + multicast UDP), 53318 (control), 53319 (paired files).
- M1.4 is split: M1.4a (`core`, this ADR), M1.4b (Mac adapters and UI), M1.4c (Android adapters and UI). The
  device gate runs after M1.4c.
