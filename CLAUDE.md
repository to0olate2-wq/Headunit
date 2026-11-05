# Drive Launcher: notes for Claude

Home-screen launcher for Android car head units. Package `app.drivelauncher`, Kotlin, **plain Android Views,
no AndroidX/Compose in the app** (Robolectric is test-only). The owner is not a developer: reply briefly,
in plain words, answer first. Owner's unit: TS10S (UIS7862S, 1280×720, reports Android 15, CAN version Unknown),
2015 Hyundai Sonata, phone music via ZLink (Anghami / Spotify). This repo is public.

## Rules
- Releases carry only the APK (`DriveLauncher.apk`, constant name so the "latest" link never changes).
- Screen pictures and logs in `ci-results` are for your own checking; don't send them to the owner.
- Say plainly what has not been tried on the real head unit (nothing is device-tested here).

## Build & release
- No Android SDK locally. Syntax/type check: `kotlinc -cp <android-34 android.jar>` on `app/src/main/java/**/*.kt`
  (needs a stub `object BuildConfig { const val VERSION_NAME = "x" }`). Google Maven is blocked here.
- Commit message containing `[no release]` builds and tests without publishing (use while checking screens).
- Push `main` → CI builds `assembleRelease`, runs `testReleaseUnitTest` (OBD parser tests + `ScreensTest`, which
  starts the launcher in Robolectric at 1280×720, 1024×600 and 768×1024 and saves PNGs). Release `build-<run>`
  is published only if both pass. Results: shallow-clone branch `ci-results` (status.txt, logs, screens/, test-results/).
- `versionCode = 1000 + run` (earlier test builds from the Project repo went up to ~170). `versionName = 0.4.<run>`. Same signing key
  (`signing/`) for every build so updates install over the old one.

## Layout (`app/src/main/java/app/drivelauncher/`)
- `MainActivity` builds every page from the real window size (`S.u` scale, design = 1280×720, portrait = 720×1280).
  `DashActivity` (subclass, `dashMode`) is the compact dashboard used for split screen with Maps.
- Home pages: `Cards.kt` — `Card` types (hero, speed, rpm, trip, nav, road, gauges, stats, map, media, clock, compass,
  car, logo, widget:<id>), layouts (aurora 3, cockpit 5, classic 6, map 3, grid 4, duo 2, dash 3) in `HomePages`
  (JSON in Prefs `pages_<skin>`). Tap and hold a card → edit menu.
- Other pages: `TelemetryPage`, `CarPage` (trips, fuel, service, engine codes, parking), `SourcesPage` (sources,
  settings, diagnostics), `AppsPage`; `Dock` (4 owner-chosen app buttons, page dots).
- `DriveService` (foreground, type location): GPS, `Trips` recording, `SpeedAlert`, `Overlay` (floating speedometer),
  `AutoStart` (power-on / wake from sleep). `Car.kt`: Odometer, Trips, Parking, FuelLog, ServiceLog, DtcInfo.
- `Theme.kt`: `Sun` (sunrise/sunset), `ThemeMgr` (auto day/night, brightness). Palette `C` is switchable (`C.apply`).
- `Skin.kt` (0.4): themes `glass` (Aurora Glass, default), `sport` (Carbon Sport), `classic`. `Prefs.skin`; accent and
  home pages are stored **per theme** (`accent_<skin>`, `pages_<skin>`; classic also reads the old keys). `C.apply` and
  `F.apply` follow the skin (fonts: Outfit for glass, Rajdhani numbers for sport, Barlow otherwise; `F.display` = huge
  numbers). Shapes: `cardBg()` → `GlassBg` / `ChamferBg` / rounded; `Skin.tile()`, `Skin.gradient()`, `C.FLOAT` for
  chips over maps. `Motion.on` (pref `motion`; tests set `Motion.allowed = false`): page slide-in, aurora drift,
  dial needle easing, hero wheel spin.
- `PremiumCards.kt`: `HeroView`/`HeroCard` (clock, big speed, 3 stats, `CarArt` side-view car), `DialView`
  (sport: analog instrument; others: slim arc), `RpmCard`, `SpeedDialCard` (speed card in sport), `NavCard`, `StatsCard`.
  Layouts `aurora` [hero, nav, media] and `cockpit` [rpm, nav, media, stats, speed]; `HomePages.standard()` per skin.
- Data: `Gps` (speed, trip), `MediaHub` (+ `NavHub`, `MediaListenerService`), `Obd` + `ObdParse` (ELM327 BT/Wi-Fi),
  `Weather` (Open-Meteo), `TileMapView` (Esri dark gray → OSM with dark filter), `Diag` (on-screen + saved log).
- Drawing: `Views.kt` (gauges, road, art, buttons), `Icons.kt` (24×24 stroke icons), `Ui.kt` (palette, helpers).

## Music source order (MediaHub.pick)
Pinned source → playing media session → playing notification track (ZLink/BT) → broadcast track → paused session.
Controls: session transport → notification actions (by name, or position when 3) → media key events.

## Pitfalls already met
- `isSingleLine = true` replaces the all-caps transformation: set caps after it (`Ui.label`).
- `/*` inside a KDoc comment (e.g. `*.png`) opens a nested comment → "Unclosed comment".
- Robolectric needs `android.useAndroidX=true` in gradle.properties.
- CARTO basemaps now need an API key ("API KEY REQUIRED" tiles) — don't go back to them.
- `LocationListener`: implement all four methods (older Android calls them).
- Robolectric software drawing ignores `clipToOutline`; map/road views clip their own rounded corners (`Skin.radius`).
- Glass cards are see-through: anything floating over the map/road uses `C.FLOAT`, not `C.CARD2`.
- Real blur is not available behind views on these units; "frosted glass" is a translucent fill + light edge.
