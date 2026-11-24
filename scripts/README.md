# Fire TV setup from a computer

Since Fire OS 7.6.6.9, Amazon stops apps on a Fire TV from using that TV's own ADB. On those
builds, MaterialTV's built-in setup can't reach the TV, so the setup has to run from another device.
These scripts do it from a Windows, Mac or Linux computer on the same network as the TV.

They run the same steps as the app's setup (`src/app/m3home/Setup.java`): the same commands, in
the same order, with the same skip rules and the same lists (`assets/setup/`). They also write the
same change log on the TV, so you can set up from the computer and undo from the app, or the
other way round.

| Script | Runs on |
|---|---|
| `setup.ps1` | Windows (Windows PowerShell 5.1 or PowerShell 7). No admin rights, no modules. |
| `setup.sh` | macOS and Linux (bash 3.2 or newer, `curl`; `unzip` only if adb has to be downloaded). |

## Before you start
1. On the TV, turn on **Settings › My Fire TV › Developer options › ADB debugging**.
   If **Developer options** is missing, open **Settings › My Fire TV › About** and select the
   device name 7 times.
2. Find the TV's IP address in **Settings › My Fire TV › About › Network** (the MaterialTV setup
   screen shows it too). The examples below use `192.168.1.50`. Use your TV's address.
3. The first time, the TV asks **"Allow USB debugging?"** for your computer. Tick **Always allow
   from this computer**, then select **Allow**. The script waits for you.

You don't need to install adb. If `adb` isn't on your PATH, the script downloads Google's official
platform-tools into a cache folder in your user profile and uses it from there.

## Windows
Step-by-step guide on the project site: <https://brick-bread.github.io/MaterialTV/setup.html>.

Download `setup.ps1` from
<https://raw.githubusercontent.com/Brick-Bread/MaterialTV/main/scripts/setup.ps1>
(right-click › Save as), then in that folder run:

```powershell
powershell -ExecutionPolicy Bypass -File setup.ps1 -Tv 192.168.1.50
powershell -ExecutionPolicy Bypass -File setup.ps1 -Tv 192.168.1.50 -Undo
```

Or run it straight from GitHub in a PowerShell window:

```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/Brick-Bread/MaterialTV/main/scripts/setup.ps1))) -Tv 192.168.1.50
```

`irm … | iex` works too, but `iex` can't pass parameters. Set them as environment variables first:

```powershell
$env:M3_TV = '192.168.1.50'      # optional: you're asked otherwise
$env:M3_UNDO = '1'               # optional: undo instead of setup
irm https://raw.githubusercontent.com/Brick-Bread/MaterialTV/main/scripts/setup.ps1 | iex
```

## macOS and Linux

```bash
curl -fsSLO https://raw.githubusercontent.com/Brick-Bread/MaterialTV/main/scripts/setup.sh
bash setup.sh --tv 192.168.1.50
bash setup.sh --tv 192.168.1.50 --undo
```

Or in one line:

```bash
curl -fsSL https://raw.githubusercontent.com/Brick-Bread/MaterialTV/main/scripts/setup.sh | bash -s -- --tv 192.168.1.50
```

## Options

| `setup.ps1` | `setup.sh` | Environment | What it does |
|---|---|---|---|
| `-Tv IP` | `--tv IP` | `M3_TV` | The TV's IP address. Port 5555 is added if you leave it out. Asked for if not given. |
| `-Undo` | `--undo` | `M3_UNDO=1` | Put back what setup changed. |
| `-Yes` | `--yes` | `M3_YES=1` | Don't ask questions; use the default answers shown below. |

## What setup does
1. Connects with `adb connect IP:5555` and checks that the TV is an Amazon Fire TV on Fire OS 7 or
   newer (Android 9+). It warns you otherwise.
2. **MaterialTV:** if it isn't installed, downloads the latest `m3home.apk` from this project's GitHub
   Releases, checks it against the release's `m3home.apk.sha256`, and installs it. If an older
   version is installed, it offers to update it.
