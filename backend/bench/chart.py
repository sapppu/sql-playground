#!/usr/bin/env python3
"""Log-log SVG chart for bench/join-benchmark.csv. Stdlib only.

Usage:  python3 bench/chart.py [input.csv] [output.svg]
Theme matches the app: teal hash_join, maroon nested_loop.
"""
import csv
import math
import sys

TEAL = "#31AAA9"
MAROON = "#6C1A1A"
INK = "#26251F"
FAINT = "#857F73"
GRID = "#D8D0BE"
BG = "#FCFBF7"

W, H = 860, 380
PAD_L, PAD_R, PAD_T, PAD_B = 64, 16, 34, 44
PANEL_GAP = 60


def load(path):
    rows = {"scale": {}, "threshold": {}}
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            exp = r["experiment"]
            x = int(r["dept_rows"] if exp == "threshold" else r["emp_rows"])
            rows[exp].setdefault(r["strategy"], []).append((x, float(r["median_ms"])))
    for exp in rows:
        for s in rows[exp]:
            rows[exp][s].sort()
    return rows


def panel_svg(x0, width, title, series, x_ticks, y_range, show_ylabel, threshold_x=None):
    h = H - PAD_T - PAD_B
    lo_y, hi_y = (math.log10(v) for v in y_range)

    def X(v):
        t = (math.log10(v) - math.log10(x_ticks[0])) / (math.log10(x_ticks[-1]) - math.log10(x_ticks[0]))
        return x0 + 8 + t * (width - 16)

    def Y(v):
        t = (math.log10(v) - lo_y) / (hi_y - lo_y)
        return PAD_T + (1 - t) * h

    parts = [f'<text x="{x0 + width / 2}" y="20" text-anchor="middle" font-size="14" font-weight="600" fill="{INK}" font-family="sans-serif">{title}</text>']

    # gridlines + y ticks
    y = 10 ** math.floor(lo_y)
    while y <= 10 ** hi_y * 1.001:
        if y >= 10 ** lo_y / 1.001:
            yy = Y(y)
            parts.append(f'<line x1="{x0}" y1="{yy:.1f}" x2="{x0 + width}" y2="{yy:.1f}" stroke="{GRID}" stroke-width="1"/>')
            if show_ylabel:
                lab = f"{y:g}"
                parts.append(f'<text x="{x0 - 8}" y="{yy + 4:.1f}" text-anchor="end" font-size="11" fill="{FAINT}" font-family="sans-serif">{lab}</text>')
        y *= 10
    if show_ylabel:
        parts.append(f'<text x="14" y="{PAD_T + h / 2}" text-anchor="middle" font-size="11" fill="{FAINT}" font-family="sans-serif" transform="rotate(-90 14 {PAD_T + h / 2})">median ms (log)</text>')

    # x ticks
    for v in x_ticks:
        xx = X(v)
        lab = f"{v // 1000}k" if v >= 1000 else str(v)
        parts.append(f'<text x="{xx:.1f}" y="{PAD_T + h + 18}" text-anchor="middle" font-size="11" fill="{FAINT}" font-family="sans-serif">{lab}</text>')

    # threshold marker
    if threshold_x is not None:
        xx = X(threshold_x)
        parts.append(f'<line x1="{xx:.1f}" y1="{PAD_T}" x2="{xx:.1f}" y2="{PAD_T + h}" stroke="{FAINT}" stroke-width="1" stroke-dasharray="4 3"/>')
        parts.append(f'<text x="{xx + 4:.1f}" y="{PAD_T + 12}" font-size="10" fill="{FAINT}" font-family="sans-serif">threshold 200</text>')

    # series
    for label, color, pts in series:
        d = " ".join(f"{'M' if i == 0 else 'L'}{X(x):.1f},{Y(y):.1f}" for i, (x, y) in enumerate(pts))
        parts.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="2.5"/>')
        for x, y in pts:
            parts.append(f'<circle cx="{X(x):.1f}" cy="{Y(y):.1f}" r="4" fill="{color}"/>')
        lx, ly = pts[-1]
        ex = X(lx)
        if ex + 82 > x0 + width:
            parts.append(f'<text x="{ex - 8:.1f}" y="{Y(ly) + 4:.1f}" text-anchor="end" font-size="11" font-weight="600" fill="{color}" font-family="sans-serif">{label}</text>')
        else:
            parts.append(f'<text x="{ex + 8:.1f}" y="{Y(ly) + 4:.1f}" font-size="11" font-weight="600" fill="{color}" font-family="sans-serif">{label}</text>')

    # x label
    parts.append(f'<text x="{x0 + width / 2}" y="{PAD_T + h + 34}" text-anchor="middle" font-size="11" fill="{FAINT}" font-family="sans-serif">rows (log scale)</text>')
    return "\n".join(parts)


def legend():
    items = [(TEAL, "hash_join"), (MAROON, "nested_loop")]
    s = ""
    x = W / 2 - 130
    for color, label in items:
        s += f'<line x1="{x}" y1="{H - 8}" x2="{x + 22}" y2="{H - 8}" stroke="{color}" stroke-width="2.5"/>'
        s += f'<circle cx="{x + 11}" cy="{H - 8}" r="4" fill="{color}"/>'
        s += f'<text x="{x + 28}" y="{H - 4}" font-size="12" fill="{INK}" font-family="sans-serif">{label}</text>'
        x += 130
    return s


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "bench/join-benchmark.csv"
    dst = sys.argv[2] if len(sys.argv) > 2 else "bench/join-benchmark.svg"
    data = load(src)
    pw = (W - PAD_L - PAD_R - PANEL_GAP) / 2

    scale = data["scale"]
    thresh = data["threshold"]

    p1 = panel_svg(PAD_L, pw, "Scale: employees joined to 3 departments",
                   [("hash_join", TEAL, scale["hash_join"]), ("nested_loop", MAROON, scale["nested_loop"])],
                   [10, 100, 1000, 10000, 100000], (0.1, 1000), True)
    p2 = panel_svg(PAD_L + pw + PANEL_GAP, pw, "Build side sweep: 2000 employees x N departments",
                   [("hash_join", TEAL, thresh["hash_join"]), ("nested_loop", MAROON, thresh["nested_loop"])],
                   [10, 100, 1000, 2000], (0.5, 10000), False, threshold_x=200)

    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">'
           f'<rect width="{W}" height="{H}" fill="{BG}"/>\n{p1}\n{p2}\n{legend()}</svg>')
    with open(dst, "w") as f:
        f.write(svg)
    print("wrote", dst)


if __name__ == "__main__":
    main()
