# M3 Home: a Material 3 launcher for Fire TV

A home screen, settings panel and screensaver for Amazon Fire TV Sticks, built in the Material 3 Expressive style: dark theme, baseline purple, set in Google Sans Flex. It's plain Java with no Gradle, plus one small library (ZXing) for QR codes.

## Home screen
- **Header:** a greeting, a large clock with the date, a **Tailscale chip** showing whether Tailscale is up and the stick's tailnet IP, and a **Settings** button.
- **Rows:** Favourites (with an "Add favourite" card), **Recently used**, and an **All apps** grid five tiles wide.
- **Tiles:** every app tile is drawn the same way, as a tonal gradient taken from the app's own colours with its sharpest icon and its name inside the card. When a tile is focused it springs up, its corners round out, and a purple ring appears.
- **≡ (menu) or hold Select on a tile:** add or remove a favourite, move it left or right, hide the app from All apps, **change the tile picture** (use your own artwork), or open app info.
- **Fire OS Home button:** pressing Home opens M3 Home. **Holding Home** opens M3 Home's settings instead of Amazon's quick menu.

## Settings panel (Settings button, or hold Home)
- **Sleep timer:** off, or 15 minutes to 2 hours. When it ends, a one-minute **wind-down** shows the sleep scene slowly fading darker. Press any key to stay awake; otherwise the stick goes to sleep.
- **Bedtime** (default 23:00 to 07:00): during these hours the screensaver becomes a calm **sleep scene**, with a dark sky, twinkling stars, a drifting moon, the odd shooting star and a dim clock. It switches over by itself at bedtime and wake-up.
- **Display:**
  - Brightness dimming, a blue light filter and a filter strength. These are an overlay drawn over the picture, because a Fire TV Stick on HDMI has no real brightness or night-light control.
- **Screensaver:**
  - Style: Rotate all, Clock, Shapes or Slideshow.
  - How long each picture shows.
  - Stills only, live wallpapers only, or both.
  - Theme switches for abstract, nature, space and city.
  - A switch for the clock and widgets.
  - A preview, and a switch to use M3 Home as the Fire TV screensaver.
- **Widgets:**
  - Weather from Open-Meteo, with no key or account.
  - Deadlines and a big countdown.
  - Now playing.
  - Tailscale.
- **Home screen:** clear recently used, or show hidden apps again.
- **Updates:** check GitHub now, or once a day automatically.
- **Fire TV settings:** links to Network, My Fire TV, Accessibility and All Fire TV settings.

## Screensaver
- **Clock:** large expressive digits and a greeting over drifting Material shapes, with widget chips underneath.
- **Shapes:** circles, squircles, flowers, scalloped "cookies", pills and soft stars float, turn and gently morph, each with a soft glow.
- **Slideshow:**
  - Pictures pan and zoom slowly.
  - Live wallpapers (MP4) play silently, and short clips loop with a soft dip so the restart doesn't jump.
  - Every change fades through black.
  - A glass card shows the time, date, the picture's name and your widgets.
- **Where media goes:** put files in `/sdcard/Android/data/app.m3home/files/screensaver/` on the stick. You can use sub-folders `abstract`, `nature`, `space` and `city`, and each one gets its own on/off switch.

## Deadlines and countdown (QR code and companion site)
1. In settings, open **Deadlines and countdown**.
2. Scan the QR code with your phone. Your phone needs to be on the same Wi-Fi as the TV, or on Tailscale.
3. A small page served **by the stick itself** opens. There you can add or remove deadlines, set the big countdown and set the weather town. There's no built-in town, so weather appears once you set one.

The companion site only runs while that screen is open, and every request needs the secret key in the QR link. Nothing goes through an outside server.

## Updates from GitHub
M3 Home checks this repository's latest **Release** once a day. When there's a newer version, an "Update available" card appears on the home screen. Install downloads `m3home.apk`, checks it against `m3home.apk.sha256`, and opens Android's install prompt, where you confirm. Android only accepts an update signed with the same key as the installed app.

To publish an update:
1. Raise `VERSION_CODE` and `VERSION_NAME` in `build.sh`, then run `./build.sh`.
2. Create a release tagged `vX.Y` with both `build/m3home.apk` and `build/m3home.apk.sha256` attached (`sha256sum build/m3home.apk > build/m3home.apk.sha256`).

## Requirements
- A Fire TV device on Fire OS 7 or newer (Android 9+). Tested on a Fire TV Stick (AFTSS, Fire OS 7.7.1.7).
- To build it:
  - JDK 17+
  - An Android SDK with any `platforms;android-NN` and `build-tools`
  - `adb`
  - On Windows, Git Bash to run the scripts

## Build and install

```bash
./build.sh                        # builds build/m3home.apk; creates m3home.keystore the first time
./install.sh 192.168.1.50:5555    # your Fire TV's adb address; installs and grants what M3 Home needs (below)
```

The first build creates your signing key, `m3home.keystore`, with a random password saved in `keystore.properties`. You can also set the password with `M3_KEYSTORE_PASS`. Back both files up and keep them private; both are git-ignored. Updates must be signed with the same key.

What `install.sh` turns on over adb:
- **The accessibility service `HomeGuard`.** It handles Home and hold-Home, the display filter and the sleep timer.
  - It only receives window changes from `com.amazon.tv.launcher` and `com.amazon.tv.settings.v2`.
  - It reads no screen content.
- **`WRITE_SECURE_SETTINGS`.** M3 Home uses it only to set itself as the screensaver, and to turn its own service back on after an update. Android can switch the service off when the app updates.
- **Notification-listener access for `MediaListener`.** It's used only to read "now playing" titles, and it ignores notifications.
- **"Install unknown apps" for M3 Home.** This lets it offer the GitHub updates. Android still asks you before installing each one.

On Fire OS there's a delay of about 3 to 5 seconds after you press Home before another app can come to the front. Every replacement launcher has this delay.

## Permissions
- `INTERNET`: used for the weather (only the chosen town's coordinates are sent to Open-Meteo) and for the companion site.
- `ACCESS_NETWORK_STATE`: used for the Tailscale chip and to find the TV's address for the QR code.

## Project layout

```
AndroidManifest.xml
assets/fonts/          Google Sans Flex (SIL OFL 1.1, see OFL.txt)
libs/                  ZXing core 3.5.3 (Apache 2.0), QR codes
res/                   icons, banner, theme, accessibility config
src/app/m3home/
  Home.java            home screen
  Apps.java            installed apps, favourites, recents, hidden apps, tile pictures
  SettingsActivity.java  M3 side-sheet settings
  HomeGuard.java       Home / hold-Home, display filter, sleep timer
  Screensaver*.java    the Dream, its preview, and the shared view
  ShapesView.java      Material shapes animation
  Weather.java         Open-Meteo forecast and geocoding
  Deadlines.java       deadlines and countdown storage
  Companion.java       the phone companion site
  DeadlinesActivity.java  QR code screen
  NowPlaying.java, MediaListener.java   now-playing widget
  M3.java, M3Menu.java, Glow.java       colours, fonts, menus, background
build.sh, install.sh
```

## Credits and licences
- M3 Home: GPL-3.0, see [LICENSE](LICENSE).
- Google Sans Flex: SIL Open Font License 1.1, see `assets/fonts/OFL.txt`.
- ZXing core: Apache License 2.0.
- Material icons (settings, add, visibility off): Apache License 2.0.
