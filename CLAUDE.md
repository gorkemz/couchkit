# CLAUDE.md: installing CouchKit with Claude Code

You are helping a user install CouchKit on **their own** Android TV from this repository. The TV is
a real device in someone's home. Be careful, explain each step in plain words, and never change the
TV without the user's explicit approval for that step.

## Ground rules

1. Change the TV **only through `./install.sh`**. Don't run your own `pm disable`, `pm uninstall`,
   `settings put`, `appops` or `input keyevent` commands. Read-only adb queries are fine:
   `getprop`, `pm list packages`, `dumpsys role`, `settings get`, `appops get`.
2. Never disable `com.google.android.apps.tv.launcherx` or `com.google.android.tungsten.setupwraith`
   by hand. The `replace-stock-home` step does it safely: both together, with a precondition check,
   verification and automatic rollback.
3. Steps that disable packages (`--replace-stock-home`, `--debloat`) are for devices listed in
   `installer/verified-devices.txt`. Offer `--allow-unverified` only if the user asks for it, after
   explaining the risk (DISCLAIMER.md).
4. The installer asks y/N questions on a terminal you cannot answer. Run one step at a time with
   `--only <step> --yes`, and only after the user approved that specific step in chat.
5. Don't print or store the user's API keys anywhere except the config file they asked you to write.
   Never commit `config.json`.
6. Don't build or install the `experimentalBatteries` flavor unless the user explicitly asks for it
   and has read `launcher/extras/experimental-batteries-bridge/README.md`.
7. If something goes wrong (black screen, TV stuck, Home not working), stop. Run
   `./install.sh --host IP --undo`, or `--rescue` if there is no journal. Then report what happened.

## Flow

1. **Prerequisites.**
   - Check `adb version`. If adb is missing, tell the user how to install it (README) and don't
     install it yourself without asking.
   - Ask for the TV's IP address. Remind the user to enable Developer options → USB/Network
     debugging, and to accept the "Allow debugging?" prompt on the TV.
2. **Inspect, read only.** Run `./install.sh --host IP --status`. Tell the user:
   - the profile: verified, tcl-unverified, generic or unsupported
   - what is already installed
   - the current home screen and accessibility setting
   - whether an undo journal exists
3. **APKs.**
   - If `dist/couchkit-cast.apk` and `dist/couchkit-home.apk` are missing, offer two options:
     a) download them from the project's GitHub Releases, or
     b) build them with `./scripts/build.sh`, which needs JDK 21 and the Android SDK.
   - Ask which one they want.
4. **Plan.**
   - Propose a step list that fits the profile and what the user wants. Example:
     receiver → launcher → autostart → home → homekey, then optional screensaver,
     replace-stock-home and debloat.
   - For each step, say in one sentence what changes on the TV and how to undo it. The README
     table has these.
   - Show the full plan with `./install.sh --dry-run --simulate <closest profile> ...flags`, then
     ask for approval.
5. **Configure (optional).**
   - If the user wants weather, exchange rates, the featured row or match scores, build
     `my-config.json` from `launcher/config.example.json`.
   - Keys are the user's own: TMDB for the featured row, API-Football for scores.
   - Pass the file with `--config my-config.json` in the `launcher` step.
6. **Execute.**
   - Run each approved step with `./install.sh --host IP --only <step> --yes [flags]`.
   - Read the output. Every step prints a verification line. If verification fails, stop and
     report.
   - After `home` / `replace-stock-home`, ask the user to press Home on the remote and confirm the
     CouchKit Home screen appears.
   - Suggest one reboot test, and do it only if the user agrees and is at the TV. After the reboot,
     check with `--status`.
7. **Finish.** Summarize:
   - what was changed
   - where the undo journal is (`~/.couchkit/<ip>.journal`)
   - the two recovery commands, `--undo` and `--rescue`

## Repository map

- `installer/install.sh`: the only tool that changes the TV. `--help` lists everything.
- `installer/verified-devices.txt`, `installer/debloat/*.txt`, `installer/sim/*.env`: device data.
- `receiver/`: screen receiver (GPL-3.0, modified upstream). `launcher/`: home screen (Apache-2.0).
- `docs/DEVICES.md`: why the steps are what they are.
