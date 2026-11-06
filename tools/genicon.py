#!/usr/bin/env python3
"""Generate TripLogger launcher icons: white map pin (with transparent round
window and steering wheel inside) standing on a street map over deep blue.
Mirrors drawable/ic_launcher_foreground.xml + ic_launcher_background.xml."""
import os
from PIL import Image, ImageDraw

RES = "/mnt/Media/Data/source/AndroidStudioProjects/TripLogger/app/src/main/res"
BG = (30, 70, 114, 255)        # #1E4672
FG = (255, 255, 255, 255)
# roads pre-blended against BG: ImageDraw replaces pixels (no alpha blending),
# so every raster pixel stays fully opaque; the vector layer uses real
# translucent white over the base rect instead
def _blend(a):
    return tuple(int(b + (255 - b) * a) for b in BG[:3]) + (255,)
ROAD_MAIN = _blend(66 / 255)
ROAD_SEC = _blend(33 / 255)

CX, CY = 54.0, 41.45           # pin window center (108dp space)
WIN_R = 14.6
RIM_OUT, RIM_IN = 10.9, 7.9
HUB_R = 2.7
# street map: gently angled boulevards crossing near the pin tip (54,72),
# which stands on segment A
STREETS = [
    ((-4, 80.3), (112, 63.7), 4.0, "main"),
    ((63.8, 0), (88.6, 108), 4.0, "main"),
    ((-4, 45), (34, 112), 2.5, "sec"),
    ((74, 86), (104, 60), 2.5, "sec"),
]

def _cubic(p0, p1, p2, p3, n=48):
    pts = []
    for i in range(n + 1):
        t = i / n
        mt = 1 - t
        pts.append((mt**3 * p0[0] + 3 * mt**2 * t * p1[0] + 3 * mt * t**2 * p2[0] + t**3 * p3[0],
                    mt**3 * p0[1] + 3 * mt**2 * t * p1[1] + 3 * mt * t**2 * p2[1] + t**3 * p3[1]))
    return pts

def pin_outline():
    """Material 'place' silhouette scaled 2.35x, top at y=25, tip at (54,72)."""
    f = lambda x, y: (54 + (x - 12) * 2.35, 25 + (y - 2) * 2.35)
    pts = []
    pts += _cubic(f(12, 2), f(8.13, 2), f(5, 5.13), f(5, 9))
    pts += _cubic(f(5, 9), f(5, 14.25), f(12, 22), f(12, 22))
    pts += _cubic(f(12, 22), f(19, 14.25), f(19, 9), f(19, 9))
    pts += _cubic(f(19, 9), f(19, 5.13), f(16.87, 2), f(12, 2))
    return pts

def roads(d, k):
    for (x1, y1), (x2, y2), w, kind in STREETS:
        d.line([x1 * k, y1 * k, x2 * k, y2 * k], fill=ROAD_MAIN if kind == "main" else ROAD_SEC,
               width=max(1, int(round(w * k))))

def draw(s):
    """Render at scale s (pixels per 108-unit space)."""
    px = int(round(108 * s))
    k = px / 108.0
    img = Image.new("RGBA", (px, px), BG)
    roads(ImageDraw.Draw(img), k)
    white = Image.new("L", (px, px), 0)
    d = ImageDraw.Draw(white)
    d.polygon([(x * k, y * k) for x, y in pin_outline()], fill=255)
    d.ellipse([(CX - WIN_R) * k, (CY - WIN_R) * k, (CX + WIN_R) * k, (CY + WIN_R) * k], fill=0)
    d.ellipse([(CX - RIM_OUT) * k, (CY - RIM_OUT) * k, (CX + RIM_OUT) * k, (CY + RIM_OUT) * k], fill=255)
    d.ellipse([(CX - RIM_IN) * k, (CY - RIM_IN) * k, (CX + RIM_IN) * k, (CY + RIM_IN) * k], fill=0)
    sw = 1.3
    d.rectangle([(CX - RIM_IN - 1.4) * k, (CY - sw) * k, (CX + RIM_IN + 1.4) * k, (CY + sw) * k], fill=255)
    d.rectangle([(CX - sw) * k, (CY) * k, (CX + sw) * k, (CY + RIM_OUT) * k], fill=255)
    d.ellipse([(CX - HUB_R) * k, (CY - HUB_R) * k, (CX + HUB_R) * k, (CY + HUB_R) * k], fill=255)
    img.paste(Image.new("RGBA", (px, px), FG), (0, 0), white)
    return img

def save(size, path, round_):
    big = draw(size * 8 / 108.0).resize((size, size), Image.LANCZOS)
    if round_:
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(big, (0, 0), mask)
    else:
        out = big
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out.save(path)
    print(path, size)

DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for d, sz in DENS.items():
    save(sz, f"{RES}/mipmap-{d}/ic_launcher.png", round_=False)
    save(sz, f"{RES}/mipmap-{d}/ic_launcher_round.png", round_=True)

sheet = Image.new("RGBA", (700, 220), (40, 40, 40, 255))
x = 10
for sz in [192, 144, 96, 72, 48]:
    im = draw(sz / 108.0)
    sheet.paste(im, (x, (220 - sz) // 2), im)
    x += sz + 12
sheet.save("/tmp/opencode/icon_preview.png")
draw(4).save("/tmp/opencode/icon_master.png")
print("done")
