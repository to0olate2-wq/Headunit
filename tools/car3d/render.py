import asyncio, base64, json, subprocess, sys, time
from playwright.async_api import async_playwright
OUT = sys.argv[1] if len(sys.argv) > 1 else "out"
import os; os.makedirs(OUT, exist_ok=True)
PAINTS = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {"silver": {"color": "#aeb4bc", "metal": 0.75, "rough": 0.3}}
WIDTH = int(sys.argv[3]) if len(sys.argv) > 3 else 1400
async def main():
    srv = subprocess.Popen([sys.executable, "-m", "http.server", "8765", "--bind", "127.0.0.1"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1)
    try:
        async with async_playwright() as p:
            b = await p.chromium.launch(executable_path="/usr/bin/google-chrome", args=["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"])
            pg = await b.new_page(viewport={"width": 800, "height": 400})
            pg.on("console", lambda m: print("console:", m.text))
            pg.on("pageerror", lambda e: print("pageerror:", e))
            await pg.goto("http://127.0.0.1:8765/index.html")
            await pg.wait_for_function("window.ready === true", timeout=60000)
            for name, paint in PAINTS.items():
                t = time.time()
                url = await pg.evaluate("([p, w]) => window.renderCar(p, {width: w})", [paint, WIDTH])
                open(f"{OUT}/car_{name}.png", "wb").write(base64.b64decode(url.split(",")[1]))
                print(name, round(time.time() - t, 1), "s")
            url = await pg.evaluate("() => window.renderWheel(512)")
            open(f"{OUT}/wheel.png", "wb").write(base64.b64decode(url.split(",")[1]))
            url = await pg.evaluate("([p, w]) => window.renderCar(p, {width: w, wheels: true})", [list(PAINTS.values())[0], WIDTH])
            open(f"{OUT}/full.png", "wb").write(base64.b64decode(url.split(",")[1]))
            print(json.dumps(await pg.evaluate("() => window.meta")))
            await b.close()
    finally:
        srv.terminate()
asyncio.run(main())
