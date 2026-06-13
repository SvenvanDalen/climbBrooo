# Protocol notes

Companion document to `schema.json`. The schema is the canonical source — this file explains *why* the schema is shaped the way it is.

## Versioning

The `v` field (currently `1`) is required and gates compatibility. On a breaking change:

- Bump `v` in the schema.
- Both producer (Android) and consumer (Monkey C) must check `v` and reject (or down-convert) unknown versions rather than silently mis-parsing.
- Update `protocol/examples/*.json` to the new version.

Within a major version, only **additive** changes are allowed (new optional fields). Renaming, removing, or re-typing a field is a breaking change.

## Why fixed-point gradients

Gradients are stored as integers representing `percent × 10`. So `7.2%` is `72`.

- **Why not float?** Floats serialize larger (variable-length text on the wire when JSON, or 4 bytes vs 1–2 bytes when binary) and round inconsistently across Java and Monkey C.
- **Why ×10?** 0.1% is below the resolution of GPS elevation noise, so we lose no signal. ×10 fits any realistic gradient (-20.0 to +100.0%) in a signed byte for segments (`-200` to `+1000`) and an unsigned byte for climbs (`30` to `1000`).
- The producer must round half-away-from-zero. The consumer treats the value as already-rounded.

## Why colorIndex instead of color names

`Idea.md`'s example uses `"color": "yellow"`. We deliberately changed this to an integer index:

- Strings cost bytes — `"yellow"` is 8 chars vs `1`.
- String mappings drift. A `colorIndex` ties phone and watch to one shared table (`colors.md`).
- The watch's rendering layer is free to pick a device-appropriate hex for each index without involving the protocol.

## Mode-dependent fields

The schema does **not** use JSON Schema's `oneOf` / `if-then-else` to enforce that route-mode payloads carry `routeId`/`startDistance` and radius-mode payloads carry `startLat`/`startLon`. The reason: `jsonschema2pojo` has weak support for those constructs and tends to generate odd class hierarchies.

Instead:

- Both sets of fields are optional in the schema.
- The producer (`ClimbPayloadBuilder` in Phase 4) enforces the contract.
- The round-trip test (`ProtocolRoundTripTest`) loads each example and asserts the mode-specific fields are populated correctly.
- The consumer must tolerate missing optional fields gracefully.

## Wire keys for packed encoding

When a payload is serialized for the wire, logical JSON objects are packed into compact integer arrays:

- `surfSec` — packed int array of user-defined surface sections: `[startDistance, endDistance, surfaceType, …]`, 3 ints per section, ordered by startDistance. Route mode only. Sent in a dedicated lean payload (with `"climbs": []`) to the surface datafield app, not in the climb datafield payload.
- `tsec` — packed int array of per-segment target times in whole seconds, one int per segment, parallel to `segs` (same length). Route mode only. Optional per climb; omitted when no pacing plan is available.

## Byte budget

Watch-side cap is **8 KB** (configurable; tuned in Phase 7 after measurement). Estimated cost of a representative payload, JSON-encoded with the schema as-is:

- Envelope overhead: ~40 bytes
- Per climb (no name): ~150 bytes (`startDistance`, `endDistance`, `length`, `elevationGain`, `avgGradient`, 12-segment array)
- Per segment: ~60 bytes (`distance`, `elevationGain`, `gradient`, `colorIndex`)

So ~150 + 12 × 60 = ~870 bytes per climb. 8 KB ÷ 870 ≈ **~9 climbs comfortably**, more if names are omitted and JSON is minified (no whitespace).

When a pacing plan is present, the optional `tsec` array adds ~1 int per segment — roughly **+70–80 bytes per climb** (minified). This narrows the comfortable climb count slightly but stays well within 8 KB for typical routes.

If the budget ever feels tight, options in priority order:
1. Drop optional `name` fields.
2. Switch to a CBOR or MessagePack encoding (would require updating both decoders — not trivial).
3. Drop `colorIndex` and have the watch compute it from `gradient` (saves ~12 bytes per segment).

## Validation

The schema is loaded at test time by `com.networknt.json-schema-validator` against the draft-07 spec. Each example in `protocol/examples/` is validated as part of `ProtocolRoundTripTest`. If you add a new example, add it to the test's parameterized inputs.

## Change log

| Version | Date       | Change                                   |
| ------- | ---------- | ---------------------------------------- |
| 1       | 2026-06-13 | Added optional `tsec` (targetSeconds) packed int array on Climb — per-segment target time in whole seconds, parallel to `segments` (wire key `segs`). Route-mode only, omitted when no pacing plan. ~13 ints per climb; additive, no version bump. |
| 1       | 2026-05-20 | Initial schema. Two modes, fixed-point gradients, color index, byte budget 8 KB. |
