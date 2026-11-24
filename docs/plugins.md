# MaterialTV plugins

Plugins add things to MaterialTV's Home screen:

| What | Where it shows |
| --- | --- |
| **Widgets** | Small status chips in the Home header, and lines in the screensaver's clock card |
| **Rows** | Rows of tiles on Home, like Favourites. Each tile can open something |
| **Themes** | A shareable look: accent colour (any colour), light/dark, background picture or colour, dim/blur, tile size |
| **Shortcuts** (actions) | Tiles that open a web page, open an app or deep link, or call a webhook (smart home). Each can be added to Favourites |

There are two kinds of plugin:

- **Web plugins**: a JSON file at an https link. No code runs on the TV; MaterialTV fetches your JSON and draws it.
  Anyone can make one with a text editor and free hosting (GitHub Pages, a gist, your own server).
- **Android plugins**: a small Android app with a `ContentProvider`. Use this when the data lives on the TV, or
  you need to compute it locally.

Plugin API version: **1**.

---

## Installing plugins

- **From your phone**: on the TV open *Settings > Customise from your phone* and scan the QR code. Under
  **Plugins**, paste the plugin's https link and press **Add plugin**. The same list lets you switch plugins on
  and off, apply a plugin's theme and remove plugins.
- **From the Get apps row**: plugins listed in MaterialTV's catalog show up in *Get apps*. Select one and choose
  *Install plugin*.
- **Android plugins**: install the app (for example with *Get apps*, Downloader or `adb install`). MaterialTV
  finds it automatically; switch it on in *Settings > Plugins* (Android plugins are **off** until you do).

*Settings > Plugins* lists every plugin with what it adds. Open one to switch it on or off, show or hide each of
its rows, put its shortcuts in Favourites, apply its theme, refresh it, or remove it.

When a plugin is switched on, its rows are added to the end of Home once. Hide one from the plugin's page in
Settings; it stays hidden until you show it again (or switch the plugin off and on).

---

## Web plugins

### The manifest

Host a JSON file at an https URL. That link is what people add.

```json
{
  "api": 1,
  "id": "world-clocks",
  "name": "World clocks",
  "author": "Your name",
  "description": "What it does, in a sentence or two.",
  "version": "1.0",
  "icon": "🌍",
  "widgets": [
    { "id": "offsets", "title": "World clocks",
      "data_url": "https://example.github.io/clocks/widget.json", "refresh_minutes": 60 }
  ],
  "rows": [
    { "id": "cities", "title": "World clocks",
      "data_url": "https://example.github.io/clocks/row.json", "refresh_minutes": 60 }
  ],
  "theme": {
    "accent": "#3F6FD8", "mode": "dark", "background_color": "#0B1020", "tile_size": "normal"
  },
  "actions": [
    { "id": "timeanddate", "title": "Time zones", "icon": "🕒",
      "type": "url", "url": "https://www.timeanddate.com/worldclock/" }
  ]
}
```

