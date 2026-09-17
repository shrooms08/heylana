#!/usr/bin/env python3
"""No visual move after landing: a frame-diff check on a connected phone.

    python3 scripts/landing_check.py right
    python3 scripts/landing_check.py left

Debug build, buddy started. Docks the disc on that side, opens the box and closes it
with the debug PANEL broadcast (no finger on the disc, no network). On the phone itself,
so there is no host latency, it blocks until `flight: landed` is logged, grabs a burst
of frames straight away — a screencap takes 100-200ms to grab one, so the first lands
around +100ms and the burst covers the first half-second — and one more from +700ms.
Every early frame must match the late one within tolerance: anything that still moves
after landing (a window sliding, a shrink, a dim, a ghost) is a difference.
Standard library only.
"""
import os, re, subprocess, sys, time

ADB = os.environ.get("ADB") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")

# Pixels that change by more than PIXEL_DELTA (summed over R, G, B), counted against the
# docked disc's own area: the disc is under 2% of the screen, so a share of the whole
# screen would never see it move. The idle disc breathes (5.5% over 3.2s), which changes a
# ring a pixel or two wide at its edge in 600ms, a few percent of its area; a slide, a
# shrink or a dim changes far more.
PIXEL_DELTA = 48
CHANGED_OF_DISC_TOLERANCE = 0.08
STATUS_BAR_PX = 120
BURST = 3
LATE_MS = 700
# The visible disc is the buddy's view less its bloom room on both sides (20dp around 88dp).
BLEED_RATIO = 20 / 88


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, check=False)


def broadcast(*extras):
    adb("shell", "am", "broadcast", "-a", "xyz.heylana.app.debug.PANEL", *extras)


def read_raw(path):
    raw = adb("exec-out", "cat", path).stdout
    w, h = int.from_bytes(raw[0:4], "little"), int.from_bytes(raw[4:8], "little")
    return w, h, raw[len(raw) - w * h * 4:]


def capture_after_landing():
    burst = "; ".join(
        "echo s%d $(date +%%s.%%N) >> /sdcard/landed.txt; screencap /sdcard/early%d.raw; "
        "echo e%d $(date +%%s.%%N) >> /sdcard/landed.txt" % (i, i, i)
        for i in range(BURST)
    )
    script = (
        "rm -f /sdcard/landed.txt; logcat -c; "
        "am broadcast -a xyz.heylana.app.debug.PANEL --ez toggle true >/dev/null; "
        "logcat -v epoch -m 1 -s HeylanaState -e 'flight: landed' > /sdcard/landed.txt; "
        + burst + "; "
        "sleep 0.05; echo late $(date +%s.%N) >> /sdcard/landed.txt; screencap /sdcard/late.raw"
    )
    adb("shell", script)
    text = adb("exec-out", "cat", "/sdcard/landed.txt").stdout.decode(errors="ignore")
    found = re.search(r"(\d+\.\d+)\s+\d+\s+\d+ I HeylanaState: flight: landed x=(-?\d+) y=(-?\d+) size=(\d+)", text)
    if not found:
        raise SystemExit("no 'flight: landed' line:\n" + text)
    landed_at = float(found.group(1))
    x, y, size = (int(v) for v in found.groups()[1:])
    marks = {k: (float(v) - landed_at) * 1000 for k, v in re.findall(r"(s\d|e\d|late) (\d+\.\d+)", text)}
    frames = []
    w = h = 0
    for i in range(BURST):
        w, h, pixels = read_raw("/sdcard/early%d.raw" % i)
        frames.append((marks["s%d" % i], marks["e%d" % i], pixels))
    _, _, late = read_raw("/sdcard/late.raw")
    adb("shell", "rm -f /sdcard/landed.txt /sdcard/early*.raw /sdcard/late.raw")
    return x, y, size, w, h, frames, marks["late"], late


def changed_pixels(a, b, w, box):
    """Pixels that differ by more than PIXEL_DELTA, sampled every other row and column and scaled back up."""
    x0, y0, x1, y1 = box
    changed = 0
    for yy in range(y0, y1, 2):
        row = yy * w
        for xx in range(x0, x1, 2):
            i = (row + xx) * 4
            if abs(a[i] - b[i]) + abs(a[i + 1] - b[i + 1]) + abs(a[i + 2] - b[i + 2]) > PIXEL_DELTA:
                changed += 1
    return changed * 4


def main():
    side = sys.argv[1] if len(sys.argv) > 1 else "right"
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(1.0)
    broadcast("--es", "dock", side)
    time.sleep(1.0)
    broadcast("--ez", "toggle", "true")  # open
    time.sleep(2.5)
    x, y, size, w, h, frames, late_ms, late = capture_after_landing()  # close
    if late_ms < LATE_MS:
        raise SystemExit("late frame too early: +%dms" % late_ms)
    # The whole screen below the status bar: a sliding window starts far from the dock.
    box = (0, STATUS_BAR_PX, w, h)
    disc = size / (1 + 2 * BLEED_RATIO)
    worst = 0.0
    for start_ms, end_ms, pixels in frames:
        share = changed_pixels(pixels, late, w, box) / (disc * disc)
        print("  frame +%dms..+%dms vs +%dms: changed pixels %.0f%% of the disc's area" % (start_ms, end_ms, late_ms, share * 100))
        worst = max(worst, share)
    ok = worst <= CHANGED_OF_DISC_TOLERANCE
    print("%s dock: landed x=%d y=%d size=%d; worst %.0f%% of the disc (max %.0f%%): %s"
          % (side, x, y, size, worst * 100, CHANGED_OF_DISC_TOLERANCE * 100, "PASS" if ok else "FAIL"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
