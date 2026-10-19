# CouchKit

Turn a slow, ad-filled Android TV / Google TV into a fast, clean living-room screen:

- **CouchKit Cast**: a screen-mirroring and media receiver for AirPlay-compatible senders
  (iPhone, iPad, Mac). It starts on boot and comes to the front when someone connects. Based on
  [android-airplay-server](https://github.com/jqssun/android-airplay-server) and UxPlay. GPL-3.0.
- **CouchKit Home**: a lightweight glass-style home screen with a featured row, glass dock,
  screensaver, weather, exchange rates and live match card. Each extra is optional and uses your
  own API key. Apache-2.0.
- **Installer**: a single adb script. It checks the device first, verifies every change and records
  how to undo it.

Türkçe: [README.tr.md](README.tr.md)

> CouchKit is not affiliated with Apple, Google, TCL or any streaming service. Product names are
> used only to describe compatibility ([TRADEMARKS.md](TRADEMARKS.md)). Read
> [DISCLAIMER.md](DISCLAIMER.md) before changing your TV.

## Which devices?

| Device | What you get |
|---|---|
| **Verified**: TCL Google TV "Smart TV Pro" (P735 series), Android 11, firmware `V8-R51MT05-LF1V620` | everything, including replacing the stock home screen and debloat |
| Other TCL Android/Google TV | apps, start-on-boot fix and default home. Disabling packages needs `--allow-unverified`. |
| Any other Android TV (SDK 26+) | safe mode: apps, permissions and default home. Nothing is disabled. |
| Phones, tablets, non-TV devices | refused |

The list of verified devices is in [installer/verified-devices.txt](installer/verified-devices.txt).
Reports for other models are welcome: see [docs/DEVICES.md](docs/DEVICES.md).

## Install without help (about 10 minutes)

1. **On the TV:** Settings → System → About → click *Build* 7 times → Developer options →
   turn on **USB debugging** / **Network debugging**. Note the TV's IP address (Settings →
   Network).
2. **On your computer:** install adb.
   - macOS: `brew install android-platform-tools`
   - Debian/Ubuntu: `sudo apt install adb`
   - Windows: use WSL
3. **Get the apps.** Either download `couchkit-cast.apk` and `couchkit-home.apk` from the
   Releases page into `dist/`, or build them with `./scripts/build.sh` (needs JDK 21 and the
   Android SDK).
4. **Preview first.** This changes nothing and does not connect to the TV:
   ```
   ./install.sh --dry-run
   ```
5. **Install:**
   ```
   ./install.sh --host 192.168.x.y
   ```
   The TV shows *Allow debugging?*. Tick *Always allow* and press OK. The script asks before
   every change.

Optional extras:

```
./install.sh --host 192.168.x.y --name "Bedroom TV" --config my-config.json --screensaver
./install.sh --host 192.168.x.y --replace-stock-home     # frees ~300 MB RAM (verified devices)
./install.sh --host 192.168.x.y --debloat=safe           # vendor telemetry/ads (verified devices)
./install.sh --host 192.168.x.y --only receiver,autostart  # receiver only
```

Undo and status:

```
./install.sh --host 192.168.x.y --status   # read only
./install.sh --host 192.168.x.y --undo     # reverts everything recorded, newest first
./install.sh --host 192.168.x.y --rescue   # brings the stock home screen back
```

### What each step does

| Step | Change on the TV | Undo |
|---|---|---|
| `receiver` | installs CouchKit Cast, allows it to draw over apps, sets name and start-on-boot | uninstall, permission reset |
| `launcher` | installs CouchKit Home, optionally pushes `config.json` | uninstall |
| `autostart` | TCL only: allows the vendor boot manager to start both apps (`appops` 100/101, only if the firmware names them `APP_AUTO_START` / `APP_ASSOC_START`) | ops back to default |
| `home` | makes CouchKit Home the default home screen | previous home restored |
| `homekey` | Google TV only: enables CouchKit Home's Home-button accessibility service (it cannot read screen content) | previous accessibility setting restored |
| `replace-stock-home` (opt-in) | disables the stock launcher **and** setup wizard together, after checking CouchKit Home is the home role holder. If the Home check fails it rolls back immediately. | both re-enabled, stock home set |
| `debloat` (opt-in) | `pm disable-user` on [installer/debloat/](installer/debloat) lists. Nothing is ever uninstalled. | `pm enable` for each |
| `screensaver` (opt-in) | sets the CouchKit screensaver | previous screensaver restored |

**Why the stock launcher and the setup wizard go together:** on the verified TV, disabling only the
stock launcher makes the setup wizard's recovery screen take over Home at the next boot. The TV then
looks frozen. The installer never disables one without the other.

## Install with Claude Code

Open this folder in [Claude Code](https://claude.com/claude-code) and say *"install CouchKit on my TV"*.
Claude reads [CLAUDE.md](CLAUDE.md), inspects the TV read-only, explains which steps fit your device,
and runs the installer one step at a time after you approve.

## Launcher configuration

Copy [launcher/config.example.json](launcher/config.example.json) to `my-config.json`. Keep the
sections you want, add your own keys and pass it with `--config`. Without a config file the home
screen shows only apps, clock and gradient backgrounds. See [launcher/README.md](launcher/README.md).

## Build from source

```
git clone <this repo> && cd couchkit   # submodules are only needed for --native-source
./scripts/build.sh                  # receiver uses upstream's unmodified native libs (armeabi-v7a)
./scripts/build.sh --native-source  # build the receiver's native code from source (all ABIs)
```

Release signing uses your own key: see [receiver/README.md](receiver/README.md) and
[launcher/README.md](launcher/README.md). This project never ships a signing key.

## Licenses

- `receiver/` is GPL-3.0. It is a modified upstream: [receiver/CHANGES.md](receiver/CHANGES.md).
- Everything else is Apache-2.0 ([LICENSE](LICENSE)).
- Third-party components are listed in [THIRD_PARTY.md](THIRD_PARTY.md) and [NOTICE](NOTICE).

## Contributing

Run `./scripts/secret-scan.sh` before every commit. Follow the rules in
[TRADEMARKS.md](TRADEMARKS.md): no third-party logos, fonts, wallpapers or videos, and no brand
names in app or package names.
