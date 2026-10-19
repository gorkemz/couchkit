# Step-by-step installation guide

This guide assumes you have never used adb. It takes about 15 minutes. Your computer and your TV
must be on the **same network** (connected to the same router).

> The screenshots are from Google TV. Menu names differ a little between brands. For example,
> older Android TVs say "Device Preferences" instead of "System". The steps are the same.

Contents:
1. [Turn on Developer options on the TV](#1-turn-on-developer-options-on-the-tv)
2. [Turn on USB debugging](#2-turn-on-usb-debugging)
3. [Find the TV's IP address](#3-find-the-tvs-ip-address)
4. [Install adb on your computer](#4-install-adb-on-your-computer)
5. [Download CouchKit](#5-download-couchkit)
6. [Connect to the TV](#6-connect-to-the-tv)
7. [Install](#7-install)
8. [Use it](#8-use-it)
9. [Undo and troubleshooting](#9-undo-and-troubleshooting)

---

## 1. Turn on Developer options on the TV

Open **Settings** with the remote. On Google TV, select the gear icon at the top right, then
*All settings*.

![Settings](images/setup/01-settings.png)

Open **System**.

![System](images/setup/02-system.png)

Open **About**.

![About](images/setup/03-about.png)

Scroll to the bottom to **Android TV OS build** (or "Build" / "Build number").

![Build number](images/setup/04-build-number.png)

With that row selected, press **OK** on the remote **7 times** in a row. You will see
*"You are now a developer!"*. If the TV asks for a PIN, enter it.

![You are now a developer](images/setup/05-you-are-a-developer.png)

## 2. Turn on USB debugging

Press **Back** to return to System. **Developer options** is now near the bottom of the list.
Open it.

![Developer options](images/setup/06-developer-options.png)

Under **Debugging**, turn on **USB debugging** and confirm with *OK*.

![USB debugging](images/setup/07-usb-debugging.png)

> **No cable needed.** Despite the name, most Android TVs also accept network connections on
> port 5555 while this is on. If the list also has **Network debugging**, **ADB over network** or
> **Wireless debugging**, turn that on too. Step 6 explains how wireless debugging works.

## 3. Find the TV's IP address

Go to **Settings → Network & Internet** and select your network. Write down the **IP address**.
It has four parts and usually starts with `192.168.` or `10.`, for example `192.168.1.23`.

![IP address](images/setup/08-ip-address.png)

> Tip: a DHCP reservation ("static IP") for the TV in your router settings keeps this address the
> same.

## 4. Install adb on your computer

adb is the command-line tool in Google's free "Android Platform Tools".

**macOS**
1. Open **Terminal**: press ⌘ + Space and type "Terminal".
2. If [Homebrew](https://brew.sh) is not installed, install it with the one-line command on its site.
3. Then run:
   ```bash
   brew install android-platform-tools
   ```

**Windows**
1. Install [Git for Windows](https://git-scm.com/download/win). It includes **Git Bash**, which is
   where the CouchKit script runs.
2. Download the [SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools)
   zip and extract it to `C:\platform-tools`.
3. In Git Bash, run this (it lasts for the current session):
   ```bash
   export PATH="$PATH:/c/platform-tools"
   ```

**Linux (Debian/Ubuntu)**
```bash
sudo apt install adb
```

Check that it works. This should print a version number:
```bash
adb version
```

## 5. Download CouchKit

1. Click the green **Code** button at the top of this page → **Download ZIP**, then unzip it. If you
   use git, `git clone` works too.
2. Open a terminal in that folder:
   - macOS: right-click the folder in Finder → *New Terminal at Folder*.
   - Windows: right-click inside the folder → *Open Git Bash here*.
3. Get the two apps (APKs). There are two ways:
   - **Easy:** download `couchkit-cast.apk` and `couchkit-home.apk` from the **Releases** section
     on the right side of this page and put them in the `dist/` folder.
   - **Build them yourself:** this needs JDK 21 and the Android SDK, which Android Studio includes.
     ```bash
     ./scripts/build.sh
     ```

## 6. Connect to the TV

```bash
adb connect 192.168.1.23
```

Use your TV's address instead of this one. The TV shows **"Allow USB debugging?"**. Tick
**Always allow from this computer** and select **OK**. Then run:

```bash
adb devices
```

You should see `192.168.1.23:5555  device`. If it says `unauthorized`, accept the prompt on the TV.

> **Wireless debugging (some Android 11+ TVs):** on the TV choose *Wireless debugging → Pair device
> with pairing code*. It shows an IP:port and a 6-digit code.
> 1. Pair:
>    ```bash
>    adb pair 192.168.1.23:37123
>    ```
>    (enter the code)
> 2. Connect: run `adb connect` with the **other** IP:port shown on the Wireless debugging screen.
> 3. Use that same address when installing: `--host 192.168.1.23:4xxxx`.

## 7. Install

First see what will happen. This changes nothing and does not connect to the TV:
```bash
./install.sh --dry-run
```

Then install:
```bash
./install.sh --host 192.168.1.23
```

The script first identifies the TV (brand, model, Android version). Before every change it asks
`[y/N]`; type `y` and press Enter. After each step it verifies the result (✓).

If the TV shows a **Play Protect** warning while installing, choose **Install anyway** with the
remote. Apps that don't come from the Play Store always trigger this.

![Play Protect](images/setup/09-play-protect.png)

What gets installed:

| Step | What happens |
|---|---|
| receiver | installs CouchKit Cast. It starts with the TV and comes to the front when someone connects. |
| launcher | installs CouchKit Home |
| autostart | TCL only: lets the vendor boot manager start both apps |
| home | makes CouchKit Home the default home screen |
| homekey | makes the remote's Home button open CouchKit Home. This uses an accessibility service, but it cannot read screen content. |

Optional:

```bash
./install.sh --host 192.168.1.23 --name "Living Room"                 # name shown to senders
./install.sh --host 192.168.1.23 --only launcher --config my-config.json   # weather, rates, scores...
./install.sh --host 192.168.1.23 --screensaver                        # CouchKit screensaver
./install.sh --host 192.168.1.23 --replace-stock-home                 # disable stock home (~300 MB RAM)
./install.sh --host 192.168.1.23 --debloat=safe                       # vendor ads/telemetry packages
```

`--replace-stock-home` and `--debloat` only run on **verified** devices
([installer/verified-devices.txt](../installer/verified-devices.txt)).

For `my-config.json`, copy [launcher/config.example.json](../launcher/config.example.json) and delete
the sections you don't want. The keys come from your own free accounts:
- **Featured row:** [TMDB](https://www.themoviedb.org/settings/api)
- **Match card:** [API-Football](https://dashboard.api-football.com/register)

## 8. Use it

- **Home screen:** press Home. Move with the arrow keys.
  - **OK** opens an app.
  - **Holding OK** starts move mode.
  - In move mode, **holding OK again** hides the app.
- **Screen mirroring:**
  - iPhone/iPad: Control Center → **Screen Mirroring** → pick your TV's name ("Living Room TV" by
    default, or the name you gave with `--name`).
  - Mac: menu bar → Control Center → **Screen Mirroring**.
  - For audio and video, use the cast/stream button inside apps.
- When mirroring stops, the TV goes back to what was on screen before.

![Home screen](images/home.png)

## 9. Undo and troubleshooting

```bash
./install.sh --host 192.168.1.23 --status   # read only
./install.sh --host 192.168.1.23 --undo     # reverts everything this script did
./install.sh --host 192.168.1.23 --rescue   # brings the stock home screen back, even without a journal
```

| Problem | Fix |
|---|---|
| `adb connect` → *connection refused* | Turn on network / wireless debugging (step 2). Are the TV and computer on the same network? |
| `unauthorized` | Developer options → *Revoke USB debugging authorizations*, turn USB debugging off and on, connect again |
| The install hangs | A Play Protect prompt may be open on the TV. Choose *Install anyway*. |
| The TV is not in the mirroring list | Is your phone on the same network? Open CouchKit Cast once on the TV. Guest Wi-Fi and VLANs hide devices from each other. |
| Home still opens the old home screen | `./install.sh --host IP --only homekey` |
| Black screen or recovery screen after a reboot | `./install.sh --host IP --rescue` |
| Nothing helps | Factory reset from the TV's settings. This erases the data on the TV. |

If you get stuck, open a GitHub *Issue* and include the `--status` output. Remove your IP address
and account names first.
