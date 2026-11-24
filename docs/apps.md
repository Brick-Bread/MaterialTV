# Make apps for MaterialTV

MaterialTV's **Get apps** row installs two kinds of full-screen app, and you can make either:

| | Android TV app | MaterialTV web app |
|---|---|---|
| What you ship | An APK in a GitHub release (or at an https link) | A `manifest.json` and a web page, on any https host |
| Written in | Kotlin, Java, Flutter, anything that makes an APK | HTML, CSS and JavaScript |
| Runs as | Its own Android app | A full-screen page inside MaterialTV |
| Can do | Anything an Android app can | What a web page can, plus the `window.materialtv` bridge |
| Install | MaterialTV downloads it, checks it, Android asks you to confirm | Instant; nothing for Android to install |

Both kinds can be added by anyone from the phone page (**Your apps**), and both can be listed for
everyone in the catalog, [`docs/store.json`](store.json), with a pull request.

The HTML version of this guide is at <https://brick-bread.github.io/MaterialTV/apps.html>.

---

## Part 1: Android TV apps

### Make it work on a Fire TV

- **Android 9 or older as minSdk.** Fire OS 7 is Android 9 (API 28); Fire OS 8 is Android 11.
- **32-bit ARM.** Fire TV sticks run 32-bit Android, even on 64-bit chips. Ship an
  `armeabi-v7a` or universal APK. An `arm64-v8a`-only APK will not install.
- **No Google Play services.** Fire TV doesn't have them.
- **A TV launcher entry and a banner**, so MaterialTV (and Fire OS) list it as a TV app:

```xml
<uses-feature android:name="android.software.leanback" android:required="false" />
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />

<application android:banner="@drawable/banner" android:icon="@mipmap/ic_launcher" ...>
    <activity android:name=".MainActivity" android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
        </intent-filter>
    </activity>
</application>
```

  The banner is 320 × 180 px (xhdpi). MaterialTV draws its tiles from your sharpest launcher icon.
- **The remote does everything.** Every control is reachable with the D-pad, the focused one is
  obvious, and Back always goes back.

### Publish a release MaterialTV can install

1. Create a GitHub release. Its **tag is the version**: `v1.4.0` or `1.4.0`.
2. Attach the APK as a release asset. MaterialTV picks the asset like this:
   - names with `arm64`, `aarch64`, `x86` or `x64` are skipped;
   - names with `armeabi`, `armv7`, `arm32` or a separate word `arm` come first, then `universal`,
     then any other `.apk`; names with `debug` come last;
   - a catalog entry's `asset_regex` (below) narrows the choice first.
3. Keep `versionName` the same as the tag (without the `v`). MaterialTV shows **Update** when the
   tag's leading numbers are newer than the installed `versionName`.
4. Sign every release with the same key, or Android refuses the update.

Before installing, MaterialTV checks that the download is an app and that its package name is
the one the catalog promises (for your own apps: the one it saw the first time). Android then asks
you to confirm.

### Try it on your own TV

On the phone page (Home > Get apps > Add an app > QR code), under **Your apps**, add a name and
your repo as `owner/name` (or an https link straight to an APK). It appears in Get apps on the TV.

---

## Part 2: MaterialTV web apps

A web app is a web page MaterialTV shows full screen, with no browser bars. MaterialTV moves focus
with the remote's arrows for you, gives the page your theme's colours and font, and offers a small
JavaScript bridge, `window.materialtv`.

### Quick start

Three files on any https host (GitHub Pages is free):

```
my-app/
  manifest.json
  index.html
  icon.png        a square PNG, 256 × 256 or so
```

`manifest.json`:

```json
{
  "api": 1,
  "type": "webapp",
  "id": "my-app",
  "name": "My App",
  "author": "You",
  "description": "What it does, in a sentence.",
  "version": "1.0.0",
  "icon": "icon.png",
  "start_url": "index.html",
  "orientation": "landscape",
  "permissions": ["storage"]
}
```

