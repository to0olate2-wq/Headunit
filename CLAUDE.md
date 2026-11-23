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
- `versionCode = 1000 + run` (earlier test builds from the Project repo went up to ~170). `versionName = 0.6.<run>`. Same signing key
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
- `Skin.kt` (0.4/0.5): themes `glass` (Aurora Glass, default), `sport` (Carbon Sport), `neon` (Neon Night), `luxury`, `classic`.
  Traits: `glowy` (glass, neon: gradients/glows), `analog` (sport, luxury: `DialView` instrument branch, `SpeedDialCard`),
  `floatingHero`. Shapes `GlassBg` / `ChamferBg` / `NeonBg` / `LuxBg`, `Skin.panel()`; dock styles: gradient tiles (glass),
  outline tiles (neon), `jewel` gold rings (luxury), chamfer (sport). Luxury digits need `lnum` (Cormorant has old-style
  figures): `tnum()` and the canvas paints set it. Orbitron is shipped **unmodified** (Reserved Font Name) as a variable
  font; weights come from `Typeface.Builder.setFontVariationSettings`. `Prefs.skin`; accent and
  home pages are stored **per theme** (`accent_<skin>`, `pages_<skin>`; classic also reads the old keys). `C.apply` and
  `F.apply` follow the skin (fonts: Outfit for glass, Rajdhani numbers for sport, Barlow otherwise; `F.display` = huge
  numbers). Shapes: `cardBg()` → `GlassBg` / `ChamferBg` / rounded; `Skin.tile()`, `Skin.gradient()`, `C.FLOAT` for
  chips over maps. `Motion.on` (pref `motion`; tests set `Motion.allowed = false`): page slide-in, aurora drift,
  dial needle easing, hero wheel spin.
- `CarArt` (in `PremiumCards.kt`): original 3D-rendered premium saloon made by `tools/car3d` (three.js `premium.js` +
  headless Chrome, `compose.py` adds the floor reflection). Two looks picked by `CarArt.Mode` (driving after 2 s above
  8 km/h, parked after 8 s below 3 km/h, 0.7 s crossfade; `Mode.force` for tests): **driving** = `side_<colour>.webp`
  + `wheel.webp` (rotated at run time), headlight beam, tail glow, moving road and light streaks; **parked** =
  `parked_<colour>.webp` (front three-quarter, reflection) on an accent spotlight. Frame/wheel metres and
  `PARK_CONTACT` must match `premium.js` / `compose.py`. Owner photo: `files/car.png` (`Prefs.carPhoto`), cut-outs
  are trimmed, normal photos get faded edges. Never ship real-brand cars. In three.js r170, a material's
  `envMapIntensity` only counts when the material has its own `envMap` (premium.js sets it in `shot()`).
- `Roads.kt`: `RoadLimit` (Overpass ways in a ~2 km box, matched locally per fix; numeric `maxspeed` only), `SpeedSign`,
  `SpeedSignView`, `SpeedVolume`. `Places.kt`: quick destinations (Maps/Waze intents). `Car.kt`: `DriveScore`, `FuelRange`.
  `AppsPage.kt`: drawer (search, sort, hidden, folders in Prefs `hiddenApps`/`folders`/`appUses`), `IconPack` in `Apps.kt`.
- `Comms.kt` (0.6): `PhoneHub` (favourites JSON `phoneFavs`, CallLog, dial via ACTION_CALL/DIAL, call banner from any
  notification with CATEGORY_CALL / CallStyle / answer-hangup actions), `MessageHub` (chat notifications → banner, card,
  `Speaker` TTS with ducking, auto-reply via RemoteInput, never to groups, once per chat per 20 min), `Banners` (top of
  the launcher, `MainActivity.bannerBox`), `PhoneCard`, `MessagesCard`. Listener order: Nav → Phone → Message → Media.
