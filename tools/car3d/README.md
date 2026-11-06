# Car picture generator

Builds the original 3D premium saloon shown on the home screen (no real brand's design: low body, light bars
front and back, black glass roof, turbine wheels) and renders it with a transparent background in each paint colour.

    npm install three@0.170.0
    python3 render.py out              # side_*, park_*, refl_*, wheel (Playwright + headless Chrome)
    python3 compose.py out parked      # parked_* = three-quarter view + soft floor reflection

App pictures in `app/src/main/assets/car/` (WebP, quality ~84):
- `side_<colour>.webp` — driving look, side view without wheels, 1120 px wide;
- `wheel.webp` — one wheel, turned at run time, 320 px;
- `parked_<colour>.webp` — parked look, 1000 px wide.

`FRAME`, `WR`, `WF`, `WY`, `TIRE_R` in `premium.js` must match `CarArt` in `PremiumCards.kt`, and the
`contactY / h` printed by `compose.py` must match `PARK_CONTACT`.
