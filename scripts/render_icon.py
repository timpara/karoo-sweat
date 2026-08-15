#!/usr/bin/env python3
"""Render the extension icon PNG used by the Karoo companion app.

The companion app reads `iconUrl` from the release manifest and fetches a raster
image over the network, so it cannot use the Android vector drawable directly.
This script rasterises the same droplet path as `app/src/main/res/drawable/ic_sweat.xml`
into `docs/icon.png` so the two never drift apart.

Pure standard library on purpose: the repo already asks contributors for a JDK and
an Android SDK, and adding Pillow or a native rasteriser to that list to produce one
static file is a poor trade. Run it only when the icon changes.

    python3 scripts/render_icon.py

Output is deterministic, so a no-op run leaves the working tree clean.
"""

from __future__ import annotations

import re
import struct
import sys
import zlib
from pathlib import Path

# --- Configuration -----------------------------------------------------------

SIZE = 512
"""Output edge length in pixels. 512 is the conventional store-icon size and gives
the companion app room to downscale cleanly."""

SUPERSAMPLE = 4
"""Linear supersampling factor. 4 means 16 samples per output pixel, which is
enough to keep the droplet's curved shoulders free of visible stair-stepping."""

CORNER_RADIUS = 96
"""Rounded-square radius, proportionally close to the Android adaptive-icon mask so
the icon does not look foreign next to system apps."""

BACKGROUND = (0x0D, 0x47, 0xA1)
"""Deep blue. Deliberately not one of the palette colours in `values/colors.xml`:
those encode hydration status (green OK, orange warn, red critical) and reusing one
would imply a permanent status. Blue reads as water without making a claim."""

DROPLET = (0xFF, 0xFF, 0xFF)

DROPLET_SCALE = 0.60
"""Droplet height as a fraction of the icon edge, leaving a margin that survives the
circular masking some launchers and list views apply."""

# The outer subpath of ic_sweat.xml, in its native 24x24 viewport. Only the outer
# contour is used: the drawable is a 2px-stroke outline suited to a 24dp data-field
# glyph, but at icon sizes a solid silhouette stays legible when downscaled to the
# ~48px the companion app list actually renders.
DROPLET_PATH = (
    "M12,2C12,2 5.5,9.2 5.5,14C5.5,17.6 8.4,20.5 12,20.5"
    "C15.6,20.5 18.5,17.6 18.5,14C18.5,9.2 12,2 12,2z"
)
VIEWPORT = 24.0

BEZIER_STEPS = 24
"""Line segments per cubic. The droplet has four curves, so this yields ~96 edges,
well past the point where more makes any difference at 512px."""


# --- Path handling -----------------------------------------------------------

def parse_path(data: str) -> list[list[tuple[float, float]]]:
    """Flatten an SVG path into polygons.

    Supports only the commands the droplet actually uses (absolute M, C and z). This
    is not a general SVG parser and should not be extended into one: if the icon ever
    needs more, rasterise it with a real toolchain instead.
    """
    tokens = re.findall(r"[MCz]|-?\d*\.?\d+", data)
    polygons: list[list[tuple[float, float]]] = []
    current: list[tuple[float, float]] = []
    point = (0.0, 0.0)
    index = 0

    while index < len(tokens):
        command = tokens[index]
        index += 1

        if command == "M":
            if current:
                polygons.append(current)
            point = (float(tokens[index]), float(tokens[index + 1]))
            index += 2
            current = [point]

        elif command == "C":
            control_a = (float(tokens[index]), float(tokens[index + 1]))
            control_b = (float(tokens[index + 2]), float(tokens[index + 3]))
            end = (float(tokens[index + 4]), float(tokens[index + 5]))
            index += 6
            current.extend(flatten_cubic(point, control_a, control_b, end))
            point = end

        elif command == "z":
            if current:
                polygons.append(current)
                current = []

        else:
            raise ValueError(f"unsupported path command: {command!r}")

    if current:
        polygons.append(current)
    return polygons


def flatten_cubic(
    start: tuple[float, float],
    control_a: tuple[float, float],
    control_b: tuple[float, float],
    end: tuple[float, float],
) -> list[tuple[float, float]]:
    """Sample a cubic Bezier, excluding the start point (already in the polygon)."""
    points = []
    for step in range(1, BEZIER_STEPS + 1):
        t = step / BEZIER_STEPS
        u = 1.0 - t
        x = (
            u * u * u * start[0]
            + 3 * u * u * t * control_a[0]
            + 3 * u * t * t * control_b[0]
            + t * t * t * end[0]
        )
        y = (
            u * u * u * start[1]
            + 3 * u * u * t * control_a[1]
            + 3 * u * t * t * control_b[1]
            + t * t * t * end[1]
        )
        points.append((x, y))
    return points


