# Drive Launcher

A home-screen launcher for Android car head units (made for a TS10S, UIS7862S, 1280×720, in a 2015 Hyundai Sonata).
Dashboard with speed, trip, next turn, engine gauges, map and now-playing music, in three themes:

- **Aurora Glass** (default): frosted-glass cards on a softly glowing background, very large thin numbers,
  a premium car picture (driving look while you drive, showroom look when parked), colourful app tiles, music card tinted by the album art.
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
- **Your car:** an original 3D-rendered premium saloon in your paint colour. Driving: side view with lights on,
  turning wheels and moving road. Parked: front three-quarter showroom view with a floor reflection. Or your own car photo.
- **Road speed limit sign** from OpenStreetMap (Google Maps and Waze don't share theirs), with an optional beep.
- **Quick destinations:** Home, Work, saved places, nearest fuel / coffee / food / parking (opens Google Maps or Waze).
- **Speed-based volume**, **fuel range** (OBD2 fuel level or the fuel log), **drive score** with tips for each trip.
- **App drawer:** search, sort (A–Z, most used, newest), hide apps, folders; Android **icon packs** (ADW/Nova format).
- **Floating pill over ZLink and Maps:** speedometer, next turn and a mini music player (each can be turned on separately).
- **Live wallpapers** (city lights, rain on glass, night highway that speeds up as you drive) or **your own video**.
- **Phone card:** favourite contacts, recent calls, call banner with answer / decline (from the unit's Bluetooth phone app).
- **Messages:** banner and card for new chat messages on the unit, read aloud with the unit's voice, optional
  automatic "I'm driving" reply through the app's own reply button. Calls and messages inside ZLink stay in ZLink.
- **Day/night look** following sunrise and sunset, with optional automatic brightness; plain, starry or photo backgrounds.
- **Car page:** automatic trip history with routes, fuel log and costs, service reminders, check-engine codes
  (read/clear, with OBD2), and where you last parked.
- **While other apps are open:** speed warning chime, floating speedometer, trips keep recording.
- **Auto-start:** opens ZLink and resumes music when the car turns on; split screen with Maps
  (if the unit allows it); choose your own dock app buttons.
- **Parked screen saver:** big dim clock, date, weather, your car in its showroom look and what's playing; moves a
  little every minute so nothing burns in. Tap to close.
- **Break reminder:** after 2 hours of driving (or 1½ / 3), a spoken reminder, a banner with "Find coffee" and a notification.
- **Backup & restore:** settings, home pages, dock, places, trips, fuel and service logs in one file (Downloads, USB stick…),
  plus a daily copy in Downloads.
- **Updates:** tells you when a new version is out and installs it with one tap (Android asks you to confirm).

## First-time setup on the unit

The app opens a **setup guide** the first time: each permission has an *Allow* button and a tick when done
(Settings → Connections → Setup guide opens it again). If the app ever crashes it restarts by itself and offers
to save a report to Downloads.

1. Install the APK (allow "unknown sources" if asked).
2. Open it, allow location (and Bluetooth).
3. Settings (sliders icon) → **Notification access** → turn on *Drive Launcher* — needed for music and turn info.
4. Settings → **Default launcher** → choose Drive Launcher as the home app.
5. Optional: pair an OBD2 adapter in Bluetooth settings (PIN usually 1234 or 0000).

Map tiles: Esri World Dark Gray (Esri, HERE, Garmin, © OpenStreetMap contributors), falling back to OpenStreetMap.
Weather: Open-Meteo. Speed limits: © OpenStreetMap contributors (Overpass API).
Fonts: Barlow, Outfit, Rajdhani, Orbitron, Cormorant Garamond (SIL OFL, see `app/src/main/assets/fonts/OFL.txt`).
The car picture is an original model made with `tools/car3d` (three.js).
