# ADR 0004 — Control-message codec and the `error` payload

**Status:** accepted · 2026-11-07

## Context
M1.1 introduces the envelope and the first message types. Spec §4 listed `error` as `code, message, replyTo`,
but the envelope already carries `replyTo`, and no schema existed for `error` or `pong`. The schema test needs a
JSON Schema validator, and none is in spec §8.

## Decision
- `error` payload is `{ code, message }`. `code` is a stable snake_case string (e.g. `unsupported_type`).
  The failed request is identified by the envelope's `replyTo`, never duplicated in the payload.
- Every wire type has its own schema file, including `pong.json` (identical to `ping.json`).
- Payloads are a sealed `Message` hierarchy; each subclass's `@SerialName` is its wire `type`. The codec moves
  that discriminator between the payload and the envelope's `type` field, so the type table is the sealed class.
- Decoding ignores unknown payload fields (tolerant reader), so adding an optional field is not a breaking
  change and needs no protocol version bump. Schemas stay strict (`additionalProperties: false`) about what we emit.
- Schema validation in tests uses a small in-repo validator (`core/src/jvmTest/.../SchemaValidator.kt`) that
  supports only the keywords our schemas use and fails on any other keyword. No new dependency.

## Consequences
- The Kotlin class for `error` is `ProtocolError`, not `Error`, to avoid shadowing `kotlin.Error`.
- A schema using `$ref`, `oneOf`, etc. makes the test fail until the validator is extended or replaced.