def build_edges(
    polygons: list[list[tuple[float, float]]],
    scale: float,
    offset_x: float,
    offset_y: float,
) -> list[tuple[float, float, float, float, int]]:
    """Transform polygons into (x0, y0, x1, y1, winding) edges in sample space.

    Horizontal edges are dropped: they contribute nothing to a scanline crossing test
    and would otherwise need special-casing to avoid a divide by zero.
    """
    edges = []
    for polygon in polygons:
        for i in range(len(polygon)):
            x0, y0 = polygon[i]
            x1, y1 = polygon[(i + 1) % len(polygon)]
            if y0 == y1:
                continue
            edges.append(
                (
                    x0 * scale + offset_x,
                    y0 * scale + offset_y,
                    x1 * scale + offset_x,
                    y1 * scale + offset_y,
                    1 if y1 > y0 else -1,
                )
            )
    return edges


def scanline_coverage(
    edges: list[tuple[float, float, float, float, int]], dimension: int
) -> list[float]:
    """Rasterise edges to per-pixel coverage in 0..1 using nonzero winding.

    Samples at the centre of each subpixel, matching the fill rule Android vector
    drawables use by default.
    """
    samples = dimension * SUPERSAMPLE
    accumulator = [0] * (dimension * dimension)

    for sample_y in range(samples):
        y = sample_y + 0.5
        crossings = []
        for x0, y0, x1, y1, winding in edges:
            if (y0 <= y < y1) or (y1 <= y < y0):
                x = x0 + (y - y0) * (x1 - x0) / (y1 - y0)
                crossings.append((x, winding))
        if not crossings:
            continue
        crossings.sort()

        depth = 0
        row = (sample_y // SUPERSAMPLE) * dimension
        for i in range(len(crossings) - 1):
            depth += crossings[i][1]
            if depth == 0:
                continue
            start = crossings[i][0]
            end = crossings[i + 1][0]
            first = max(0, int(start))
            last = min(samples - 1, int(end))
            for sample_x in range(first, last + 1):
                if start <= sample_x + 0.5 < end:
                    accumulator[row + sample_x // SUPERSAMPLE] += 1

    divisor = float(SUPERSAMPLE * SUPERSAMPLE)
    return [count / divisor for count in accumulator]


def rounded_square_coverage(dimension: int, radius: int) -> list[float]:
    """Coverage mask for a rounded square, supersampled to match the droplet."""
    samples = dimension * SUPERSAMPLE
    r = radius * SUPERSAMPLE
    accumulator = [0] * (dimension * dimension)

    for sample_y in range(samples):
        y = sample_y + 0.5
        row = (sample_y // SUPERSAMPLE) * dimension
        # Distance from the nearest horizontal edge, clamped into the corner arc.
        dy = max(r - y, y - (samples - r), 0.0)
        for sample_x in range(samples):
            x = sample_x + 0.5
            dx = max(r - x, x - (samples - r), 0.0)
            if dx * dx + dy * dy <= r * r:
                accumulator[row + sample_x // SUPERSAMPLE] += 1

    divisor = float(SUPERSAMPLE * SUPERSAMPLE)
    return [count / divisor for count in accumulator]


# --- PNG output --------------------------------------------------------------

def write_png(path: Path, pixels: bytes, dimension: int) -> None:
    """Write a straight-alpha RGBA PNG. No filtering, since the image is tiny."""

    def chunk(tag: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + tag
            + payload
            + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
        )

    raw = bytearray()
    stride = dimension * 4
    for y in range(dimension):
        raw.append(0)  # filter type 0 (None)
        raw.extend(pixels[y * stride : (y + 1) * stride])

    header = struct.pack(">IIBBBBB", dimension, dimension, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )
    path.write_bytes(png)


# --- Composition -------------------------------------------------------------

def main() -> int:
    polygons = parse_path(DROPLET_PATH)

    target_height = SIZE * DROPLET_SCALE
    scale = (target_height / VIEWPORT) * SUPERSAMPLE
    # Centre the 24x24 viewport in the icon.
    offset = (SIZE * SUPERSAMPLE - VIEWPORT * scale) / 2.0

    droplet = scanline_coverage(build_edges(polygons, scale, offset, offset), SIZE)
    background = rounded_square_coverage(SIZE, CORNER_RADIUS)

    pixels = bytearray()
    for i in range(SIZE * SIZE):
        bg = background[i]
        fg = min(droplet[i], bg)
        if bg <= 0.0:
            pixels.extend((0, 0, 0, 0))
            continue
        # Composite droplet over background, then premultiply nothing: alpha is the
        # background mask, colour is the blend of the two opaque layers.
        colour = tuple(
            round(BACKGROUND[c] * (1.0 - fg / bg) + DROPLET[c] * (fg / bg))
            for c in range(3)
        )
        pixels.extend((*colour, round(bg * 255)))

    output = Path(__file__).resolve().parent.parent / "docs" / "icon.png"
    output.parent.mkdir(parents=True, exist_ok=True)
    write_png(output, bytes(pixels), SIZE)
    print(f"wrote {output} ({output.stat().st_size} bytes, {SIZE}x{SIZE})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
