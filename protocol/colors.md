# Gradient → color mapping

Single source of truth for the segment color category. Both the Android phone-side `GradientColor` mapper and the Garmin watch-side renderer must agree on this table.

## Rule

Cutoffs are **inclusive lower, exclusive upper** (`[low, high)`). The mapping uses the segment's **average gradient** (not its individual point gradients).

| `colorIndex` | Gradient range (%) | Gradient range (fixed-point ×10) | Color label    | Hex (reference, 24-bit RGB) |
| ------------ | ------------------ | -------------------------------- | -------------- | --------------------------- |
| 0            | `[0, 2)`           | `[0, 20)`                        | light yellow   | `#FFF59D`                   |
| 1            | `[2, 4)`           | `[20, 40)`                       | yellow         | `#FFEB3B`                   |
| 2            | `[4, 6)`           | `[40, 60)`                       | dark yellow    | `#FBC02D`                   |
| 3            | `[6, 8)`           | `[60, 80)`                       | orange         | `#FB8C00`                   |
| 4            | `[8, 10)`          | `[80, 100)`                      | dark orange    | `#E65100`                   |
| 5            | `[10, ∞)`          | `[100, ∞)`                       | red            | `#D32F2F`                   |

**Negative gradients** (dips inside a longer climb): map to `colorIndex = 0` (light yellow). Watch-side rendering may choose to render these in a neutral tone instead — the protocol just carries the index.

## Implementation contract

- Wire payload uses **`colorIndex` (integer 0–5)**, not color names or hex codes.
- The exact hex values above are *reference* values for rendering on the watch. The Monkey C side may substitute device-appropriate equivalents (e.g. for MIP displays with limited palettes), but the **index assignment must be identical** on both sides.
- When changing the table:
  1. Update this file (the canonical mapping).
  2. Update phone-side `GradientColor.java` (Phase 2).
  3. Update watch-side color resolution in `ClimbView.mc` (Phase 6).
  4. Regenerate `protocol/examples/*.json` so the round-trip tests cover the new mapping.

## FTP intensity zones (optional second color source, issue #66)

The gradient table above stays the default and the only color source the watch needs. When the rider has set FTP and weights, the phone also sends a per-segment `zc` array: the segment's estimated power (`SegmentIntensityZones`, reusing the indoor-workout pacing model) placed in a Coggan zone, mapped onto the **same six indices** so the watch reuses its palette. The watch uses `zc` only when its *Kleurmodus* setting is *FTP-zone*.

| Coggan zone | % of FTP       | `colorIndex` | Color label  |
| ----------- | -------------- | ------------ | ------------ |
| Z1          | `< 55`         | 0            | light yellow |
| Z2          | `[55, 75)`     | 1            | yellow       |
| Z3          | `[75, 90)`     | 2            | dark yellow  |
| Z4          | `[90, 105)`    | 3            | orange       |
| Z5          | `[105, 120)`   | 4            | dark orange  |
| Z6          | `[120, 150)`   | 5            | red          |
| Z7          | `>= 150`       | 5            | red          |

Phone-side implementation: `GradientColor.forPowerZone` (zone → index) and `ZoneCalculator.POWER_ZONE_UPPER` (zone bounds). The watch never computes zones; it only picks between `segs[...colorIndex]` and `zc[i]`.

## Pseudocode (for both sides)

```text
fn colorIndex(gradientFixedPoint: int) -> int:
    if gradientFixedPoint <  20: return 0   //  < 2%
    if gradientFixedPoint <  40: return 1   //  < 4%
    if gradientFixedPoint <  60: return 2   //  < 6%
    if gradientFixedPoint <  80: return 3   //  < 8%
    if gradientFixedPoint < 100: return 4   // < 10%
    return 5                                // >= 10%
```

Negative input falls through to `colorIndex = 0` because the first comparison succeeds.
