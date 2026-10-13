#!/usr/bin/env python3
"""Author original, reproducible commander art. No input assets are read.

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


material("steel", 0, (161, 179, 194), "metal")
material("dark_steel", 1, (84, 105, 126), "metal")
material("ivory", 2, (215, 222, 225), "hair")
material("shadow_hair", 3, (178, 192, 209), "hair")
material("skin", 4, (225, 197, 182))
fx, fy = material("face", 5, (231, 205, 189))
material("navy", 6, (30, 44, 65), "cloth")
material("mail", 7, (57, 68, 83), "mail")
material("leather", 8, (44, 38, 38), "cloth")
material("gold", 9, (164, 141, 92), "metal")
cx, cy = material("breastplate", 10, (171, 189, 203), "metal")
material("ivory_cloth", 11, (185, 194, 203), "cloth")
material("black", 12, (24, 29, 38))
material("blue_gem", 13, (54, 91, 121), "metal")
material("plate_trim", 14, (207, 215, 218), "metal")
material("cape", 15, (44, 57, 79), "cloth")

# Original restrained face, designed at native pixel-art scale.
def rect(box, color):
    paint.rectangle((fx + box[0], fy + box[1], fx + box[2], fy + box[3]), fill=color)

rect((0, 0, 31, 3), (207, 209, 212))
rect((0, 3, 2, 21), (192, 173, 167))
rect((29, 3, 31, 21), (192, 173, 167))
rect((4, 10, 12, 10), (124, 130, 145))
rect((19, 10, 27, 10), (124, 130, 145))
rect((5, 12, 12, 15), (244, 240, 231))
rect((19, 12, 26, 15), (244, 240, 231))
rect((9, 12, 11, 15), (87, 109, 145))
rect((20, 12, 22, 15), (87, 109, 145))
rect((10, 13, 10, 15), (31, 42, 62))
rect((21, 13, 21, 15), (31, 42, 62))
rect((9, 12, 9, 12), (233, 243, 250))
rect((20, 12, 20, 12), (233, 243, 250))
rect((4, 11, 12, 11), (96, 91, 102))
rect((19, 11, 27, 11), (96, 91, 102))
rect((15, 15, 16, 21), (209, 175, 158))
rect((13, 21, 17, 21), (201, 166, 152))
rect((11, 25, 20, 25), (163, 119, 117))
rect((13, 26, 18, 26), (207, 164, 155))
rect((5, 29, 26, 31), (211, 182, 166))
# Etched wing-like geometric ornament, not a copied insignia.
for side in (-1, 1):
    for n in range(4):
        start = (cx + 15 + side * 2, cy + 12 + n * 3)
        end = (cx + 15 + side * (11 - n), cy + 7 + n * 3)
        paint.line((start, end), fill=(101, 124, 149), width=1)
paint.polygon([(cx + 15, cy + 8), (cx + 19, cy + 15),
               (cx + 15, cy + 21), (cx + 11, cy + 15)], fill=(190, 167, 112))
paint.polygon([(cx + 15, cy + 11), (cx + 17, cy + 15),
               (cx + 15, cy + 18), (cx + 13, cy + 15)], fill=(62, 95, 131))

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


# Joint contract: torso 7, chest 8, head 9; right leg 1/2,
# left leg 4/5; right upper/forearm 11/12, left upper/forearm 16/17.
box("hips", 0, .71, .89, 0, .43, .27, 7, "navy")
panel("waist", (0, .86, 0, .205, .145), (0, 1.14, 0, .224, .16), 7, "dark_steel")
panel("breastplate", (0, 1.09, -.005, .215, .157), (0, 1.47, 0, .264, .165), 8, "steel", "breastplate")
box("belt", 0, .845, .91, -.014, .465, .326, 7, "leather")
box("buckle", 0, .852, .907, -.189, .068, .018, 7, "gold")
box("gorget", 0, 1.43, 1.5, 0, .235, .31, 8, "plate_trim")
box("neck", 0, 1.46, 1.565, 0, .17, .18, 9, "skin")
panel("face", (0, 1.53, -.014, .186, .179), (0, 1.978, 0, .219, .211), 9, "skin", "face")
box("hair_crown", 0, 1.935, 2.018, .004, .472, .462, 9, "ivory")
panel("hair_back", (0, 1.265, .192, .214, .06), (0, 1.985, .182, .242, .084), 9, "ivory")
for s in (-1, 1):
    box("ear", s * .220, 1.674, 1.797, .012, .037, .108, 9, "skin")
    panel("hair_side", (s * .221, 1.38, .09, .057, .093),
          (s * .231, 1.981, .043, .034, .182), 9, "ivory")
    panel("front_lock", (s * .178, 1.635, -.229, .023, .023),
          (s * .122, 1.976, -.219, .066, .030), 9, "ivory")
    panel("back_lock", (s * .125, 1.224, .224, .042, .026),
          (s * .136, 1.705, .259, .078, .028), 9, "shadow_hair")
    upper, fore, thigh, shin = (11, 12, 1, 2) if s == 1 else (16, 17, 4, 5)
    x = s * .367
    panel("upper_arm", (x, 1.107, 0, .102, .111), (x, 1.452, 0, .118, .126), upper, "mail")
    panel("pauldron", (x, 1.305, 0, .147, .154), (x, 1.482, 0, .111, .143), upper, "steel")
    box("pauldron_rim", x, 1.299, 1.33, 0, .296, .308, upper, "plate_trim")
    box("elbow", x, 1.079, 1.16, -.01, .225, .257, fore, "dark_steel")
    panel("bracer", (x, .819, 0, .109, .116), (x, 1.109, 0, .101, .118), fore, "steel")
    box("gauntlet", x, .733, .854, -.008, .209, .225, fore, "dark_steel")
    legx = s * .126
    panel("thigh", (legx, .39, 0, .105, .124), (legx, .755, 0, .113, .128), thigh, "dark_steel")
    box("kneecap", legx, .352, .454, -.123, .19, .054, shin, "plate_trim")
    panel("greave", (legx, .075, 0, .106, .121), (legx, .377, 0, .103, .132), shin, "steel")
    box("boot", legx, .015, .137, -.043, .232, .352, shin, "dark_steel")
    box("sole", legx, 0, .035, -.041, .236, .362, shin, "black")
    panel("tasset", (s * .15, .651, -.168, .099, .035),
          (s * .153, .851, -.164, .10, .032), thigh, "steel")
    # Short mantle rather than untested simulated cape cloth.
    panel("mantle", (s * .132, .87, .197, .115, .032),
          (s * .142, 1.425, .205, .152, .04), 8, "cape")
    box("mantle_clasp", s * .203, 1.422, 1.47, -.178, .053, .023, 8, "gold")
box("tabard", 0, .582, .852, -.18, .17, .027, 7, "ivory_cloth")


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

model_path = ASSETS / "animmodels/entity/griffith.json"
texture_path = ASSETS / "textures/entity/griffith.png"
model_path.parent.mkdir(parents=True, exist_ok=True)
texture_path.parent.mkdir(parents=True, exist_ok=True)
model_path.write_text(json.dumps({"vertices": vertices}, separators=(",", ":")) + "\n")
atlas.save(texture_path)
print(f"Original mesh: {len(parts)} boxes, {len(influences)} vertices, {len(indices) // 9} triangles")
print(f"Original texture: {atlas.size}, {len(materials)} authored material tiles")
print(model_path)
print(texture_path)
