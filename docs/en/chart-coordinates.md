# Chart Coordinate Conventions

This document fixes the coordinate conventions used by the structured
chart data model (ADR-008). Renderers, editors, importers, and
exporters all share this convention.

## Rectangular Grid (`RECT_GRID`)

- `x` increases to the **right**. `x = 0` is the leftmost column.
- `y` increases **upward**. `y = 0` is the bottom row (row 1).
- Origin `(0, 0)` is the bottom-left cell.

Knitting charts are conventionally read from bottom to top
(the first row worked is at the bottom of the chart). Our coordinate
system mirrors that reading direction so that a cell's coordinates
match what a knitter would call "row 3, stitch 7" on paper.

### Rendering note

Screen coordinates typically have `y` increasing downward. The
Phase 31 Compose and SwiftUI renderers apply a `scaleY(-1)` (plus a
translation equal to the chart height) at the viewport layer to map
chart coordinates to screen coordinates. Consumers of the domain
model (repositories, use cases, importers) always work in chart
coordinates — the flip happens only at the draw call.

### Multi-cell symbols

A cell with `width > 1` and/or `height > 1` occupies the rectangle
`[x, x + width) × [y, y + height)` — extending to the right and
upward from the anchor `(x, y)`.

A 2×2 cable cross at `(x = 3, y = 5)` covers columns 3, 4 and
rows 5, 6.

## Polar / Round (`POLAR_ROUND`)

- `x` is the **stitch index within the ring**, 0-based, increasing
  clockwise when viewed from outside the work.
- `y` is the **ring index**, 0-based, increasing **outward** from
  the centre.
- `y = 0` is the innermost ring (the cast-on / first round).
- `ChartExtents.Polar.stitchesPerRing[y]` gives the number of
  stitches in ring `y`; `x` is taken modulo this value.

### Rendering note

Phase 31 polar renderer maps `(x, y)` to screen via
`angle = 2π · x / stitchesPerRing[y]` and `radius = baseRadius + y · ringSpacing`.
The direction of angle increase (clockwise vs counter-clockwise) is a
renderer decision; the data model itself is direction-agnostic.

### Multi-cell symbols

Multi-cell symbols in polar mode are **deferred to Phase 35**
(editor advanced). Phase 29 accepts `width = height = 1` only for
polar charts. The domain model carries `width` and `height` anyway
so the schema does not change.

## Rotation

`ChartCell.rotation` is in degrees, values `0`, `90`, `180`, `270`.
Rotation is clockwise in chart coordinates (equivalently,
counter-clockwise in screen coordinates after the `scaleY(-1)`
flip).

Non-axis-aligned rotations are intentionally not supported in v1.

## Default values

| Field | Default |
|---|---|
| `width` | `1` |
| `height` | `1` |
| `rotation` | `0` |
| `colorId` | `null` |
| `ChartLayer.visible` | `true` |
| `ChartLayer.locked` | `false` |

## Bounding box

`ChartExtents.Rect` uses **inclusive** bounds on both sides:
`minX ≤ x ≤ maxX`, `minY ≤ y ≤ maxY`. An empty chart has
`minX = 0, maxX = -1` (a canonical empty range) — not `null` — so
that the extents are always a well-formed `Rect`.
