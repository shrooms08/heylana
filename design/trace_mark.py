#!/usr/bin/env python3
"""Trace design/refs/heylana_logo.png into the vector brand mark and every
launcher asset.

Run this instead of hand-editing any generated path:

    python3 -m venv .venv && .venv/bin/pip install Pillow
    brew install potrace
    .venv/bin/python design/trace_mark.py

Writes design/heylana_mark.svg, design/heylana_mark_1080.png,
design/compare.png, app/src/main/res/drawable/ic_heylana_mark.xml,
the adaptive launcher layers, and the legacy mipmap PNGs.
"""

import os, re, subprocess
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "design/refs/heylana_logo.png")
VIEW = 1080.0          # square viewBox for both SVG and VectorDrawable
PAD_FRACTION = 0.08    # 8 percent padding around the mark

def prepare(scale, blur, thresh=127):
    src = Image.open(SRC).convert("L")
    bw = src.point(lambda v: 255 if v > 127 else 0, mode="L")
    bbox = bw.getbbox()
    w, h = bbox[2]-bbox[0], bbox[3]-bbox[1]
    side = max(w, h)
    # 8 percent margin measured against the finished square, so this framing
    # matches normalise() exactly and the two can be compared pixel for pixel.
    canvas = int(round(side / (1 - 2*PAD_FRACTION)))
    pad = (canvas - side) // 2
    sq = Image.new("L", (canvas, canvas), 0)
    sq.paste(bw.crop(bbox), (pad + (side-w)//2, pad + (side-h)//2))
    big = sq.resize((canvas*scale, canvas*scale), Image.BICUBIC)
    if blur:
        big = big.filter(ImageFilter.GaussianBlur(blur))
    big = big.point(lambda v: 255 if v > thresh else 0, mode="L")
    return big

def trace(bitmap, tag, alphamax, opttol):
    inv = bitmap.point(lambda v: 0 if v == 255 else 255, mode="L")
    pbm = f"/tmp/heylana_{tag}.pbm"
    svg = f"/tmp/heylana_{tag}.raw.svg"
    inv.convert("1").save(pbm)
    subprocess.run(["potrace", "-s", "-a", str(alphamax), "-O", str(opttol),
                    "-o", svg, pbm], check=True)
    return open(svg).read()

TOKEN = re.compile(r'([MmCcLlHhVvZzSsQqTtAa])|(-?\d*\.?\d+(?:[eE][-+]?\d+)?)')

def parse_path(d):
    """potrace emits M/c/l/z with implicit repetition. Returns subpaths of
    absolute points: [('M',x,y), ('C',x1,y1,x2,y2,x,y), ...]."""
    toks = [(m.group(1), m.group(2)) for m in TOKEN.finditer(d)]
    out, i, cmd = [], 0, None
    cx = cy = sx = sy = 0.0
    nums = lambda n: [float(toks[i + k][1]) for k in range(n)]
    while i < len(toks):
        if toks[i][0]:
            cmd = toks[i][0]; i += 1
            if cmd in "Zz":
                out.append(("Z",)); cx, cy = sx, sy
                continue
        if cmd in "Mm":
            x, y = nums(2); i += 2
            if cmd == "m": x, y = cx + x, cy + y
            out.append(("M", x, y)); cx, cy = sx, sy = x, y
            cmd = "L" if cmd == "M" else "l"
        elif cmd in "Cc":
            a, b, c, dd, e, f = nums(6); i += 6
            if cmd == "c":
                a, b, c, dd, e, f = cx+a, cy+b, cx+c, cy+dd, cx+e, cy+f
            out.append(("C", a, b, c, dd, e, f)); cx, cy = e, f
        elif cmd in "Ll":
            x, y = nums(2); i += 2
            if cmd == "l": x, y = cx + x, cy + y
            out.append(("L", x, y)); cx, cy = x, y
        else:
            raise ValueError(f"unsupported command {cmd!r}")
    return out

def apply_transform(cmds, tx, ty, sx, sy):
    def pt(x, y): return (x * sx + tx, y * sy + ty)
    out = []
    for c in cmds:
        if c[0] == "M" or c[0] == "L":
            out.append((c[0],) + pt(c[1], c[2]))
        elif c[0] == "C":
            out.append(("C",) + pt(c[1], c[2]) + pt(c[3], c[4]) + pt(c[5], c[6]))
        else:
            out.append(c)
    return out

def bounds(cmds):
    xs, ys = [], []
    for c in cmds:
        for k in range(1, len(c), 2):
            xs.append(c[k]); ys.append(c[k+1])
    return min(xs), min(ys), max(xs), max(ys)

def normalise(cmds):
    """Centre the mark in a VIEW square with PAD_FRACTION padding."""
    x0, y0, x1, y1 = bounds(cmds)
    w, h = x1 - x0, y1 - y0
    target = VIEW * (1 - 2 * PAD_FRACTION)
    s = target / max(w, h)
    tx = (VIEW - w * s) / 2 - x0 * s
    ty = (VIEW - h * s) / 2 - y0 * s
    return apply_transform(cmds, tx, ty, s, s)

def to_d(cmds, prec=2):
    f = lambda v: f"{v:.{prec}f}".rstrip("0").rstrip(".")
    parts = []
    for c in cmds:
        if c[0] == "Z":
            parts.append("Z")
        else:
            parts.append(c[0] + " " + " ".join(f(v) for v in c[1:]))
    return " ".join(parts)

def flatten(cmds, steps=24):
    """Subpaths as point lists, for rasterising."""
    paths, cur, cx, cy = [], [], 0.0, 0.0
    for c in cmds:
        if c[0] == "M":
            if cur: paths.append(cur)
            cur = [(c[1], c[2])]; cx, cy = c[1], c[2]
        elif c[0] == "L":
            cur.append((c[1], c[2])); cx, cy = c[1], c[2]
        elif c[0] == "C":
            x0, y0 = cx, cy
            for k in range(1, steps + 1):
                t = k / steps; u = 1 - t
                x = u**3*x0 + 3*u*u*t*c[1] + 3*u*t*t*c[3] + t**3*c[5]
                y = u**3*y0 + 3*u*u*t*c[2] + 3*u*t*t*c[4] + t**3*c[6]
                cur.append((x, y))
            cx, cy = c[5], c[6]
        elif c[0] == "Z":
            if cur: paths.append(cur); cur = []
    if cur: paths.append(cur)
    return paths

def rasterise(cmds, size):
    img = Image.new("L", (size, size), 0)
    dr = ImageDraw.Draw(img)
    k = size / VIEW
    for poly in flatten(cmds):
        if len(poly) > 2:
            dr.polygon([(x*k, y*k) for x, y in poly], fill=255)
    return img

def build(scale, blur, alphamax, opttol, tag, thresh=127):
    """The mark is several separate pieces, so every <path> potrace emits counts."""
    bmp = prepare(scale, blur, thresh)
    raw = trace(bmp, tag, alphamax, opttol)
    m = re.search(r'translate\(([\d.-]+),([\d.-]+)\) scale\(([\d.-]+),([\d.-]+)\)', raw)
    tx, ty, sx, sy = (float(g) for g in m.groups())
    cmds = []
    for d in re.findall(r'd="([^"]*)"', raw):
        cmds += apply_transform(parse_path(d), tx, ty, sx, sy)
    return normalise(cmds)

SCALE, BLUR, ALPHAMAX, OPTTOL = 2, 2.5, 1.0, 0.5

cmds = build(SCALE, BLUR, ALPHAMAX, OPTTOL, "emit")
print(f"trace: {sum(1 for c in cmds if c[0]=='M')} subpaths, "
      f"{sum(1 for c in cmds if c[0]=='C')} curve segments")

def reframe(cmds, occupancy):
    """Rescale the mark to fill `occupancy` of the VIEW square, centred."""
    x0, y0, x1, y1 = bounds(cmds)
    w, h = x1 - x0, y1 - y0
    s = (VIEW * occupancy) / max(w, h)
    return apply_transform(cmds, (VIEW - w*s)/2 - x0*s, (VIEW - h*s)/2 - y0*s, s, s)

mark_d = to_d(cmds)                                   # 84% of the square
# Adaptive icons only guarantee the middle 66% of the layer is visible.
fg_d = to_d(reframe(cmds, 0.58))

# ---------------------------------------------------------------- SVG
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="1080"
     viewBox="0 0 1080 1080" fill="none">
  <title>Heylana brand mark</title>
  <path fill="#FFFFFF" fill-rule="nonzero" d="{mark_d}"/>
</svg>
'''
open(f"{ROOT}/design/heylana_mark.svg", "w").write(svg)

def vector_drawable(size_dp, d, colour, name):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Heylana brand mark. Generated from design/refs/heylana_logo.png; edit that
     and re-run the trace rather than hand-editing this path. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:name="{name}"
    android:width="{size_dp}dp"
    android:height="{size_dp}dp"
    android:viewportWidth="1080"
    android:viewportHeight="1080">
    <path
        android:fillColor="{colour}"
        android:fillType="nonZero"
        android:pathData="{d}" />
</vector>
'''

open(f"{ROOT}/app/src/main/res/drawable/ic_heylana_mark.xml", "w").write(
    vector_drawable(88, mark_d, "#FFFFFFFF", "heylana_mark"))
open(f"{ROOT}/app/src/main/res/drawable/ic_launcher_foreground.xml", "w").write(
    vector_drawable(108, fg_d, "#FFFFFFFF", "heylana_launcher_foreground"))
open(f"{ROOT}/app/src/main/res/drawable/ic_launcher_monochrome.xml", "w").write(
    vector_drawable(108, fg_d, "#FFFFFFFF", "heylana_launcher_monochrome"))

open(f"{ROOT}/app/src/main/res/drawable/ic_launcher_background.xml", "w").write(
    '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FF000000"
        android:pathData="M0,0h108v108h-108z" />
</vector>
''')

for name in ("ic_launcher", "ic_launcher_round"):
    open(f"{ROOT}/app/src/main/res/mipmap-anydpi/{name}.xml", "w").write(
        '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
''')

# ------------------------------------------------- legacy density PNGs
def render(cmds_, size, bg, fg=255):
    img = Image.new("L", (size, size), 0)
    dr = ImageDraw.Draw(img)
    k = size / VIEW
    for poly in flatten(cmds_):
        if len(poly) > 2:
            dr.polygon([(x*k, y*k) for x, y in poly], fill=fg)
    out = Image.new("RGBA", (size, size), bg)
    white = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    out.paste(white, (0, 0), img)
    return out

def circle_mask(img):
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).ellipse([0, 0, img.size[0]-1, img.size[1]-1], fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), m)
    return out