| Field | Required | Notes |
| --- | --- | --- |
| `api` | yes | Must be `1`. |
| `id` | yes | 1–40 characters: lowercase letters, digits, `-` and `_`, starting with a letter or digit. Unique per plugin; installing a manifest with the same `id` again updates it. |
| `name` | yes | Up to 40 characters. |
| `author`, `description`, `version` | no | Shown in Settings and on the phone page (40, 300 and 20 characters). |
| `icon` | no | An https picture or a short glyph (an emoji or up to two letters). |
| `widgets` | no | Up to 6. See [Widgets](#widgets). |
| `rows` | no | Up to 6. See [Rows](#rows). |
| `theme` | no | See [Themes](#themes). |
| `actions` | no | Up to 30 shortcuts. See [Actions](#actions). |

Unknown fields are ignored, so newer manifests keep working in older MaterialTV versions.

### Widgets

```json
{ "id": "offsets", "title": "World clocks", "data_url": "https://…/widget.json", "refresh_minutes": 60 }
```

`data_url` must be https. `refresh_minutes` is 5–1440 (default 30). The data is one card:

```json
{ "icon": "🌍", "text": "New York −5 h · Tokyo +9 h", "subtext": "from London (GMT)" }
```

or up to three cards: `{ "items": [ { "icon", "title", "subtitle" }, … ] }`.

- `text` (or `title`): up to 60 characters. `subtext` (or `subtitle`): up to 80. Cards with no text are skipped.
- `icon`: an https picture (shown at about 48 px) or a glyph.
- Home shows at most three plugin widgets in its header; the screensaver shows up to four.
  Widgets aren't clickable.

### Rows

```json
{ "id": "cities", "title": "World clocks", "data_url": "https://…/row.json", "refresh_minutes": 60 }
```

The row id `actions` is reserved (see [Actions](#actions)). The data:

```json
{
  "items": [
    { "title": "Tokyo", "subtitle": "UTC +9",
      "image": "https://example.github.io/clocks/tokyo.jpg",
      "action": { "type": "url", "url": "https://www.timeanddate.com/worldclock/japan/tokyo" } }
  ]
}
```

- Up to 40 items. `title` up to 60 characters (required), `subtitle` up to 80 (shown in the tile's menu).
- `image`: an https picture filling the tile (16:10 looks best; it's shrunk to about 480 px wide). Without one,
  the tile shows `icon` (a picture or glyph) or the title's first letter on a colour taken from the title.
- `action`: what Select does, in the same form as a shortcut's (without `id`, `title` and `icon`).
  Row items can't be added to Favourites, because their contents change; use [Actions](#actions) for that.
- A row with no items (or that hasn't loaded yet) is hidden.

### Themes

```json
"theme": {
  "accent": "#1E88E5",
  "mode": "dark",
  "background_url": "https://example.github.io/theme/background.jpg",
  "background_color": "#101820",
  "dim": 40,
  "blur": 20,
  "tile_size": "large"
}
```

Every field is optional. A theme is applied only when someone chooses **Apply theme** (in Settings or on the
phone page); installing a plugin never changes the look by itself.

| Field | Values |
| --- | --- |
| `accent` | Any `#RRGGBB` colour (MaterialTV builds its Material 3 colour scheme from it; shown as *Custom*), or one of `purple`, `blue`, `teal`, `green`, `yellow`, `orange`, `red`, `pink`, `background`. |
| `mode` | `dark` or `light`. |
| `background_url` | An https picture (JPEG or PNG, up to 8 MB). It becomes the Home background (*Your picture*), stored at most 1920 px. |
| `background_color` | `#RRGGBB`: a solid background. `background_url` wins if both are set and the picture downloads. |
| `dim` | 0–80 (%), for picture backgrounds. |
| `blur` | 0–100, for picture backgrounds. |
| `tile_size` | `compact`, `normal` or `large`. |

### Actions

Shortcuts appear in a row named after the plugin (the `actions` row), and each one can be added to Favourites
(Menu or long-press a tile, *Add to favourites*; or *Settings > Plugins > the plugin*). `id` (same rules as the
plugin id) and `title` (up to 40 characters) are required; `icon` is optional.

**Open a web page**

```json
{ "id": "radar", "title": "Rain radar", "icon": "🌧", "type": "url", "url": "https://www.rainviewer.com/map.html" }
```

**Open an app, or a deep link**

```json
{ "id": "youtube", "title": "YouTube", "type": "app", "package": "com.amazon.firetv.youtube" }
{ "id": "lofi", "title": "Lo-fi radio", "type": "app", "package": "com.amazon.firetv.youtube",
  "uri": "https://www.youtube.com/watch?v=jfKfPfyJRdk" }
{ "id": "settings", "title": "Wi-Fi", "type": "app",
  "intent": "intent:#Intent;action=android.settings.WIFI_SETTINGS;end" }
```

- `package` alone opens the app (its TV launcher entry first).
- `uri` opens a deep link (`https:` or an app scheme like `spotify:`), limited to `package` if given.
  `http`, `file`, `content`, `javascript`, `data` and `intent` URIs aren't allowed here.
- `intent` is an Android `intent:` URI ([format](https://developer.android.com/reference/android/content/Intent#toUri(int))).
  See [How intents are made safe](#how-intents-are-made-safe).
- MaterialTV tries `intent`, then `uri`, then `package`, and uses the first that opens.

**Call a webhook (smart home)**

```json
{ "id": "lights-on", "title": "Lights on", "icon": "💡", "type": "webhook",
  "method": "POST", "url": "https://ha.example.com/api/webhook/lights-on",
  "headers": { "Authorization": "Bearer YOUR-TOKEN" },
  "content_type": "application/json", "body": { "brightness": 80 } }
```

- `url` must be https. `method` is `GET` (default) or `POST`.
- `body` (POST only): a string, or a JSON object/array that's sent as JSON. Up to 4 KB.
- `content_type`: default `application/json`.
- `headers`: up to 5, names of letters, digits and `-`, values up to 500 characters (no line breaks; `Host` and
  `Content-Length` can't be set).
- Redirects aren't followed. Any 2xx answer counts as success; the TV shows "Lights on: done" or the error.
- **The first time** each shortcut runs, MaterialTV asks: it names the plugin, the method and the host. If the
  plugin later changes that shortcut's method, link, headers or body, it asks again.
- Anyone who can read your manifest can read its headers. For tokens, host the manifest somewhere private
  (an unlisted gist, your own server), or use webhook URLs that need no token (Home Assistant webhook triggers
  are unauthenticated by design, so keep their ids secret). Local `http://` addresses aren't supported; Home
  Assistant can be reached over https with Nabu Casa or your own certificate.

### Limits and rules

- Every link (manifest, data, pictures, webhooks) must be `https://`, up to 500 characters, without a
  user name or password. Redirects (for GETs) are followed only to other https links, up to 5.
- The manifest and each data file: up to **256 KB**. Pictures: up to **1 MB** and 4096 × 4096 px; they're
  downsampled before display. Theme backgrounds: up to 8 MB.
- Timeouts: 8 s to connect, 12 s to read.
- Text is shown as plain text: control characters and text-direction overrides are removed, line breaks become
  spaces, and long text is cut to the limits above.
- MaterialTV keeps the last good copy of every widget and row (on the TV), so it shows straight away and when your
  server is down. Data is fetched while Home (or the screensaver) is showing, when it's older than
  `refresh_minutes`, and only for rows that are on Home. A failed fetch is retried 5 minutes later.
- Pictures are cached on the TV, so changing a picture means giving it a new URL.
- *Settings > Plugins > Refresh plugins* reloads every manifest and all data now.
- Up to 20 web plugins.
- Requests carry `User-Agent: MaterialTV-Plugins/1` and no cookies.

### Hosting on GitHub Pages

1. Create a repository, add `plugin.json` (and your data files), and switch on *Settings > Pages* for it.
2. Your link is `https://YOUR-NAME.github.io/REPO/plugin.json`.
3. To update data, commit new JSON; TVs pick it up at their next refresh.

Data can also come from any https API that returns this JSON, for example a small serverless function that
turns your calendar or a sports API into `{ "items": [...] }`.

### Examples

- [World clocks](plugins/examples/world-clocks.json): a widget
  ([data](plugins/examples/world-clocks-widget.json)), a row ([data](plugins/examples/world-clocks-row.json))
  and a theme. Also in the *Get apps* row.
- [Smart home](plugins/examples/smart-home.json): webhook, app and web shortcuts. A template: host your own
  copy with your own webhook links.

Add them from the phone page with these links:

```
https://brick-bread.github.io/MaterialTV/plugins/examples/world-clocks.json
https://brick-bread.github.io/MaterialTV/plugins/examples/smart-home.json
```

### Listing a plugin in Get apps

MaterialTV's catalog ([docs/store.json](store.json)) can list plugins next to apps:

```json
{ "id": "world-clocks", "name": "World clocks", "type": "plugin", "category": "Plugins",
  "description": "Plugin: a world-clock widget and row, and a night-blue theme",
  "icon_url": "https://example.github.io/clocks/icon.png",
  "manifest": "https://brick-bread.github.io/MaterialTV/plugins/examples/world-clocks.json" }
```

`manifest` must be https. Optional `long_description`, `screenshots`, `license` and `website` fill the
plugin's full-screen page, as for apps (see [apps.md](apps.md)). Installed plugins drop out of the row
(they're managed in *Settings > Plugins*).
Open a pull request to suggest one.

---

## Android plugins

An Android plugin is an app with an exported `ContentProvider` that:

1. has an intent filter for the action **`app.m3home.PLUGIN`**,
2. names its authority in meta-data **`app.m3home.plugin`** (on the `<provider>`, or on `<application>`),
3. should set `android:readPermission="app.m3home.permission.PLUGIN_HOST"`, so only MaterialTV can read it.

MaterialTV declares that permission with `signature` protection and holds it, so no other app can be granted it.
(If your plugin is installed before MaterialTV, it can't be read until MaterialTV is installed; that's fine.)

```xml
<provider
    android:name=".PluginProvider"
    android:authorities="com.example.myplugin.plugin"
    android:exported="true"
    android:readPermission="app.m3home.permission.PLUGIN_HOST">
    <intent-filter>
        <action android:name="app.m3home.PLUGIN" />
    </intent-filter>
    <meta-data android:name="app.m3home.plugin" android:value="com.example.myplugin.plugin" />
    <meta-data android:name="app.m3home.plugin.description" android:value="What it adds, in a sentence." />
</provider>
```

The authority must belong to a provider in the **same app** (MaterialTV checks), and it is the only thing
MaterialTV ever asks. The plugin's name is the app's label; its version is the app's `versionName`.

### What MaterialTV queries

Nothing is queried until the user switches the plugin on in *Settings > Plugins*. Then:

| URI | Columns | Shown as |
| --- | --- | --- |
| `content://AUTHORITY/widgets` | `title`, `subtitle`, `image_uri`, `intent_uri` | Up to 3 widget cards (Home header, screensaver) |
| `content://AUTHORITY/rows` | `id`, `title` | The rows you offer (up to 6). `id` follows the plugin-id rules; `actions` is reserved |
| `content://AUTHORITY/rows/ID` | `title`, `subtitle`, `image_uri`, `intent_uri` | The tiles in one row (up to 40) |

- Return `null` (or an empty cursor) for anything you don't offer. Missing columns read as empty.
- MaterialTV asks for `/rows` when the plugin is switched on and on *Refresh plugins*; it asks for widgets and
  each shown row about every 15 minutes while Home or the screensaver is showing, and keeps the last answer.
- Queries run on a background thread through an *unstable* provider connection, so a crash in your plugin
  can't take MaterialTV down. Keep them fast (well under a second).
- Text follows the same rules and limits as web plugins.
- `image_uri`: `https://…`, `content://AUTHORITY/…` (your provider; implement `openFile`), or
  `android.resource://YOUR.PACKAGE/…`. Anything else is ignored.
- `intent_uri`: an `intent:` URI that opens an activity. Widgets aren't clickable, so it's used for row tiles.

### How intents are made safe

`intent_uri` (and a web plugin's `intent` field) is parsed with `Intent.parseUri(uri, URI_INTENT_SCHEME)`, then:

- the selector, clip data and all flags are dropped (so no URI permission grants); MaterialTV adds `NEW_TASK`;
- `file:`, `content:` and `javascript:` data is refused;
- it's only ever started with `startActivity` — never as a broadcast or a service;
- it must resolve to at least one activity, none of them in MaterialTV itself, and an explicit component
  must be exported.

### Sample

[`samples/android-plugin`](https://github.com/Brick-Bread/MaterialTV/tree/main/samples/android-plugin) is a
complete plugin in one Java file: a widget (how long the TV has been on) and a *Quick links* row. Build it with
its `build.sh` (bare SDK tools, like MaterialTV), then `adb install -r build/sample-plugin.apk` and switch it on
in *Settings > Plugins*.

```java
public class PluginProvider extends ContentProvider {
    private static final String[] CARD = { "title", "subtitle", "image_uri", "intent_uri" };

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        List<String> path = uri.getPathSegments();
        if (path.size() == 1 && path.get(0).equals("widgets")) {
            MatrixCursor c = new MatrixCursor(CARD);
            c.addRow(new Object[] { "Hello from a plugin", "updated every 15 minutes", null, null });
            return c;
        }
        if (path.size() == 1 && path.get(0).equals("rows")) {
            MatrixCursor c = new MatrixCursor(new String[] { "id", "title" });
            c.addRow(new Object[] { "links", "Quick links" });
            return c;
        }
        if (path.size() == 2 && path.get(0).equals("rows") && path.get(1).equals("links")) {
            MatrixCursor c = new MatrixCursor(CARD);
            c.addRow(new Object[] { "Fire TV settings", null, null,
                    "intent:#Intent;action=android.settings.SETTINGS;end" });
            return c;
        }
        return null;
    }
    // getType, insert, delete, update: return null / 0
}
```

---

## Privacy and safety

- Web plugins can't run code on the TV: MaterialTV only reads your JSON and pictures, and shows them as plain
  text and downsampled images. All traffic is https.
- The servers you link to see the TV's IP address and `User-Agent` when MaterialTV fetches, like any website
  visit. Nothing about the TV, its apps or its user is sent.
- Webhooks run only when someone selects the tile, and ask the first time.
- Android plugins are off until switched on, and are only asked the three URIs above.
- Removing a web plugin deletes its manifest, cached data, its rows on Home, its shortcuts in Favourites and its
  webhook approvals. To remove an Android plugin, uninstall the app.
