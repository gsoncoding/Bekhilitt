#!/usr/bin/env python3
"""Create Casca's original pixel-art atlas. No external images are read.

UV rectangles match the explicitly authored boxes in CascaBodyRenderer.
Short dark hair, brown skin, practical leather/linen clothing and boots.
"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src/main/resources/assets/bekhilitt/textures/entity/casca.png"
image = Image.new("RGBA", (128, 128), (33, 28, 29, 255))
draw = ImageDraw.Draw(image)
SKIN = (148, 96, 70)
HAIR = (33, 25, 29)
LEATHER = (110, 64, 42)
LINEN = (165, 151, 123)
TROUSERS = (72, 67, 57)
BOOT = (49, 36, 32)


def region(x, y, w, h, color, style="plain"):
    for row in range(h):
        for col in range(w):
            edge = min(row, col, h - 1 - row, w - 1 - col)
            shade = 0
            if style == "hair":
                shade = (3, 7, 1, -3, 0)[col % 5] - row // 4
            elif style == "cloth":
                shade = (1, 4, 1, -3, -1)[col % 5]
            elif style == "leather":
                shade = 10 if edge == 0 else (col + row * 2) % 4 - 2
            rgb = tuple(max(0, min(255, value + shade)) for value in color)
            draw.point((x + col, y + row), fill=rgb + (255,))


def box(u, v, w, h, d, color, style="plain"):
    # Standard vanilla cube net. Extra one-pixel padding protects fractional boxes.
    region(u, v, 2 * (w + d) + 1, d + h + 1, color, style)
    return u + d, v + d, w, h


face = box(0, 0, 7, 8, 7, SKIN)
torso = box(32, 0, 7, 12, 4, LEATHER, "leather")
arm = box(64, 0, 3, 12, 4, LINEN, "cloth")
leg = box(80, 0, 3, 12, 4, TROUSERS, "cloth")
box(0, 24, 8, 2, 8, HAIR, "hair")
box(34, 24, 8, 6, 2, HAIR, "hair")
box(58, 24, 1, 5, 6, HAIR, "hair")
box(76, 24, 3, 3, 1, HAIR, "hair")
box(86, 24, 3, 2, 1, HAIR, "hair")
box(0, 42, 5, 2, 5, LINEN, "cloth")
box(24, 42, 8, 1, 5, BOOT, "leather")
box(52, 42, 2, 2, 1, (158, 133, 82), "leather")
box(60, 42, 2, 3, 1, (92, 58, 38), "leather")
box(0, 58, 4, 5, 5, BOOT, "leather")

# Face painted specifically into the front seven-by-eight-pixel UV rectangle.
fx, fy, _, _ = face
face_pixels = [
    "hhhhhhh",
    "sssssss",
    "sbbSbbs",
    "sepspes",
    "sssNsss",
    "ssnSnss",
    "sssmmss",
    "sssssss",
]
colors = {
    "h": (51, 32, 30), "s": SKIN, "S": (161, 108, 78),
    "b": (61, 37, 33), "e": (220, 195, 150), "p": (40, 29, 26),
    "N": (173, 115, 78), "n": (120, 73, 52), "m": (120, 67, 54),
}
for y, row in enumerate(face_pixels):
    for x, symbol in enumerate(row):
        draw.point((fx + x, fy + y), fill=colors[symbol])

# Worn, fully covered leather vest; muted stitching and a central seam.
tx, ty, tw, th = torso
for y in range(th):
    draw.point((tx + 3, ty + y), fill=(78, 47, 36))
    if y % 3 == 1:
        draw.point((tx + 2, ty + y), fill=(175, 139, 87))
        draw.point((tx + 4, ty + y), fill=(175, 139, 87))
for x in (0, 6):
    for y in range(8):
        draw.point((tx + x, ty + y), fill=(146, 99, 59))
draw.line((tx, ty + 9, tx + 6, ty + 9), fill=(47, 35, 29))

# Wrap sleeve bands around all four sides, with exposed hands only at cuffs.
for start, width in [(64, 4), (68, 3), (71, 4), (75, 3)]:
    region(start, 4 + 6, width, 3, (86, 58, 41), "leather")
    region(start, 4 + 9, width, 3, SKIN)

assert image.size == (128, 128)
assert image.getextrema()[3] == (255, 255)
OUT.parent.mkdir(parents=True, exist_ok=True)
image.save(OUT)
print(f"Original Casca texture: {OUT}, {image.size}")
