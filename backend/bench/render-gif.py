#!/usr/bin/env python3
"""Render a terminal transcript (e.g. crash-demo.sh output) as an animated GIF.

Terminal-style typewriter using ImageMagick frames + ffmpeg. Stdlib only
apart from the `convert` and `ffmpeg` binaries.

Usage:  python3 bench/render-gif.py <transcript.log> <out.gif>
"""
import os
import subprocess
import sys
import tempfile

W, H = 920, 600
BG = "#141011"
TITLE_BG = "#1D1617"
TITLE_FG = "#7ED4D3"
BORDER = "#3A2424"
INK = "#E9E1D6"
DIM = "#B7AC9D"
TEAL = "#31AAA9"
RED = "#E08A8A"
GOLD = "#F8E0A4"
FONT = "/usr/share/fonts/noto/NotoSansMono-Regular.ttf"
SIZE = 15
LINE_H = 23
PAD_X, TOP = 20, 52
ROWS = (H - TOP - 16) // LINE_H
FPS = 10
CPS = 80  # typewriter characters per second
PAUSE_AFTER = {"---": 10, "PASS": 12, "FAIL": 14, "===": 12}


def color_of(line):
    s = line.strip()
    if s.startswith("PASS"):
        return TEAL
    if s.startswith("FAIL"):
        return RED
    if s.startswith("==="):
        return GOLD
    if s.startswith("---"):
        return DIM
    return INK


def draw_frame(path, lines, partial, show_cursor):
    """lines: completed visible lines; partial: (text, color) in progress."""
    cmd = ["convert", "-size", f"{W}x{H}", f"xc:{BG}"]
    cmd += ["-fill", TITLE_BG, "-draw", f"rectangle 0,0 {W},36",
            "-fill", BORDER, "-draw", f"rectangle 0,36 {W},37",
            "-fill", TITLE_FG, "-font", FONT, "-pointsize", "14",
            "-annotate", "+20+24", "WAL crash-recovery demo  \u2014  kill -9, restart, replay"]
    y = TOP
    for text, color in lines:
        cmd += ["-fill", color, "-font", FONT, "-pointsize", str(SIZE),
                "-annotate", f"+{PAD_X}+{y}", text or " "]
        y += LINE_H
    if partial is not None:
        text, color = partial
        cmd += ["-fill", color, "-font", FONT, "-pointsize", str(SIZE),
                "-annotate", f"+{PAD_X}+{y}", text or " "]
        if show_cursor:
            # teal block cursor after the last typed char (mono advance ~9px at 15pt)
            cx = PAD_X + len(text) * 9 + 3
            cmd += ["-fill", TEAL, "-draw", f"rectangle {cx},{y - 12} {cx + 9},{y + 2}"]
    cmd.append(path)
    subprocess.run(cmd, check=True, capture_output=True)


def main():
    transcript, out_gif = sys.argv[1], sys.argv[2]
    with open(transcript) as f:
        raw = [ln.rstrip("\n").replace("\t", "    ") for ln in f]
    # drop trailing blank lines, cap line length
    while raw and not raw[-1].strip():
        raw.pop()
    raw = [ln[:96] for ln in raw]

    # Build event list: ("type", payload) frames at FPS
    chars_per_frame = max(1, CPS // FPS)
    events = []  # ("frame", lines_so_far, partial) expanded below
    done = []
    frames = []

    def snapshot(partial=None):
        # partial line rides below the completed window; keep total on canvas
        base = done[-(ROWS - 1):] if partial else done[-ROWS:]
        frames.append((list(base), partial))

    for line in raw:
        color = color_of(line)
        for i in range(0, len(line), chars_per_frame):
            snapshot((line[: i + chars_per_frame], color))
        done.append((line, color))
        snapshot(None)
        head = line.strip()[:4]
        for _ in range(PAUSE_AFTER.get(head, 3)):
            snapshot(None)

    with tempfile.TemporaryDirectory() as td:
        for i, (base, partial) in enumerate(frames):
            draw_frame(os.path.join(td, f"f{i:04d}.png"), base, partial, show_cursor=partial is not None)
        pal = os.path.join(td, "pal.png")
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                        "-framerate", str(FPS), "-i", os.path.join(td, "f%04d.png"),
                        "-vf", "palettegen=max_colors=48", pal], check=True)
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                        "-framerate", str(FPS), "-i", os.path.join(td, "f%04d.png"),
                        "-i", pal, "-lavfi", "paletteuse",
                        "-gifflags", "+transdiff", out_gif], check=True)
    print(f"wrote {out_gif} ({len(frames)} frames)")


if __name__ == "__main__":
    main()