# Legacy icons show the whole layer, so use a comfortable 68% mark.
legacy = reframe(cmds, 0.68)
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for dens, px in DENSITIES.items():
    d = f"{ROOT}/app/src/main/res/mipmap-{dens}"
    sq = render(legacy, px * 4, (0, 0, 0, 255)).resize((px, px), Image.LANCZOS)
    sq.save(f"{d}/ic_launcher.png")
    circle_mask(render(legacy, px * 4, (0, 0, 0, 255))).resize((px, px), Image.LANCZOS) \
        .save(f"{d}/ic_launcher_round.png")
    for old in ("ic_launcher.webp", "ic_launcher_round.webp"):
        p = os.path.join(d, old)
        if os.path.exists(p):
            os.remove(p)
print("wrote launcher icons for", ", ".join(DENSITIES))

# ------------------------------------------------------ verification art
def supersampled(cmds_, size, ss=4):
    """Fill with 4x supersampling so the rendered edges are smooth."""
    big = Image.new("L", (size*ss, size*ss), 0)
    dr = ImageDraw.Draw(big)
    k = size*ss / VIEW
    for poly in flatten(cmds_, steps=48):
        if len(poly) > 2:
            dr.polygon([(x*k, y*k) for x, y in poly], fill=255)
    return big.resize((size, size), Image.LANCZOS)

