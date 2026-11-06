"""Render the car pictures with headless Chrome (Playwright).

    python3 render.py out            # every paint colour: side_*, park_*, refl_*, wheel
    python3 compose.py out parked    # parked_* = car + floor reflection
    python3 render.py out '[["side_silver", "renderSide", [{"color": "#b9bfc7", "metal": 0.7, "rough": 0.28}, {"width": 1400}]]]'
"""
import asyncio, base64, json, subprocess, sys, time, os
from playwright.async_api import async_playwright

PAINTS = {
    "white": {"color": "#e8eaed", "metal": 0.1, "rough": 0.25}, "silver": {"color": "#b9bfc7", "metal": 0.7, "rough": 0.28},
    "grey": {"color": "#5b616a", "metal": 0.6, "rough": 0.3}, "black": {"color": "#0c0d0f", "metal": 0.05, "rough": 0.18, "env": 0.45},
    "blue": {"color": "#1d3f8f", "metal": 0.55, "rough": 0.3}, "red": {"color": "#8e1616", "metal": 0.5, "rough": 0.28},
    "bronze": {"color": "#6e4b2f", "metal": 0.6, "rough": 0.3}, "green": {"color": "#1d3a30", "metal": 0.55, "rough": 0.3},
}

def standard_jobs():
    jobs = []
    for c, p in PAINTS.items():
        jobs.append(["side_" + c, "renderSide", [p, {"width": 1400}]])
        jobs.append(["park_" + c, "renderParked", [p, {"width": 2400, "height": 1080, "dist": 10.6}]])
        jobs.append(["refl_" + c, "renderParked", [p, {"width": 2400, "height": 1080, "dist": 10.6, "mirror": True, "drl": 0.6}]])
    return jobs + [["wheel", "renderWheel", [512]]]

OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True)
JOBS = json.loads(sys.argv[2]) if len(sys.argv) > 2 else standard_jobs()   # [[name, fn, args]]
HERE = os.path.dirname(os.path.abspath(__file__))
CHROME = os.environ.get("CHROME", "/usr/bin/google-chrome")

async def main():
    srv = subprocess.Popen([sys.executable, "-m", "http.server", "8766", "--bind", "127.0.0.1"], cwd=HERE,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1)
    try:
        async with async_playwright() as p:
            b = await p.chromium.launch(executable_path=CHROME, args=["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"])
            pg = await b.new_page(viewport={"width": 800, "height": 400})
            pg.on("pageerror", lambda e: print("pageerror:", e))
            await pg.goto("http://127.0.0.1:8766/index.html")
            await pg.wait_for_function("window.ready === true", timeout=120000)
            for name, fn, args in JOBS:
                t = time.time()
                url = await pg.evaluate(f"(a) => window.{fn}(...a)", args)
                open(f"{OUT}/{name}.png", "wb").write(base64.b64decode(url.split(",")[1]))
                print(name, round(time.time() - t, 1), "s")
            await b.close()
    finally:
        srv.terminate()

asyncio.run(main())
