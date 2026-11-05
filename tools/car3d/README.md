# Car picture generator

Builds the original 3D saloon shown on the home screen (no real brand's design) and renders it
side-on, with a transparent background, in each paint colour plus one wheel image.

    npm install three@0.170.0
    python3 render.py out '{"silver": {"color": "#b9bfc7", "metal": 0.7, "rough": 0.28}}' 1400

`render.py` serves this folder on 127.0.0.1 and drives headless Chrome (Playwright). Convert the PNGs to
WebP and copy them to `app/src/main/assets/car/` (`car_<colour>.webp`, `wheel.webp`). The picture frame and
wheel positions in `car.js` (`X0..Y1`, `WR`, `WF`, `WY`, `TIRE_R`) must match `CarArt` in `PremiumCards.kt`.
