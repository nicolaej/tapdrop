<p align="center">
  <img src="docs/banner.svg" alt="TapDrop. Tap your watch on a phone. It gets what you chose to share: a contact, Wi-Fi, a payment request, your location, a link, or anything an NFC tag can hold." width="820">
</p>

<p align="center">
  <b>Turn a Galaxy Watch into an NFC tag you can program.</b><br>
  Pick something on your wrist, tap any phone, and that phone gets it. The other phone needs no app.
</p>

<p align="center">
  <a href="https://harshtripathi272.github.io/tapdrop/#video">
    <img src="docs/promo.gif" alt="A phone touches a watch. The screen floods with colour and the phone shows a saved contact, then a payment request for 250 rupees." width="720">
  </a><br>
  <a href="https://harshtripathi272.github.io/tapdrop/#video"><b>Watch the 46 second video, with sound</b></a>
</p>

## Start here

| You have | Go to |
|---|---|
| **A Galaxy Watch4 or later, or another Wear OS watch with NFC** | [Quick start](#quick-start): a coding assistant sets it up in about ten minutes. |
| **No coding assistant** | [Doing it by hand](#doing-it-by-hand): the same steps as commands. |
| **An Apple Watch, an older Tizen Galaxy Watch, a Fitbit or a Garmin** | TapDrop can't run there. It needs Wear OS. |
| **Want to see it first** | [What you get](#what-you-get): screenshots from the watch. |

[How it works](#how-it-works) · [Quick start](#quick-start) · [What you get](#what-you-get) ·
[Sharing with the app closed](#sharing-with-the-app-closed) · [Learn from phone](#learn-from-phone) ·
[Supported devices](#supported-devices) · [Doing it by hand](#doing-it-by-hand) ·
[Removing it](#removing-it) · [Questions](#questions-people-ask) · [Building](#building-from-source)

## How it works

An NFC sticker is a tiny piece of storage that any phone can read by touching it. Android lets an
app answer a phone the same way a sticker would. TapDrop does that from your watch, and decides
what the "sticker" holds at the moment of the tap.

| Step | What happens |
|---|---|
| **Choose** | Open an item on the watch: your contact, a Wi-Fi network, a payment request, your location, a link. |
| **Tap** | Hold the watch against a phone. The read takes about a tenth of a second and the watch buzzes. |
| **Done** | The phone offers to save the contact, join the network, open the payment screen or open the link. |

The receiving phone installs nothing and pairs with nothing. Details are in
[docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md).

## Quick start

You need the watch, a computer on the same Wi-Fi, and a coding assistant that can run commands on
that computer: Claude Code, Codex, Gemini CLI or similar. Python 3 has to be installed. Nothing has
to be compiled.

| Where | What to do |
|---|---|
| **1. On the watch** | Settings → About watch → Software information → tap **Software version** five times. Then Settings → Developer options → turn on **ADB debugging** and **Wireless debugging**. |
| **2. On your computer** | `git clone https://github.com/harshtripathi272/tapdrop`, open that folder in your assistant, and paste the prompt from [`PROMPT.md`](PROMPT.md) with your watch model filled in. |
| **3. Follow along** | The assistant installs Google's `adb` if you lack it, pairs with the watch, installs TapDrop, asks what you want to share, and watches a test tap with you. |

Cloning matters. The assistant follows this repo's own setup procedure in
[`skill/tapdrop-watch/`](skill/tapdrop-watch/SKILL.md) and installs the app bundled there, so it is
not inventing steps.

Two things stay in your hands. You type the watch's six digit pairing code yourself, and you decide
on the watch whether to switch on [one-tap sharing](#sharing-with-the-app-closed).

Claude users can also install the setup procedure as a skill: package `skill/tapdrop-watch` into a
`.skill` file and add it to Claude.

## What you get

| | | |
|:---:|:---:|:---:|
| <img src="docs/screenshots/home.png" width="230" alt="The TapDrop home list on the watch: a contact, a Wi-Fi network and a payment request in one rounded group"> | <img src="docs/screenshots/share-contact.png" width="230" alt="The share screen for a contact, with rings travelling outward and an Edit button"> | <img src="docs/screenshots/share-pay.png" width="230" alt="The share screen for a payment request showing an amount in rupees"> |
| *Your items* | *Ready to tap* | *Pay me, amount set with the bezel* |
| <img src="docs/screenshots/home-add.png" width="230" alt="Further down the home list: the kinds of item that can be added"> | <img src="docs/screenshots/learn.png" width="230" alt="The Learn screen, waiting for an NFC tag app to write to the watch"> | <img src="docs/screenshots/quick-tap.png" width="230" alt="The Quick tap screen: choose which item is shared while TapDrop is closed"> |
| *Everything you can add* | *Learn from phone* | *Quick tap* |

| Item | What the other phone gets |
|---|---|
| **Contact** | Your name, number and email, as a card it offers to save. |
| **Wi-Fi** | A network and its password, as an offer to join. |
| **Pay me** | A UPI payment request with the amount filled in. Turn the bezel to set the amount. |
| **Location** | A map link to where the watch is right now. |
| **Link** | Any web address: your Instagram, a portfolio, a video. |
| **Learned tag** | Whatever an NFC tag app wrote to the watch. See [Learn from phone](#learn-from-phone). |

The app is built to look like part of the watch: Samsung One UI's grouped rows, colours and type,
with a curved scroll indicator. It is about 40 KB.

## Sharing with the app closed

Opening an item and tapping is the fast path: one tap. You don't have to open the app, though.

**Quick tap.** If a phone taps the watch while TapDrop is closed, the watch shows "Select an app to
use". Pick **TapDrop tag** and tap the phone again. The watch sends your quick-tap item. Choose
which item that is, or "Nothing", on the Quick tap screen at the bottom of the home list.

**The TapDrop tile** shows your quick-tap item, with its icon, in the watch's tile carousel. Add it
from the tile editor, then tap it to open the Quick tap screen and pick another. Like an item's
share screen, it sends rings out to the edge while it is ready to answer a phone, and shows a tick
and "Sent" when a phone has read the item. The tile only shows and opens the choice. It cannot switch NFC on, since Android lets only system apps do
that, so NFC still has to be on in the watch's settings.

The second tap exists because Samsung Health answers the same NFC tag ID as TapDrop, so the watch
asks which you meant. It also means a passing phone gets nothing unless you pick TapDrop on your own
wrist.

**One-tap sharing.** On the same screen, Turn on switches off Samsung Health's NFC tag service,
using an Android setting made for this clash. TapDrop then answers straight away, with the app
closed and with the screen off. It has two costs, and the watch tells you both before you agree:

- Samsung Health's NFC tag feature, which some gym machines use, stops working.
- Nobody is asked before your quick-tap item is sent. Any phone that touches the watch gets it.
  Keep that item to something you would hand to a stranger.

Turn off on the same screen gives the service back.

## Learn from phone

Add → **Learn from phone** turns the watch into an empty, writable tag. Open any NFC tag-writing app
on a phone, such as NFC Tools, build a record, press Write and hold the phone to the watch. TapDrop
saves what arrives as a new item and shares it back unchanged. It names the item from its contents.

This is the quick way to load a Wi-Fi password without typing on a watch, and the way to share
anything TapDrop has no form for: a phone number to call, plain text, an app link.

The watch accepts writes only while the Learn screen is open. It copies the data ordinary NFC
stickers hold. It cannot copy transit, access or payment cards, which rely on encryption and a fixed
chip ID.

## Supported devices

| | Status |
|---|---|
| **Galaxy Watch8 Classic, Android 17** | Tested. Everything on this page except Location was checked on it. |
| **Other Galaxy Watches from the Watch4 on** | Should work. Same platform, not yet tried. |
| **Pixel Watch and other Wear OS watches with NFC** | Sharing should work. One-tap sharing knows about Samsung Health only. |
| **Receiving phone: Android** | Tested with a Galaxy S24 Ultra on Android 17. |
| **Receiving phone: iPhone** | Not tested. iPhones act on web links from a tag, so Link and Location are the likely ones to work. |
| **Setup from Windows** | Tested. |
| **Setup from macOS or Linux** | The helper script supports both, but nobody has run it there yet. |

Tried it on something else? Please [tell us what happened](CONTRIBUTING.md).

## Doing it by hand

Everything the assistant does, you can run. You need Python 3 and Google's
[platform-tools](https://developer.android.com/tools/releases/platform-tools) for `adb`.

```sh
# 1. See what is connected. The watch needs Wireless debugging on, same Wi-Fi as you.
python skill/tapdrop-watch/scripts/watch.py status

# 2. Pair once. On the watch: Wireless debugging > Pair new device.
python skill/tapdrop-watch/scripts/watch.py pair-target   # prints the adb pair command to run
python skill/tapdrop-watch/scripts/watch.py connect

# 3. Install.
python skill/tapdrop-watch/scripts/watch.py install

# 4. Add what you want to share. You can also do this on the watch.
python skill/tapdrop-watch/scripts/watch.py set contact name="Asha Rao" phone="+91 98765 43210"
python skill/tapdrop-watch/scripts/watch.py set wifi ssid="Home 5G" pass="your-password"
python skill/tapdrop-watch/scripts/watch.py set upi upi_id="asha@upi" upi_name="Asha Rao" amount=250
python skill/tapdrop-watch/scripts/watch.py set link --id instagram label="Instagram" url="instagram.com/asha"
python skill/tapdrop-watch/scripts/watch.py set location

# 5. Open an item on the watch, tap a phone, then check what the watch saw.
python skill/tapdrop-watch/scripts/watch.py log
```

`READ COMPLETE` in the log means the phone took the whole item. When something fails, the fixes
are in [troubleshooting](skill/tapdrop-watch/references/troubleshooting.md).

**How Pay me reaches a UPI app.** A phone will not hand a UPI link from a tag to GPay, PhonePe or
Paytm, so TapDrop sends a link to a small page that forwards to them. That page is
[`web/pay.html`](web/pay.html), published from this repo at
<https://harshtripathi272.github.io/tapdrop/pay.html>, and the app uses it by default.

The page stores nothing. The request travels after the `#` in the link, which browsers never send
to a server. If you would rather not depend on this project's copy, put the file on any static web
host and point your watch at it:

```sh
python skill/tapdrop-watch/scripts/watch.py pay-page https://your-host.example/pay.html
```

When you are finished, turn **Wireless debugging** off on the watch. TapDrop does not need it.

## Removing it

```sh
python skill/tapdrop-watch/scripts/watch.py uninstall
```

If you turned on one-tap sharing, turn it off on the Quick tap screen first, so Samsung Health gets
its NFC service back.

## Questions people ask

**Does the other person need an app?** No. Their phone reads the watch like an NFC sticker.

**Does my data go anywhere?** The app has no internet permission. Items stay on the watch and leave
only over NFC, to a phone held against it.

**Can someone read my watch without me knowing?** With the default settings, no. A tap either needs
an item open on your screen, or needs you to pick TapDrop on the watch first. If you turn on one-tap
sharing, yes: any phone that touches the watch gets your quick-tap item. That is the trade it makes.

**Does Pay me take money?** No. It opens a payment request in the payer's own UPI app, with your
name and ID showing. They approve or decline there.

**Can it copy my metro card or office pass?** No. Those are not plain data and cannot be copied this
way.

**Does it drain the battery?** NFC only wakes when a phone is touching the watch. The share screen
keeps the display on while it is open, so close it when you are done.

**Is my Wi-Fi password safe in it?** It is stored unencrypted in the app's private storage, and
anyone you tap receives it. Use a guest network.

More in [SECURITY.md](SECURITY.md).

## Building from source

Setup uses a prebuilt app, so most people never need this. To build your own:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File watch-app\build.ps1
```

On macOS or Linux:

```sh
watch-app/build.sh
```

Either needs a JDK 11 or newer. On first run it downloads Android build-tools 35 and platform 35
from Google and checks them against Google's published checksums. There is no Gradle: the script
calls the Android tools directly. The only libraries are the AndroidX ones the tile needs, pinned
in `watch-app/libraries.txt` with their SHA-256 and checked the same way. R8, which ships with
build-tools, drops the parts the app never uses, so the APK is about 220 KB instead of 50 KB. It installs to the connected watch, or pass `-NoInstall` (`--no-install`
for `build.sh`).

A build signs with a debug key it creates beside the source, so an app built on one computer
cannot update one built on another. Uninstall TapDrop from the watch first.

| Path | What it is |
|---|---|
| `watch-app/` | The app. Start with `Kinds.java` and `NdefTagService.java`. |
| `skill/tapdrop-watch/` | The setup procedure assistants follow, its helper script, and the bundled app. |
| `web/pay.html` | The page that hands a UPI request to the payer's app. |
| `tools/` | PowerShell helpers for pairing and reconnecting on Windows. |
| `docs/` | [How it works](docs/HOW-IT-WORKS.md), and the images on this page. |

Working on the code with an assistant? It will read [AGENTS.md](AGENTS.md).

## Contributing

The most useful contribution right now is a report from a watch or phone that isn't in the table
above. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

Apache 2.0. See [LICENSE](LICENSE).

TapDrop is an independent project. It is not affiliated with Samsung, Google or NPCI.
