#!/usr/bin/env python3
"""Adaptive icon for Oguri's Printer, following the Steps/Alarms 1.2
recipe: sampled solid background layer + approved artwork foreground
centered at ~68% of the 108dp canvas with alpha-feathered square
edges, plus a simulated Pixel circle/squircle preview. The artwork's
white outer corners are flood-filled with the sampled background
colour first."""
from PIL import Image, ImageDraw, ImageFilter
import os

APP = os.path.expanduser("~/workspace/printer-app")
SRC = os.path.join(APP, "build", "icon-candidates",
                   "media-generation-oguris-printer-icon-v2-0-7e8e7572-d06b-4bac-a538-88f9943a36a2.webp")

img = Image.open(SRC).convert("RGB")
w, h = img.size
print("master size", w, h)
# Sample the background from points just inside the rounded shape.
# (Top and both sides: the bottom-centre point lands on the character.)
samples = [img.getpixel((w//2, int(h*0.03))), img.getpixel((int(w*0.04), h//2)),
           img.getpixel((int(w*0.96), h//2))]
bg = tuple(sum(s[i] for s in samples)//len(samples) for i in range(3))
print("sampled BG", bg, "#%02X%02X%02X" % bg)
BG = (bg[0], bg[1], bg[2], 255)

# Flood-fill the white corners with BG.
for corner in [(0,0),(w-1,0),(0,h-1),(w-1,h-1)]:
    ImageDraw.floodfill(img, corner, bg, thresh=48)
master = img.convert("RGBA")
master.save(os.path.join(APP, "build", "icon-master.png"))

# --- foreground at 68%, feathered (Alarms recipe) -------------------------
FRACTION = 0.68
SS = 4
canvas_hi = 432 * SS
art_hi = int(round(canvas_hi * FRACTION))
feather = max(8, int(art_hi * 0.12))
art = master.resize((art_hi, art_hi), Image.LANCZOS)
mask = Image.new("L", (art_hi, art_hi), 0)
d = ImageDraw.Draw(mask)
inset = feather // 2
d.rectangle([inset, inset, art_hi - inset - 1, art_hi - inset - 1], fill=255)
mask = mask.filter(ImageFilter.GaussianBlur(feather / 2.0))
art.putalpha(mask)
fg_hi = Image.new("RGBA", (canvas_hi, canvas_hi), (0,0,0,0))
off = (canvas_hi - art_hi) // 2
fg_hi.alpha_composite(art, (off, off))

DENSITIES = {"mipmap-mdpi": (48,108), "mipmap-hdpi": (72,162),
             "mipmap-xhdpi": (96,216), "mipmap-xxhdpi": (144,324),
             "mipmap-xxxhdpi": (192,432)}
for folder, (legacy, fg) in DENSITIES.items():
    out_dir = os.path.join(APP, "res", folder)
    os.makedirs(out_dir, exist_ok=True)
    fg_hi.resize((fg, fg), Image.LANCZOS).save(os.path.join(out_dir, "ic_launcher_foreground.png"))
    master.resize((legacy, legacy), Image.LANCZOS).convert("RGB").save(os.path.join(out_dir, "ic_launcher.png"))
    print("wrote", folder)

# --- Pixel mask preview ----------------------------------------------------
PREVIEW = 192
def apply_mask(imgc, kind):
    m = Image.new("L", (PREVIEW*SS, PREVIEW*SS), 0)
    px = m.load(); s = PREVIEW*SS
    for y in range(s):
        for x in range(s):
            nx = (x+0.5)/s*2-1; ny = (y+0.5)/s*2-1
            inside = (nx*nx+ny*ny <= 1.0) if kind == "circle" else (abs(nx)**4 + abs(ny)**4 <= 1.0)
            px[x, y] = 255 if inside else 0
    m = m.resize((PREVIEW, PREVIEW), Image.LANCZOS)
    out = imgc.copy(); out.putalpha(m); return out

def render(kind):
    bgimg = Image.new("RGBA", (PREVIEW, PREVIEW), BG)
    comp = Image.alpha_composite(bgimg, fg_hi.resize((PREVIEW, PREVIEW), Image.LANCZOS))
    return apply_mask(comp, kind)

preview = Image.new("RGBA", (PREVIEW*2+24, PREVIEW+16), (24,24,32,255))
preview.alpha_composite(render("circle"), (8,8))
preview.alpha_composite(render("squircle"), (16+PREVIEW, 8))
preview.save(os.path.join(APP, "icon-preview-1.1.png"))
print("wrote icon-preview-1.1.png")