3. Asks what to set up (default in brackets):
   - **MaterialTV permissions [Y]:** turns on the Home button service, grants
     `WRITE_SECURE_SETTINGS`, allows the now-playing listener and "install unknown apps" for
     updates, keeps MaterialTV out of battery optimisation, and sets MaterialTV as the screensaver.
   - **Debloat and privacy [Y]** (dumbtv's lists):
     - Switches off Amazon's ad, tracking and telemetry apps (`packages.txt`). It never touches
       `never-disable.txt`, and only switches off apps that are installed and still on.
     - Turns on background-data restriction.
     - For apps Amazon won't let you switch off (`protected.txt`), blocks running in the
       background and background data.
     - Applies the privacy settings in `settings.txt`.
   - **Keep Alexa and the voice remote [Y]:** say no to switch off `alexa.txt` too.
   - **SmartTube instead of YouTube [n]:** installs a pinned SmartTube release, checked against its
     SHA-256, and switches off the Amazon YouTube app.
   - **Tailscale [n]:** installs the official APK from `pkgs.tailscale.com`, checked against its
     published SHA-256, and makes it the always-on VPN. Open Tailscale once on the TV to sign in.
   - If the TV is set to **stay awake while plugged in**, which stops any screensaver, it offers
     to turn that off [Y].
4. Writes the change log to the TV (see below) and prints a summary.

Running setup again is safe. Steps already done are skipped or repeated harmlessly, and the
change log keeps the values from before the first run.

## Undo
Run with `-Undo` / `--undo`, or use **Settings › Fire TV setup › Undo setup / uninstall** in MaterialTV.
It asks what to put back (default in brackets):
- **Undo debloat and privacy [Y]:** switches the apps back on, removes the background limits, and
  puts back each setting's earlier value (a setting that didn't exist before is deleted again).
  It also puts back "stay awake while plugged in" if setup changed it.
- **Remove SmartTube [Y]:** uninstalls SmartTube if setup installed it, and switches the Amazon
  YouTube app back on.
- **Remove Tailscale [Y]:** puts back the earlier always-on VPN, and uninstalls Tailscale if
  setup installed it.
- **Uninstall MaterialTV too [n]:** restores Amazon's screensaver, removes MaterialTV's accessibility
  service and now-playing listener, then uninstalls MaterialTV. Amazon's home screen comes back.

Undo uses the change log when there is one. On a TV set up without it (for example with dumbtv
directly), it falls back to dumbtv's restore lists (`packages.txt`, `alexa.txt`,
`settings.restore.txt`), the same way the app does. When it's done, it deletes the change log.

## The change log
Setup records every change in
`/sdcard/Android/data/app.m3home/files/setup/ledger.json` on the TV. It's the same file the app
and the phone mode use. It holds:
- which apps were switched off and which were installed
- each setting's earlier value
- the earlier screensaver, accessibility services, always-on VPN, background-data state and
  "stay awake" setting

A value that's already recorded is never overwritten, so the first recorded value is always the
original. The file is removed with MaterialTV if you uninstall it.

## Privacy
- The scripts only talk to your TV (over ADB on your local network) and download from:
  - `dl.google.com`: Android platform-tools (adb), only if adb isn't installed
  - `raw.githubusercontent.com`: the setup lists
  - `github.com` and `api.github.com`: MaterialTV releases, and SmartTube if you choose it
  - `pkgs.tailscale.com`: Tailscale, if you choose it
- Every APK is checked against a SHA-256 checksum before it's installed.
- Nothing is sent anywhere else. The scripts collect nothing, and don't store your TV's address.
- adb keeps its own key pair in your user folder (`~/.android`). That key is what the TV's
  "Allow USB debugging?" prompt approves.

The setup lists come from dumbtv (<https://github.com/humblemuzzu/dumbtv>), MIT License; see
`assets/setup/LICENSE-dumbtv.txt`. SmartTube and Tailscale are third-party apps, downloaded from
their official sources only if you choose them.
