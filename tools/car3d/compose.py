"""Parked picture = car + soft floor reflection, cropped to the same box for every colour."""
import sys, json, os
from PIL import Image
import numpy as np

SRC, OUT = sys.argv[1], sys.argv[2]
COLOURS = ["white", "silver", "grey", "black", "blue", "red", "bronze", "green"]
os.makedirs(OUT, exist_ok=True)

def fade(refl, y0, length, strength):
    a = np.asarray(refl).astype(np.float32)
    h = a.shape[0]
    ys = np.arange(h, dtype=np.float32)[:, None]
    t = np.clip((ys - y0) / length, 0, 1)
    f = strength * (1 - t) ** 2
    f[ys[:, 0] < y0] = strength
    a[..., 3] *= f
    return Image.fromarray(a.clip(0, 255).astype(np.uint8), "RGBA")

boxes = []
for c in COLOURS:
    park = Image.open(f"{SRC}/park_{c}.png").convert("RGBA")
    refl = Image.open(f"{SRC}/refl_{c}.png").convert("RGBA")
    y0 = refl.getbbox()[1]
    out = Image.new("RGBA", park.size, (0, 0, 0, 0))
    out.alpha_composite(fade(refl, y0 + 40, 300, 0.30))
    out.alpha_composite(park)
    out.save(f"/tmp/full_{c}.png")
    a = np.asarray(out)[..., 3]
    ys, xs = np.where(a > 6)
    boxes.append((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1))
x0 = min(b[0] for b in boxes) - 8; y0 = min(b[1] for b in boxes) - 8
x1 = max(b[2] for b in boxes) + 8; y1 = max(b[3] for b in boxes) + 8
# contact line: lowest opaque car pixel (the near front tyre) in the plain render
pa = np.asarray(Image.open(f"{SRC}/park_silver.png"))[..., 3]
contact = int(np.where(pa.max(axis=1) > 250)[0].max())
for c in COLOURS:
    Image.open(f"/tmp/full_{c}.png").crop((x0, y0, x1, y1)).save(f"{OUT}/parked_{c}.png")
json.dump({"box": [int(x0), int(y0), int(x1), int(y1)], "contactY": contact - int(y0), "w": int(x1 - x0), "h": int(y1 - y0)},
          open(f"{OUT}/parked_meta.json", "w"))
print(x0, y0, x1, y1, contact - y0)