mark = supersampled(cmds, 1080)
Image.merge("RGB", (mark, mark, mark)).save(f"{ROOT}/design/heylana_mark_1080.png")

# The original framed exactly like the trace, so the two can be judged fairly.
original = prepare(1, 0).resize((1080, 1080), Image.LANCZOS)
overlap = Image.merge("RGB", (mark, original, Image.new("L", (1080, 1080), 0)))

def caption(dr, x, y, text):
    try:
        font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 30)
    except OSError:
        font = ImageFont.load_default()
    dr.text((x, y), text, fill=(240, 240, 245), font=font)

panels = [("ORIGINAL  heylana_logo.png", Image.merge("RGB", (original,)*3)),
          ("TRACED  heylana_mark.svg", Image.merge("RGB", (mark,)*3)),
          ("OVERLAP  yellow = identical", overlap)]
gap, head = 40, 80
out = Image.new("RGB", (1080*3 + gap*4, 1080 + gap*2 + head), (20, 20, 24))
dr = ImageDraw.Draw(out)
for i, (name, img) in enumerate(panels):
    x = gap + i*(1080 + gap)
    out.paste(img, (x, gap + head))
    caption(dr, x + 4, gap + 22, name)
out.save(f"{ROOT}/design/compare.png")
print("wrote design/compare.png", out.size)
