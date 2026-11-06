"""Turn the renders into the app's pictures: python3 assets.py out parked ../../app/src/main/assets/car"""
import sys, os
from PIL import Image, ImageEnhance

SRC, PARKED, DST = sys.argv[1], sys.argv[2], sys.argv[3]
COLOURS = ["white", "silver", "grey", "black", "blue", "red", "bronze", "green"]
for c in COLOURS:
    im = Image.open(f"{SRC}/side_{c}.png")
    im.resize((1120, round(im.height * 1120 / im.width)), Image.LANCZOS).save(f"{DST}/side_{c}.webp", quality=84, method=6)
    pk = Image.open(f"{PARKED}/parked_{c}.png")
    pk.resize((1000, round(pk.height * 1000 / pk.width)), Image.LANCZOS).save(f"{DST}/parked_{c}.webp", quality=84, method=6)
# the side light makes the rim look paler than in the parked view: tone it down to match
w = Image.open(f"{SRC}/wheel.png").convert("RGBA").resize((320, 320), Image.LANCZOS)
r, g, b, a = w.split()
rgb = ImageEnhance.Brightness(Image.merge("RGB", (r, g, b))).enhance(0.72)
Image.merge("RGBA", (*rgb.split(), a)).save(f"{DST}/wheel.webp", quality=88, method=6)
print("done", sum(os.path.getsize(f"{DST}/{f}") for f in os.listdir(DST)), "bytes")