`index.html`, the smallest useful page:

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="stylesheet" href="https://materialtv.invalid/theme.css">
<style>
  body { margin: 0; padding: 5vh 5vw; font-family: var(--mtv-font, sans-serif);
         background: var(--mtv-background, #141218); color: var(--mtv-on-surface, #E6E0E9); }
  button { font: inherit; font-size: 3vh; padding: 2vh 3vw; border: 0; border-radius: 3vh;
           background: var(--mtv-surface-high, #2B2930); color: inherit; }
  button:focus { background: var(--mtv-primary-container, #4F378B); outline: 3px solid var(--mtv-primary, #D0BCFF); }
</style>
</head>
<body>
  <h1>Hello, TV</h1>
  <button onclick="window.materialtv && materialtv.toast('Hi!')" autofocus>Say hi</button>
  <button>Another</button>
</body>
</html>
```

Then, on the phone page under **Your apps**, add the manifest's link
(`https://you.github.io/my-app/manifest.json`). It shows up in Get apps; select it and choose
**Install**. It is now in All apps and can be a favourite like any other app.

A complete example is [Hello TV](apps/sample/index.html)
([manifest](apps/sample/manifest.json)): a clock, a focusable grid, the theme colours, a
counter kept in `localStorage`, and a sheet that Back closes. It is in Get apps under
"Made for MaterialTV".

### The manifest

| Field | Required | Rules |
|---|---|---|
| `api` | yes | `1` |
| `type` | yes | `"webapp"` |
| `id` | yes | Lowercase letters, digits, `.` `_` `-`; up to 40; starts with a letter or digit. Your app's identity: keep it forever. |
| `name` | yes | Up to 40 characters. The tile's name. |
| `author` | no | Up to 60 characters. |
| `description` | no | Up to 200 characters. |
| `version` | no | Up to 20 characters, shown in the store. |
| `icon` | no | A PNG/JPEG/WebP, up to 512 KB and 2048 px. Relative to the manifest, or an https link. |
| `start_url` | yes | The first page. Relative to the manifest, or https on the manifest's origin or one listed in `origins`. |
| `origins` | no | Up to 10 more origins the app may navigate to, exactly like `"https://api.example.com"` (no path). |
| `orientation` | no | `"landscape"` (the only one supported). |
| `permissions` | no | Any of `"network"`, `"storage"`, `"media"`. Leaving the field out means `["network", "storage"]`. |

The manifest must be served over https. MaterialTV reads it again whenever the app store
refreshes (once a day, or Settings > System > Refresh), so a new name, icon or start page
reaches everyone. The page itself is always live from your server.

### Permissions

| Permission | Without it | With it |
|---|---|---|
| `network` | The page can load only from the app's own origins (manifest origin + `origins`). | It can fetch from any https address (APIs, CDNs, images). |
| `storage` | `localStorage`, `sessionStorage` and Web SQL are off. | They work and persist. |
| `media` | Audio and video need a click (Select) to start. | Media can autoplay. |

Camera, microphone, location, notifications and file pickers are always refused.

### Where the app can go

- Only https. `http://` pages, mixed content, `file://` and `content://` are blocked.
- The main page can only be on the app's origins: the manifest's own and those in `origins`.
  A link elsewhere asks the viewer whether to open it in the browser; other schemes
  (`intent:`, `market:`, `mailto:`…) are blocked.
- `window.open()` and `target="_blank"` open in the same view, under the same rule.
- Frames (`<iframe>`) may show any https page, but never get the bridge's theme or focus
  handling, and third-party cookies are off.

### The remote

| Button | What the page gets |
|---|---|
| Up, down, left, right | `keydown` with `ArrowUp`, `ArrowDown`, `ArrowLeft`, `ArrowRight` |
| Select (centre) | `keydown` with `Enter` |
| Play/pause, rewind, fast-forward | `MediaPlayPause`, `MediaRewind`, `MediaFastForward` |
| Menu | a `materialtv:menu` event on `window` |
| Back | a cancelable `materialtv:back` event on `window`; if nobody cancels it, `history.back()`; at the first page, the app closes |
| Hold Back, or Home | The app closes. Always. |

**Spatial navigation.** If your page doesn't handle an arrow key itself, MaterialTV moves focus
to the nearest focusable element in that direction (links, buttons, inputs, `[tabindex]`), scrolls
it into view, and scrolls the page when there's nothing further that way. Select on a focusable
element that isn't a link, button or input clicks it. To handle keys yourself, call
`event.preventDefault()` in a `keydown` listener on `document` (or on an element), or turn spatial
navigation off:

```html
<meta name="materialtv:spatial-navigation" content="off">
```

or `window.materialtvSpatialNav = false;`.

Use Back for your own layers (a dialog, a details panel):

```js
window.addEventListener('materialtv:back', function (e) {
  if (dialogIsOpen) { e.preventDefault(); closeDialog(); }
});
```

### The bridge: `window.materialtv`

It exists only inside MaterialTV, so check first: `if (window.materialtv) { … }`. It answers only
while the page is on one of the app's origins. Values that aren't simple come back as JSON strings.

| Call | Returns / does |
|---|---|
| `materialtv.api()` | `1`, the bridge version. |
| `materialtv.version()` | MaterialTV's version, like `"1.10.0"`. |
| `materialtv.theme()` | JSON: the colour roles as `"#RRGGBB"` and `isLight`. Roles: `background`, `surfaceLow`, `surface`, `surfaceHigh`, `surfaceHighest`, `onSurface`, `onSurfaceVariant`, `outline`, `outlineVariant`, `primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer`, `secondaryContainer`, `onSecondaryContainer`, `tertiaryContainer`, `error`. |
| `materialtv.app()` | JSON: this app's `id`, `name`, `version`, `permissions`. |
| `materialtv.keys()` | JSON: what each remote button sends (the table above). |
| `materialtv.toast(text)` | A short MaterialTV message at the bottom of the screen (120 characters, one a second). |
| `materialtv.exit()` | Closes the app. |
| `materialtv.openApp(packageName)` | Asks the viewer, then opens that installed Android app. `false` if it isn't installed. |
| `materialtv.keepScreenOn(on)` | Keeps the screensaver away while `true` (say, during a video). |

```js
var theme = window.materialtv ? JSON.parse(materialtv.theme()) : null;
```

### Look native: colours and font

Every page on the app's origins gets these CSS variables (they follow the viewer's accent colour
and light or dark mode):

`--mtv-background`, `--mtv-surface-low`, `--mtv-surface`, `--mtv-surface-high`,
`--mtv-surface-highest`, `--mtv-on-surface`, `--mtv-on-surface-variant`, `--mtv-outline`,
`--mtv-outline-variant`, `--mtv-primary`, `--mtv-on-primary`, `--mtv-primary-container`,
`--mtv-on-primary-container`, `--mtv-secondary-container`, `--mtv-on-secondary-container`,
`--mtv-tertiary-container`, `--mtv-error`, `--mtv-is-light` (`0` or `1`), and
`--mtv-font` (Google Sans Flex, MaterialTV's font).

`<html>` gets the classes `mtv` and `mtv-dark` or `mtv-light`, and focused elements get a
`--mtv-primary` ring unless you style `:focus` yourself.

MaterialTV adds them when the page starts drawing. To have them from the first paint, link the
stylesheet MaterialTV serves at a reserved address (it never resolves anywhere else, so in a normal
browser your fallbacks are used):

```html
<link rel="stylesheet" href="https://materialtv.invalid/theme.css">
```

Also served there: `https://materialtv.invalid/font.ttf` (Google Sans Flex, SIL OFL) and
`https://materialtv.invalid/sdk.js` (the focus and Back helper, if you want it before your own
scripts). When everything is in place, `window` gets a `materialtv:ready` event whose `detail` is
the theme.

### Designing for the TV

- The page is about **960 × 540 CSS pixels** on a 1080p Fire TV (device pixel ratio 2). Size with
  `vh`/`vw` or `rem` so it fits any TV.
- Keep important things 5% away from the edges; some TVs crop them.
- Make focus loud: a ring, a colour change and a slight scale. There is no mouse and no hover.
- Text needs to be read from the sofa: 2.5vh (about 14 CSS px) at the very least.
- Avoid typing. If you must, the Fire TV keyboard appears for a focused `<input>` on Select.
- The user agent ends in `MaterialTV/<version> MaterialTV-WebApp/1`.

### Debugging

Build and test in desktop Chrome first: the sample works there with the arrow keys. On the TV,
with ADB debugging on (Settings > My Fire TV > Developer options), open `chrome://inspect` on a
computer on the same network that's connected with `adb connect <tv-ip>`; running web apps appear
there with full DevTools.

### Privacy, storage and limits

- Web apps run in their own process with their own web data, apart from MaterialTV and from
  Android apps. Leaving the app ends that process.
- **Storage is per origin, not per app.** Android 9 can't give each web app its own storage, so two
  web apps on the same origin (`you.github.io/app-one` and `you.github.io/app-two`) share
  `localStorage`, cookies and IndexedDB. Give each app its own origin (a custom domain or
  subdomain), or prefix your keys with your app's id.
- Uninstalling a web app removes its tile, icon and pins. Its web storage stays on the device (it is
  there again if you reinstall); clearing MaterialTV's data removes it.
- Pages inside frames can reach `window.materialtv` too. Don't frame pages you don't trust.
- Safe Browsing is on, certificate errors are never bypassed, and third-party cookies are off.

---

## Get listed in the catalog

Everyone's Get apps row comes from [`docs/store.json`](store.json), served by GitHub Pages
(the APK carries a copy, `assets/store.json`, for the first run and offline). To add your app,
open a pull request on <https://github.com/Brick-Bread/MaterialTV> that adds one entry to both
files.

```json
{ "id": "jellyfin", "name": "Jellyfin", "package": "org.jellyfin.androidtv", "category": "Video",
  "description": "The Jellyfin media server's Android TV app",
  "icon_url": "https://example.com/jellyfin-512.png",
  "source": { "type": "github", "repo": "jellyfin/jellyfin-androidtv", "asset_regex": "^jellyfin-androidtv-.*-release\\.apk$" } }
```

| Field | Rules |
|---|---|
| `id` | Unique; lowercase letters, digits, `.` `_` `-`; up to 40. |
| `name` | Up to 40 characters. |
| `package` | The APK's package name. MaterialTV refuses a download with any other. (Not for web apps.) |
| `category` | Up to 30 characters: `Popular`, `Video`, `Music`, `Games`, `Tools`, `Made for MaterialTV`. |
| `description` | Up to 160 characters. |
| `icon_url` | Optional https PNG/JPEG, up to 512 KB and 2048 px. |
| `source` | One of the four below. |

| `source.type` | Other fields | Use for |
|---|---|---|
| `github` | `repo` (`owner/name`), optional `asset_regex` (a Java regex, up to 200 characters, matched anywhere in the asset's file name) | APKs in GitHub releases: updates come with each release. |
| `url` | `url` (https link to the APK), `version` | A fixed download on the maker's own site. Bump both together. |
| `appstore` | `package` | Apps only on the Amazon Appstore: MaterialTV opens their page. |
| `webapp` | `manifest` (https link to the manifest) | MaterialTV web apps. |

Example web app entry:

```json
{ "id": "hello-tv", "name": "Hello TV", "category": "Made for MaterialTV",
  "description": "Sample web app: a clock and a remote-friendly grid.",
  "icon_url": "https://brick-bread.github.io/MaterialTV/apps/sample/icon.png",
  "source": { "type": "webapp", "manifest": "https://brick-bread.github.io/MaterialTV/apps/sample/manifest.json" } }
```

What gets accepted: the download comes from the maker (their releases or site); it works with the
remote; it's free of malware, injected ads and tracking beyond what the official app does; and it
says honestly what it is. Web apps should look at home: use the theme variables and make focus
obvious. The catalog holds at most 100 apps.
