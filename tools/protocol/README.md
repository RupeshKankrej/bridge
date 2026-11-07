# Protocol schemas

One JSON Schema (draft 2020-12) per message type, named after the wire type: `clipboard.set.json`, `hello.json`, …
`envelope.json` describes the common envelope. Keep these in sync with the `@Serializable` models in `core/src/commonMain/kotlin/bridge/protocol/`; a test in `core` validates every model against its schema.