- `Care.kt` (0.6.15+): `Notices` (one banner for "update ready" / "take a break", after calls and messages, shown by
  `Banners`), `Backup` (zip = `backup.json` with every pref + `files/`; manual via SAF or `toDownloads` (MediaStore,
  same file updated), daily `auto` from DriveService, `restore` then `restart` = kill process, no onPause, so the old
  trip isn't saved over it), `Updater` (GitHub `releases/latest`, `build-<run>` vs `VERSION_CODE - 1000`, every 6 h,
  banner only when parked; download → `PackageInstaller` session → `UpdateReceiver` opens Android's confirm screen),
  `BreakReminder` (DriveService tick; 15 min stop resets; voice + notice + notification), `SaverView` (parked screen
  saver, always dark, drifts each minute; `MainActivity.showSaver/hideSaver`). Tests set `Prefs.saverMin = 0` and
  `Prefs.autoUpdate = false`; `CareTest` covers backup/restore, update answers, reminder wording.
- `Setup.kt` (0.6.17+): `App` (Application, installs `Crash` first; skipped under Robolectric), `Crash` (uncaught
  handler → `files/crash.txt` + Diag, restart via AlarmManager: MainActivity if `Overlay.launcherVisible`, else only
  DriveService; >2 crashes in 2 min = no restart; `crashUnseen` → "restarted after a problem" notice with Save report;
  reports go into `Diag.dump` and the diagnostics box), `Setup` steps + `SetupView` (guide over the launcher; shown on
  start while pref `setupDone` is false and a needed step — location, notification access, home app — is missing;
  Settings → Connections → Setup guide). Permissions are no longer requested at first start (the guide asks).
  Tests set `setupDone = true`.
- `SafeSettings.kt` (0.6.22): TS10 lesson (from StradaOS notes; GPL, ideas only, no code): on a TS10 UIS7862S Android 14,
  opening Android's hidden Settings (`ACTION_SETTINGS`) froze the screen black until restart. On Android 10+ general
  Settings screens (Wi-Fi, BT, Home) open only if `com.android.settings` is in the launcher app list, else the brand's
  settings app. Home app: `RoleManager.createRequestRoleIntent(ROLE_HOME)` (REQ_HOME); Android 8–9: `FakeHome`
  (disabled HOME activity enabled for 1.5 s → chooser). `leaveLauncher()` (Settings → Connections): open the unit's
  launcher / stop being home / uninstall. Dock Wi-Fi + BT icons (`wifiState`, `btState`; tap = settings safely).
  AVRCP `TRACK_EVENT` song info was already in `MediaHub` broadcasts.
- Built-in navigation (0.6.25): `Nav.kt` — `Geo`, `Route`/`Maneuver` (`at` = metres along), `Words` (English
  instruction/distance text), `NavNet` (Photon → Nominatim search; route: TomTom with live traffic if pref `tomtomKey`,
  else OSRM `router.project-osrm.org` → `routing.openstreetmap.de`, polyline6, `exclude=toll,motorway`), `Guide` (fed by
  `Gps.onFix`: snaps to the route, speaks far/near instructions via `Speaker`, reroutes after 3 off-route fixes,
  publishes to `NavHub` with pkg `Guide.PKG` so home cards / pill show it; `setForTest`), `TurnArrow`.
  `OfflineMaps.kt` — mapsforge 0.25.0 (LGPL, Maven Central; jars for local type-check are copied by CI to
  `ci-results/deps`, keep them in /home/claude/tools/deps): country `.map` files from `download.mapsforge.org/maps/v5/`
  via DownloadManager into `getExternalFilesDir/maps` (`x.map.download` renamed when done), `MultiMapDataStore` +
  `DatabaseRenderer` draw 256-px tiles for `TileMapView` (offline Src when the view centre is covered; `headingUp`,
  `carAt`, `mark`). `OfflineRoute` = rough A* over map ways (zoom 14 near ends, zoom 10 between; loose ends joined
  within 40 m). `NavPage.kt` = Page.NAV (search, places, preview, driving UI, `manageMaps`). Pref `navApp` "builtin"
  makes the Maps button / quick places open it. `NavTest` covers parsing, words, guidance, listing, offline graph.
- `Overlay` + `FloatPanel` (DriveService.kt): one draggable pill over other apps with speed (`bubble`), next turn
  (`floatTurn`) and mini player (`floatMusic`). `LiveScenes` (city/rain/highway) and `VideoBackground` (TextureView +
  MediaPlayer, persisted document URI `videoUri`) in Views.kt; background picker is a list in Settings → Look.
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
- **Play Protect "App blocked to protect your device"** ("can request access to sensitive data"): Google's
  fraud block for sideloaded apps that declare a notification listener (Thailand and other countries). Not fixable in
  the app without losing notification access: the owner turns Play Protect off briefly (or installs with adb).
  0.6.19 removed call log / contacts / self-install by mistake; 0.6.20 put them back.
- `Speaker`: one audio-focus request while speaking (a new request per sentence leaked focus and left music ducked);
  `say(…, english = true)` for our own sentences (directions, reminders), the unit's voice for messages.
- `MainActivity.form`: decimal comma → point only for numeric fields (names and addresses keep commas).
- `DriveService`: `startForeground` can be refused on Android 14+ when started from the background → stop cleanly.
- Built-in guidance owns `NavHub` while `Guide.active` (other apps' nav notifications are ignored then).
- `isSingleLine = true` replaces the all-caps transformation: set caps after it (`Ui.label`).
- `/*` inside a KDoc comment (e.g. `*.png`) opens a nested comment → "Unclosed comment".
- Robolectric needs `android.useAndroidX=true` in gradle.properties.
- CARTO basemaps now need an API key ("API KEY REQUIRED" tiles) — don't go back to them.
- `LocationListener`: implement all four methods (older Android calls them).
- Robolectric software drawing ignores `clipToOutline`; map/road views clip their own rounded corners (`Skin.radius`).
- Glass cards are see-through: anything floating over the map/road uses `C.FLOAT`, not `C.CARD2`.
- Real blur is not available behind views on these units; "frosted glass" is a translucent fill + light edge.
