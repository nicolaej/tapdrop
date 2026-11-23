# Instructions for coding assistants

This file is for an AI coding assistant working in this repo: Claude Code,
Codex, Gemini CLI or any other. People read [README.md](README.md).

## What this repo is

TapDrop is a Wear OS app that makes a watch answer NFC readers as a tag. The
wearer opens an item on the watch and holds it to a phone, and the phone gets a
contact card, a Wi-Fi login, a UPI payment request, a location or a link. The
receiving phone needs no app.

## If the user wants TapDrop set up on their watch

This is the common request. Follow
[skill/tapdrop-watch/SKILL.md](skill/tapdrop-watch/SKILL.md) from start to
finish. It is the single source of truth for setup, and it covers adb,
pairing, installing, filling in the user's details and testing.

- Do the adb work with `python skill/tapdrop-watch/scripts/watch.py`. Run it
  with `status` first. Its commands are listed at the top of the file.
- Install the prebuilt app at `skill/tapdrop-watch/assets/tapdrop.apk`. Building
  from source is not needed for setup.
- When a step fails, read
  [skill/tapdrop-watch/references/troubleshooting.md](skill/tapdrop-watch/references/troubleshooting.md)
  before improvising. Most failures are already written up there.

## Rules that hold for every task here

These exist because the work touches someone's own watch and phone.

- **Ask before downloading.** Name the file, the source and the size.
- **The user types the pairing code.** Print the `adb pair` command and let
  them run it. Don't ask for the code in chat.
- **Look before you tap.** The watch drops to its watch face after a few idle
  seconds, and simulated input goes to whatever is on screen. Confirm TapDrop
  is in front (`watch.py foreground`) before any `input` command.
- **Don't turn on One-tap sharing for the user.** It switches off another
  app's NFC service and removes a confirmation step. Explain it and let them
  press the button on the watch.
- **Treat what they give you as private.** A Wi-Fi password or UPI ID goes to
  the watch and nowhere else. Don't repeat it back, log it, or put it in a
  commit.
- **Say what you could not check.** You can read the watch's log. You cannot
  see the other phone's screen unless the user tells you what it showed.
- **Offer to turn Wireless debugging off** when the setup is done.

## Repo map

| Path | What it is |
|---|---|
| `skill/tapdrop-watch/` | The setup procedure, `watch.py`, troubleshooting, and the bundled APK and pay page |
| `watch-app/` | The app's source. Plain Java, no Gradle. Android framework classes, plus AndroidX Tiles for the tile |
| `watch-app/build.ps1`, `build.sh` | Build the APK on Windows, or on macOS and Linux, and refresh the copy in `skill/` |
| `watch-app/libraries.txt` | The pinned AndroidX libraries and their SHA-256, read by both build scripts |
| `watch-app/proguard.pro` | R8 rules for shrinking those libraries. Code reached only by reflection needs a rule here |
| `web/` | The project's GitHub Pages site. `pay.html` hands a UPI request to the payer's app, and the app links to the published copy by default. A push to `main` that touches `web/` republishes it |
| `tools/` | PowerShell helpers for pairing and reconnecting on Windows |
| `docs/` | How it works, and the images the README uses |

## If the user wants to change the app

- Source is in `watch-app/src/dev/tapdrop/`. Start with `Kinds.java` (what can
  be shared) and `NdefTagService.java` (the NFC side).
  [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md) explains the parts that are not
  obvious from the code.
- Build with `watch-app/build.ps1` on Windows or `watch-app/build.sh` on macOS
  and Linux. Either needs a JDK 11 or newer, and downloads Android build-tools
  35, platform 35 and the libraries in `libraries.txt` on first run, checked
  against their checksums. Keep the two scripts doing the same steps.
- A build installs to the first connected device that reports itself as a
  watch. Pass `-NoInstall` (`--no-install` for `build.sh`) to build only.
- A build also overwrites `skill/tapdrop-watch/assets/tapdrop.apk`, signed with
  that computer's debug key. Don't commit that APK from a different computer
  than the last one that built it: installs of the old one could not update.
- `watch-app/tests/test_emulator.py` tests the tile and the Quick tap page on a
  Wear OS emulator: build with `--debuggable --no-install` (`-Debuggable
  -NoInstall`), then run it with Python. It refuses real watches, since each
  test empties the app, and puts the emulator's TapDrop data back afterwards.
- Nothing automated covers NFC, since the emulator has none. Verify that on a
  real watch: read the log, take a screenshot, and have the user tap a phone.
  Don't report a change as working from a successful compile alone.
- The app ID is `dev.tapdrop`. Don't change it: an installed app cannot be
  updated across an ID change.

## What has and has not been tested

Tested: a Galaxy Watch8 Classic and a Galaxy S24 Ultra, both on Android 17,
set up from Windows.

Not tested: other watch models, iPhones as the receiving phone, setup from
macOS or Linux, and the location item. Tell the user when their situation
falls outside what was tested, and treat it as a first run.
