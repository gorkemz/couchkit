# Experimental batteries bridge

> **WARNING: read this before using anything in this folder.**
>
> - This is an **unofficial** tool. It uses an **undocumented web API** of iCloud that was never
>   meant for third-party clients. It can stop working at any time.
> - Using it **may violate the provider's terms of service**.
> - Automated sign-ins and session reuse from a TV **may get your account flagged, challenged or
>   locked**.
> - **Use at your own risk.** The CouchKit project gives no warranty and no support for it.
> - CouchKit is **not affiliated with, endorsed by or sponsored by Apple Inc.** "Apple", "iCloud" and
>   device names are trademarks of Apple Inc. and are used here only to describe what the tool talks to.
> - **Never commit session files** (`session/`, `*.session`, `*.cookiejar`, `icloud_session.json`).
>   They are equivalent to being signed in to your account. `.gitignore` excludes them; double-check
>   before you push anything.
> - This feature is **not built into release APKs**. It only exists in the `experimentalBatteries`
>   Gradle flavor, which you have to build yourself.

## What it does

The `experimentalBatteries` edition of CouchKit Home can show the battery levels of your own
phone, watch, earbuds, tablet and laptop as a small row of rings on the home screen. This folder
contains the computer-side helper that signs in once (with 2FA) and hands a web session to the TV.
After that the TV refreshes the list every 10 minutes by itself until the session expires.

## Password handling (what is stored where)

| Mode | On your computer | On the TV |
|---|---|---|
| **Default: session-token-only** | Session cookies in `session/` (mode 0700). The password is used for the sign-in only and never written anywhere. | Session token, trust token and web cookies in app-private, non-backed-up storage (`noBackupFilesDir`). **No password.** |
| `--store-password` (opt-in, not recommended) | Same as default. | Additionally the password, RSA-OAEP encrypted to a non-exportable Android Keystore key. The TV can then renew the session itself (at most every 30 min; it stops after any rejection or 2FA prompt). |

In the default mode the web session typically expires after some hours of inactivity (for example
overnight while the TV sleeps). The battery pill then simply disappears; run `login.py` again.
A later hand-off without a password also deletes any password stored earlier.

The hand-off file (`files/icloud_session.json` in the app's external folder) is imported on the
next refresh and deleted immediately afterwards, even if it cannot be parsed.

## Setup

1. Build and install the experimental edition (never published as a release):

   ```bash
   cd launcher
   ./gradlew assembleExperimentalBatteriesRelease
   adb -s HOST:PORT install -r app/build/outputs/apk/experimentalBatteries/release/app-experimentalBatteries-release.apk
   ```

2. Create a virtual environment for the bridge:

   ```bash
   cd launcher/extras/experimental-batteries-bridge
   python3 -m venv .venv
   .venv/bin/pip install -r requirements.txt
   ```

3. Sign in and hand over the session (the TV must be reachable over adb):

   ```bash
   .venv/bin/python login.py --device HOST:PORT
   ```

   You are asked for the account, the password (hidden) and a 2FA code. The script prints the
   devices it can see and pushes the session to the TV. The script never accepts provider terms
   for you; if new terms must be accepted, do it in a web browser first.

Optional, opt-in only:

```bash
.venv/bin/python login.py --device HOST:PORT --store-password
```

You must type `YES` at the warning prompt; otherwise it continues in session-token-only mode.

## Other commands

Re-send a still-valid saved session without a password or 2FA (e.g. after clearing app data):

```bash
.venv/bin/python tvsession.py --device HOST:PORT
```

Last status written by the TV (contains no secrets):

```bash
adb -s HOST:PORT shell cat /sdcard/Android/data/io.couchkit.home/files/batteries_status.json
```

`{"updated": <epoch ms>, "status": "ok" | "login_required" | "error", "count": 4, "error": "..."}`

Forget the session on the computer (does not affect the TV):

```bash
rm -rf session
```

Forget it on the TV: clear CouchKit Home's app data, or install the `standard` edition.

## Hand-off format (`files/icloud_session.json`, computer to TV, one-shot)

```json
{
  "v": 1, "exported": 1792368717664, "apple_id": "...",
  "client_id": "...", "session_token": "...", "trust_token": "...", "account_country": "...",
  "dsid": "...", "findme_url": "https://...", "with_family": false,
  "cookies": [{"name": "...", "value": "...", "domain": "...", "path": "/", "expires": 0, "secure": true}],
  "password_enc": "only present with --store-password"
}
```

`expires` is epoch ms, 0 = session cookie. The TV keeps the same structure in its private storage
and updates refreshed cookies and tokens there.

## Known limitations

- Only your own devices are listed (family sharing is off: `WITH_FAMILY = False` in `tvsession.py`).
- At most 5 devices are drawn; devices without battery info are skipped.
- To look like the web client the session was created with, the TV and the underlying Python
  library send a desktop browser user-agent. That is client impersonation, one more reason this
  stays an unsupported experiment.
