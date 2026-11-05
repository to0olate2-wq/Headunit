# Drive Launcher

A home-screen launcher for Android car head units (made for a TS10S, UIS7862S, 1280×720, in a 2015 Hyundai Sonata).
Dashboard with speed, trip, next turn, engine gauges, map and now-playing music, in three themes:

- **Aurora Glass** (default): frosted-glass cards on a softly glowing background, very large thin numbers,
  a lit-up car picture whose wheels turn while you drive, colourful app tiles, music card tinted by the album art.
- **Carbon Sport:** carbon-fibre background, real-looking RPM and speed dials with needles and a red line,
  angled metal panels, red accent.
- **Neon Night:** glowing two-colour outlines, a moving neon grid floor, futuristic numbers.
- **Luxury:** warm black and gold, watch-style dials, elegant serif numbers, gold ring buttons.
- **Classic:** the original flat dark look.

Settings → Look → **Theme**. Each theme keeps its own accent colour and home layout.

**Download the latest version:** [DriveLauncher.apk](https://github.com/to0olate2-wq/Headunit/releases/latest/download/DriveLauncher.apk)
— open this link in the head unit's browser. New versions install over the old one and keep your settings.

## What adapts automatically

- **Screen size:** every size is scaled from the real window, so it fits 1024×600, 1280×720, wide and portrait units.
- **Music:** shows whatever is playing, picking the first source that works on the unit:
  Android media sessions → media notifications (ZLink, Bluetooth music) → legacy player broadcasts.
  If no song info is shared at all, play/pause/next still work by sending media keys.
  The ⇄ button in the music card switches the source by hand.
- **Navigation:** turn card reads the Google Maps / Waze notification when navigating on the unit.
- **Engine data:** finds a paired Bluetooth ELM327 adapter by name (or a Wi-Fi one), asks the car which readings it supports and shows only those.
  Without an adapter the gauges switch to GPS info (heading, altitude, satellites, date).
- **Diagnostics:** the settings page lists exactly what the unit shares (sessions, notifications, OBD replies) and can save a log to Downloads.

## Also included

- **Editable home screen:** tap and hold any card to swap it (drive hero, speed, RPM dial, trip, next turn, road,
  gauges, readouts, map, music, clock & weather, compass, car care, your logo, any Android widget), change the page
  layout or add pages.
- **Motion effects:** pages slide in, the background glows slowly, dials move smoothly (can be turned off).
- **Your car:** a 3D-rendered saloon in your paint colour (wheels turn while driving), or your own car photo.
- **Road speed limit sign** from OpenStreetMap (Google Maps and Waze don't share theirs), with an optional beep.
- **Quick destinations:** Home, Work, saved places, nearest fuel / coffee / food / parking (opens Google Maps or Waze).
- **Speed-based volume**, **fuel range** (OBD2 fuel level or the fuel log), **drive score** with tips for each trip.
- **App drawer:** search, sort (A–Z, most used, newest), hide apps, folders; Android **icon packs** (ADW/Nova format).
- **Day/night look** following sunrise and sunset, with optional automatic brightness; plain, starry or photo backgrounds.
- **Car page:** automatic trip history with routes, fuel log and costs, service reminders, check-engine codes
  (read/clear, with OBD2), and where you last parked.
- **While other apps are open:** speed warning chime, floating speedometer, trips keep recording.
- **Auto-start:** opens ZLink and resumes music when the car turns on; parked screen saver; split screen with Maps
  (if the unit allows it); choose your own dock app buttons.

## First-time setup on the unit

1. Install the APK (allow "unknown sources" if asked).
2. Open it, allow location (and Bluetooth).
3. Settings (sliders icon) → **Notification access** → turn on *Drive Launcher* — needed for music and turn info.
4. Settings → **Default launcher** → choose Drive Launcher as the home app.
5. Optional: pair an OBD2 adapter in Bluetooth settings (PIN usually 1234 or 0000).

Map tiles: Esri World Dark Gray (Esri, HERE, Garmin, © OpenStreetMap contributors), falling back to OpenStreetMap.
Weather: Open-Meteo. Speed limits: © OpenStreetMap contributors (Overpass API).
Fonts: Barlow, Outfit, Rajdhani, Orbitron, Cormorant Garamond (SIL OFL, see `app/src/main/assets/fonts/OFL.txt`).
The car picture is an original model made with `tools/car3d` (three.js).
