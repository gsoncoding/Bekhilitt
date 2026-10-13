#!/usr/bin/env python3
"""Author original, reproducible early-episode mercenary art. No input assets are read.

The humanoid joint numbers are the Epic Fight 21.17 BIPED API contract.
Geometry, UVs, face, costume and texture pixels below are authored here.
Requires Pillow only for writing PNG. Does not change Java or server data.
"""
import json
import math
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/bekhilitt"
SIZE = (256, 128)
TILE = 32
atlas = Image.new("RGBA", SIZE, (24, 29, 40, 255))
paint = ImageDraw.Draw(atlas)
materials = {}


def material(name, index, color, kind="plain"):
    x, y = (index % 8) * TILE, (index // 8) * TILE
    materials[name] = (x, y, x + 32, y + 32)
    # Deliberate 2px pixel-art brushwork; deterministic, no random noise.
    for py in range(32):
        for px in range(32):
            edge = min(px, py, 31 - px, 31 - py)
            shade = (3 if ((px // 2 + py // 3) % 5 == 0) else 0)
            if kind == "metal":
                shade += int(17 * math.cos((px - 11) / 20 * math.pi))
                shade += 15 if edge == 1 else -24 if edge == 0 else 0
            elif kind == "hair":
                shade += [3, 9, 15, 3, -9, -12, -4, 0][px % 8]
                shade -= int(py / 7)
            elif kind == "cloth":
                shade += [0, 3, 5, 0, -3, -6, -3, 0][px % 8]
            elif kind == "mail":
                shade += 18 if ((px + 2 * (py // 3)) % 5 == 1 and py % 3 != 2) else -7
            rgb = tuple(max(0, min(255, c + shade)) for c in color)
            paint.point((x + px, y + py), fill=rgb + (255,))
    return x, y


material("steel", 0, (129, 140, 145), "metal")
material("dark_steel", 1, (69, 79, 88), "metal")
material("hair", 2, (29, 29, 31), "hair")
material("skin", 3, (183, 143, 110))
fx, fy = material("face", 4, (193, 152, 119))
material("leather", 5, (92, 59, 42), "cloth")
material("trousers", 6, (47, 48, 46), "cloth")
material("mail", 7, (72, 74, 76), "mail")
material("boots", 8, (47, 35, 29), "cloth")
material("bronze", 9, (141, 115, 73), "metal")
cx, cy = material("cuirass", 10, (105, 67, 46), "cloth")
material("wrap", 11, (163, 151, 125), "cloth")
material("black", 12, (21, 22, 25))
material("scarf", 13, (90, 53, 48), "cloth")
material("plate_trim", 14, (172, 178, 176), "metal")
material("hair_side", 15, (40, 37, 36), "hair")

# Both healthy eyes; square youthful adult face, short black hair, no late-arc marks.
def rect(box, color):
    paint.rectangle((fx + box[0], fy + box[1], fx + box[2], fy + box[3]), fill=color)

rect((0, 0, 31, 3), (37, 31, 28))
rect((0, 4, 2, 25), (158, 113, 81))
rect((29, 4, 31, 25), (158, 113, 81))
rect((4, 10, 12, 12), (50, 38, 31))
rect((19, 10, 27, 12), (50, 38, 31))
rect((5, 13, 12, 15), (220, 208, 180))
rect((19, 13, 26, 15), (220, 208, 180))
rect((9, 13, 11, 16), (49, 39, 31))
rect((20, 13, 22, 16), (49, 39, 31))
rect((9, 13, 9, 13), (175, 154, 113))
rect((20, 13, 20, 13), (175, 154, 113))
rect((15, 16, 17, 22), (166, 119, 84))
rect((13, 22, 18, 22), (157, 107, 76))
rect((11, 26, 20, 26), (126, 80, 61))
rect((5, 29, 26, 31), (168, 123, 90))
# A plain diagonal carrying strap and stitched leather seams, no borrowed insignia.
paint.line((cx + 3, cy, cx + 24, cy + 31), fill=(45, 34, 29), width=6)
paint.line((cx + 3, cy, cx + 24, cy + 31), fill=(68, 46, 32), width=3)
for y in (5, 13, 21, 29):
    paint.line((cx + 1, cy + y, cx + 29, cy + y), fill=(129, 90, 57), width=1)
paint.rectangle((cx + 12, cy + 12, cx + 17, cy + 18), outline=(154, 128, 78), width=1)

positions, normals, uvs, influences, indices = [], [], [], [], []
parts = []


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def sub(a, b):
    return tuple(x - y for x, y in zip(a, b))


def panel(name, lower, upper, bone, surface, front=None):
    """Tapered box in MC bind-space: (center-x, y, center-z, half-width, half-depth)."""
    corners = []
    for x, y, z, w, d in (lower, upper):
        corners.extend([(x - w, y, z - d), (x + w, y, z - d),
                        (x + w, y, z + d), (x - w, y, z + d)])
    faces = [(0, 1, 5, 4), (2, 3, 7, 6), (3, 0, 4, 7),
             (1, 2, 6, 5), (4, 5, 6, 7), (3, 2, 1, 0)]
    start = len(indices)
    for face_number, face in enumerate(faces):
        verts = [corners[i] for i in face]
        normal = cross(sub(verts[1], verts[0]), sub(verts[2], verts[0]))
        length = math.sqrt(sum(n * n for n in normal))
        normal = tuple(n / length for n in normal)
        center = tuple(sum(c[i] for c in corners) / 8 for i in range(3))
        face_center = tuple(sum(c[i] for c in verts) / 4 for i in range(3))
        if sum(a * b for a, b in zip(normal, sub(face_center, center))) < 0:
            # Keep UV ordering, reverse only triangle winding.
            normal = tuple(-n for n in normal)
            triangles = (0, 2, 1, 0, 3, 2)
        else:
            triangles = (0, 1, 2, 0, 2, 3)
        # Front face maps bottom-left, bottom-right, top-right, top-left.
        box = materials[front if front and face_number == 0 else surface]
        x0, y0, x1, y1 = box
        tex = [(x0 + .5, y1 - .5), (x1 - .5, y1 - .5),
               (x1 - .5, y0 + .5), (x0 + .5, y0 + .5)]
        offset = len(positions) // 3
        for point, uv in zip(verts, tex):
            # JsonAssetLoader applies Blender->MC rotation; store inverse.
            positions.extend([point[0], -point[2], point[1]])
            normals.extend([normal[0], -normal[2], normal[1]])
            uvs.extend([uv[0] / SIZE[0], uv[1] / SIZE[1]])
            influences.append(bone)
        for index in triangles:
            indices.extend([offset + index] * 3)
    parts.append((name, start, len(indices)))


def box(name, x, y0, y1, z, width, depth, bone, material, front=None):
    panel(name, (x, y0, z, width / 2, depth / 2),
          (x, y1, z, width / 2, depth / 2), bone, material, front)


# BIPED joint API: torso7/chest8/head9; legs1,2/4,5; arms11,12/16,17.
box("hips", 0, .72, .88, 0, .46, .285, 7, "trousers")
panel("waist", (0, .86, 0, .212, .148), (0, 1.13, 0, .246, .172), 7, "leather")
panel("cuirass", (0, 1.095, 0, .239, .17), (0, 1.469, 0, .275, .18), 8, "leather", "cuirass")
box("belt", 0, .855, .915, -.008, .48, .32, 7, "boots")
box("buckle", -.065, .859, .91, -.177, .069, .028, 7, "bronze")
box("neck", 0, 1.455, 1.564, 0, .208, .2, 9, "skin")
box("collar", 0, 1.43, 1.5, .01, .286, .29, 8, "scarf")
panel("head", (0, 1.53, -.018, .211, .19), (0, 1.973, 0, .229, .215), 9, "skin", "face")
box("hair_base", 0, 1.926, 1.988, .008, .473, .45, 9, "hair")
box("hair_back", 0, 1.684, 1.958, .191, .476, .055, 9, "hair_side")
for i, x in enumerate([-.195, -.095, .005, .105, .205]):
    panel("hair_spike", (x, 1.948, -.052, .065, .169),
          (x + .027, 2.024 + (i % 2) * .025, -.012, .020, .084), 9, "hair")
for s in (-1, 1):
    box("ear", s * .234, 1.68, 1.79, .012, .043, .12, 9, "skin")
    box("sideburn", s * .231, 1.757, 1.964, .035, .047, .31, 9, "hair_side")
    panel("fringe", (s * .134, 1.84, -.216, .037, .023),
          (s * .144, 1.975, -.198, .079, .035), 9, "hair")
    upper, fore, thigh, shin = (11, 12, 1, 2) if s == 1 else (16, 17, 4, 5)
    x = s * .375
    panel("upper_arm", (x, 1.107, 0, .109, .114), (x, 1.449, 0, .121, .124), upper, "mail")
    panel("shoulder", (x, 1.315, 0, .143, .156), (x, 1.48, 0, .121, .15), upper,
          "steel" if s == 1 else "leather")
    box("shoulder_rim", x, 1.315, 1.343, 0, .288, .315, upper,
        "dark_steel" if s == 1 else "boots")
    box("elbow", x, 1.076, 1.135, 0, .232, .249, fore, "leather")
    panel("bracer", (x, .84, 0, .109, .117), (x, 1.094, 0, .108, .119), fore,
          "dark_steel" if s == 1 else "wrap")
    for y in (.879, 1.015):
        box("bracer_band", x, y, y + .025, 0, .225, .244, fore, "boots")
    box("hand", x, .739, .855, -.012, .21, .225, fore, "skin")
    legx = s * .126
    panel("thigh", (legx, .383, 0, .11, .13), (legx, .759, 0, .116, .135), thigh, "trousers")
    panel("boot", (legx, .077, 0, .112, .13), (legx, .388, 0, .116, .135), shin, "boots")
    box("boot_cuff", legx, .344, .392, 0, .244, .285, shin, "leather")
    box("boot_toe", legx, .016, .15, -.047, .245, .369, shin, "boots")
    box("sole", legx, 0, .035, -.046, .251, .375, shin, "black")
box("pouch", .19, .7, .885, -.177, .13, .065, 7, "leather")


def array(values, stride):
    return {"stride": stride, "count": len(values) // stride,
            "array": [round(v, 6) if isinstance(v, float) else v for v in values]}


vertices = {
    "positions": array(positions, 3), "normals": array(normals, 3),
    "uvs": array(uvs, 2), "weights": array([1.0], 1),
    "vcounts": array([1] * len(influences), 1),
    "vindices": array([v for bone in influences for v in (bone, 0)], 2),
    "indices": array(indices, 3),
}
# Integrity checks run before producing files.
assert len(positions) == len(normals) == len(influences) * 3
assert len(uvs) == len(influences) * 2 and len(indices) % 9 == 0
assert all(0 <= i < len(influences) for i in indices)
assert all(0 <= uv <= 1 for uv in uvs)
assert set(influences) <= {1, 2, 4, 5, 7, 8, 9, 11, 12, 16, 17}
assert all(math.isfinite(v) for v in positions + normals + uvs)
for i in range(0, len(normals), 3):
    assert abs(sum(n * n for n in normals[i:i + 3]) - 1) < 1e-5

model_path = ASSETS / "animmodels/entity/guts.json"
texture_path = ASSETS / "textures/entity/guts.png"
model_path.parent.mkdir(parents=True, exist_ok=True)
texture_path.parent.mkdir(parents=True, exist_ok=True)
model_path.write_text(json.dumps({"vertices": vertices}, separators=(",", ":")) + "\n")
atlas.save(texture_path)
print(f"Original mesh: {len(parts)} boxes, {len(influences)} vertices, {len(indices) // 9} triangles")
print(f"Original texture: {atlas.size}, {len(materials)} authored material tiles")
print(model_path)
print(texture_path)
