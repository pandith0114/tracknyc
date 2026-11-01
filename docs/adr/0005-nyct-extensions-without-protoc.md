# 5. Reading the MTA extensions without a protoc build step

Date: 2026-10-28 · Status: accepted

## Context

The NYCT subway feed carries its information in protobuf *extensions*: `train_id` and `is_assigned`
on the trip descriptor (extension 1001), scheduled/actual track on each stop-time update. Without
`is_assigned` the map draws trains that do not physically exist yet; without `train_id` there is
nothing stable to show a user. The MTA railroad feeds use a different extension number again.

The usual approach is to compile the MTA's `.proto` files with `protoc` and link the generated
classes alongside `gtfs-realtime-bindings`. That means a protoc toolchain in the build and in CI,
plus regenerating whenever the extension definitions move.

## Decision

Read the extensions directly from protobuf's `UnknownFieldSet` (`NyctExtensions`). The stock
`gtfs-realtime-bindings` parse keeps unknown fields, so extension 1001 arrives as a length-
delimited blob that a `CodedInputStream` walks: field 1 string, field 2 bool, field 3 enum. Fields
the MTA adds later are skipped instead of breaking the parse.

The `.proto` files are still kept in `scripts/fixtures/proto/` — the Python fixture generator
compiles them, and they document the wire format we are relying on.

## Consequences

- No protoc in the Java build; one small, well-commented class instead of a generated package.
- The field numbering is pinned by our own tests against the recorded fixtures, so a change in the
  upstream definition shows up as a failing test rather than a silent mis-parse.
- If the MTA ever publishes a materially richer extension, generating the classes becomes
  worthwhile; `NyctExtensions` is the only place that would change.
