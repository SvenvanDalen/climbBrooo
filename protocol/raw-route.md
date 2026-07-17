# Raw-route side protocol (garmin-onboard) — push-only

Separate from the v3 packed climb payload in `schema.json` — this channel
ships UNANALYSED geometry to the "ClimbPro Onboard" watch app
(CIQ id `a0b1c2d3e4f50617a0b1c2d3e4f50617`), which runs the full climb
pipeline on the watch (RouteParser.mc). Producers/consumers:
`service/RawRoutePayloadBuilder.java` (called from `service/OnboardPushService.java`)
<-> `garmin-onboard/source/RawRouteStore.mc`.

Push-only: the watch never transmits anything. The phone decides when to
send, triggered by the user tapping "Verstuur naar horloge" on a route's
detail screen. Phone -> watch only:
- `{"type":"RAW_HDR","id","name","n":<pointCount>,"tot":<chunkCount>}`
- `{"type":"RAW_CHUNK","id","seq":<0-based>,"lat":[...],"lon":[...],"ele":[...]}`

Encoding: lat/lon = degrees x 100000 (Number), ele = decimeters (Number).
No distances on the wire — the watch computes cumulative distance itself.

Shared constants (change BOTH sides together):
- MAX_RAW_POINTS = 6000 (`RawRoutePayloadBuilder` / `RawRouteStore.MAX_POINTS`)
  => ~25 m point spacing on a 150 km route
- CHUNK_POINTS   = 250  (`RawRoutePayloadBuilder` / `RawRouteStore.CHUNK_POINTS`)
  => at most 24 chunks per transfer

Phone decimates by uniform stride to <= MAX_RAW_POINTS and always keeps the
last point. Chunks may arrive out of order; duplicates are ignored; the watch
parses + persists when the last chunk lands. The watch persists the arrays in
slices of 2000 points per Storage key (per-value size limits).

Delivery: because the watch only swaps the incoming transfer live when EVERY
chunk has landed and can never request a resend (push-only), the phone sends
each message blocking on the CIQ device ACK (`sendMessageToOnboardBlocking`,
10 s timeout, one retry per message) and aborts the push — reporting failure
to the user — at the first message that fails both attempts. Fire-and-forget
sends are not allowed on this channel: one silently dropped chunk stalls the
watch's pending transfer forever. Retries may duplicate a delivered message;
that is safe (duplicate seq is ignored, a duplicate RAW_HDR can only occur
before any chunk was sent and just restarts the pending transfer).

The watch's only live screen is a "5 km terrain window": the elevation
profile from the current position to +5 km (clamped to route end), with
detected-climb segments colored on top of the raw terrain line so
sub-threshold terrain (a short rise, a dike) is still visible in the shape
even when it's never colored. When the GPS fix strays more than 100 m from
the route (`OFFROUTE_M`), the last computed window keeps rendering with an
"OFF ROUTE" banner instead of freezing silently — see
`docs/superpowers/specs/2026-07-13-onboard-push-and-terrain-window-design.md`.
