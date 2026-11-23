---
name: tapdrop-watch
description: Set up TapDrop on a Galaxy Watch or other Wear OS watch so it shares a contact card, Wi-Fi login, UPI payment request, live location or any link by tapping the watch on a phone over NFC. Covers installing adb, pairing the watch over Wi-Fi, sideloading the app and filling in the user's own details. Use this whenever the user wants to connect a Galaxy Watch or Wear OS watch to their computer, sideload or install an app on a watch, run adb against a watch, turn a watch into an NFC tag, share contact, Wi-Fi or payment details from their wrist, asks for "NameDrop for Galaxy Watch", or mentions TapDrop, even if they don't name the skill.
---

# TapDrop on a Wear OS watch

TapDrop makes a watch answer NFC readers as a tag. The user opens an item on the
watch, holds it to any phone, and that phone gets a contact card, a Wi-Fi login,
a payment request, a location or a link. The receiving phone needs no app.

Your job is to get it onto the user's watch and filled with their details. That
means four things: adb on the computer, a connection to the watch, the app
installed, and the user's items set.

Everything here touches the user's own hardware, so work the way a careful
technician would. Look before you change anything, say what each step does, and
leave the watch as you found it apart from the app.

## Before you start

Ask which watch they have and check it can do this:

- It must run **Wear OS** with **NFC**. Galaxy Watch4 and later qualify. Older
  Tizen Galaxy Watches, Apple Watch, Fitbit and Garmin do not.
- The computer and the watch need to be on the **same Wi-Fi network**, and that
  network must let devices talk to each other. Home Wi-Fi and a hotspot from the
  computer both work. Campus, hotel and guest networks usually block it.

Tested combination: Galaxy Watch8 Classic and Galaxy S24 Ultra, both on Android
17, driven from Windows. Other Wear OS watches should work but have not been
tried, so tell the user that if theirs differs.

## The helper script

`scripts/watch.py` wraps the fiddly adb work: finding adb, telling the watch
apart from a phone, discovery, and quoting values correctly. It needs Python 3
and nothing else. Run `python scripts/watch.py status` first in any session to
see where things stand. Every command is listed at the top of the file.

Plain `adb` works too. The script exists because the same mistakes are easy to
repeat by hand, and the ones that cost time are written up in
`references/troubleshooting.md`. Read that file when something fails.

## Step 1: adb on the computer

Run `python scripts/watch.py status`. If it says adb was not found, the user
needs Android platform-tools.

Download only from Google, and ask the user before downloading anything:

- Windows: `https://dl.google.com/android/repository/platform-tools-latest-windows.zip`
- macOS: `https://dl.google.com/android/repository/platform-tools-latest-darwin.zip`
- Linux: `https://dl.google.com/android/repository/platform-tools-latest-linux.zip`

Unzip so the tools end up in the standard SDK folder, where the script looks:
`%LOCALAPPDATA%\Android\Sdk\platform-tools` on Windows,
`~/Library/Android/sdk/platform-tools` on macOS, `~/Android/Sdk/platform-tools`
on Linux. On Windows, check `adb.exe` is signed by Google LLC before running it
(`Get-AuthenticodeSignature`). macOS users can use `brew install android-platform-tools`.

## Step 2: turn on debugging on the watch

The user does this part. Give them the steps and wait:

1. Settings > About watch > Software information. Tap **Software version** 5
   times. "Developer mode turned on" appears.
2. Settings > Developer options. Turn on **ADB debugging**.
3. In the same menu, turn on **Wireless debugging** and accept the prompt. This
   is a separate switch, and step 2 alone is not enough.

Menu names vary a little between watch makers. Pixel Watch puts the build
number under Settings > System > About.

## Step 3: pair, then connect

Pairing happens once per computer. Start `python scripts/watch.py pair-target`,
then ask the user to open Wireless debugging > **Pair new device** on the watch.
The script prints an `adb pair <ip>:<port>` command when it sees the pairing
screen.

Have the user run that command in their own terminal and type the 6 digit code
from the watch themselves. The code is a credential for their device, and adb's
prompt is built for a person. The pairing screen times out after a minute or so.
If it does, they tap Pair new device again and get a new code.

Then run `python scripts/watch.py connect`. The pairing port and the connect
port are different numbers, which is why connecting is its own step. Success
looks like a `watch` line in the status output.

On later days there is no code. `watch.py connect` is enough, as long as
Wireless debugging is still on.

## Step 4: check the watch can emulate a tag

Before installing, confirm the hardware supports it. Both commands only read:

    adb -s <watch> shell pm list features | grep nfc.hce
    adb -s <watch> shell dumpsys nfc | grep mState

You want `android.hardware.nfc.hce` present and `mState=on`. If NFC is off, the
user turns it on in the watch's Settings > Connections. If the feature is
missing, stop: this watch cannot do it, and no app will change that.

## Step 5: install TapDrop

    python scripts/watch.py install

This sideloads `assets/tapdrop.apk`. Tell the user what it is first: a small
app, signed with a throwaway debug key, that asks for NFC, vibration and
location. Location is used for the location item and to read the connected
Wi-Fi network's name. If they have the TapDrop source, building it themselves
with its `build.ps1` is the better route, since they can read what they install.

## Step 6: fill in the user's details

A fresh install has no items. Ask the user what they want to share, then set
each one. They can also type items on the watch, but a Wi-Fi password on a watch
keyboard is slow, so offering to do it from here is the kind thing.

    python scripts/watch.py set contact name="Asha Rao" phone="+91 98765 43210" email="asha@example.com"
    python scripts/watch.py set wifi ssid="Home 5G" pass="the-password"
    python scripts/watch.py set upi upi_id="asha@bank" upi_name="Asha Rao" amount=250
    python scripts/watch.py set location
    python scripts/watch.py set link --id instagram label="Instagram" url="instagram.com/asha"

`--id` keeps several items of one kind apart. Without it, setting a kind again
replaces the earlier one. Use the user's exact values and do not invent any.

There is a second way in, and it needs neither you nor a keyboard. On the
watch, Add > **Learn from phone** makes the watch an empty, writable tag. The
user opens any NFC tag-writing app on their phone (NFC Tools is a common one),
builds a record, presses Write and holds the phone to the watch. TapDrop saves
whatever arrives as a new item. Mention it for anything the five kinds don't
cover, such as a phone number, plain text or an app link, and for secrets the
user would rather not type into a chat. `watch.py log` shows `LEARNED` when a
write lands.

About the things you will be handed:

- **Wi-Fi password.** It is stored unencrypted in the app's private storage on
  the watch, and anyone the user taps gets it. Say so. Suggest a guest network.
  The script hides it in its output, so don't repeat it back in chat either.
- **UPI ID.** A payment request moves no money. The payer sees the name and ID
  in their own app and approves or declines there. It is still personal data, so
  use it only for this.
- **Wi-Fi name without typing.** On the watch, the Wi-Fi form has a "Use current
  Wi-Fi" button that fills in the connected network. Android gives no app the
  saved password, so that part is always typed once.

## Step 7: how payment requests reach a UPI app

Nothing to do here for most users. Read it so you can explain Pay me.

A tag cannot hand a `upi://` link to GPay, PhonePe or Paytm. Phones dispatch
tags with an NFC action those apps do not listen for, so the payer gets a list
of unrelated apps. TapDrop works around it: the tag carries a web link to a
small static page, and the page forwards to the phone's normal UPI app chooser.

The app uses the project's own copy of that page by default,
`https://harshtripathi272.github.io/tapdrop/pay.html`. It stores nothing: the
request travels after the `#` in the link, which browsers never send to a
server. The payer's phone needs an internet connection to load it.

A user who would rather not depend on that copy can host `assets/pay.html`
anywhere static and point their watch at it:

    python scripts/watch.py pay-page https://their-host.example/pay.html

Hosting publishes a page under the user's account, so only do it if they ask.

## Step 8: test a tap

1. `python scripts/watch.py log --clear`
2. Ask the user to open an item on the watch (tap it in the list) and hold the
   watch against the upper back of an unlocked phone with NFC on.
3. `python scripts/watch.py log`

`READ COMPLETE` means the phone took the whole record. `not answering` means
there was nothing to send: no items yet, or a location item with no fix. Then
ask what the phone showed. You can see the watch's side, not the phone's.

Also have them try it with TapDrop closed, since that is how it gets used in
practice: tap, pick TapDrop tag on the watch, tap again. The log line
`answering with the quick-tap item` confirms that path.

## Finishing up

Tell the user how to use it day to day: open TapDrop, tap an item, hold the
watch to a phone. Tap **Edit** on an item to change it, and the last row of the
list adds a new one. On Pay me, the bezel or crown sets the amount.

Then offer to turn **Wireless debugging** off on the watch. Left on, it lets any
paired computer on the same network control the watch, and it costs battery.
TapDrop keeps working without it.

To remove everything: `python scripts/watch.py uninstall`.

## Limits worth saying out loud

- One-tap sharing needs an item's share screen open. Samsung Health claims the
  same NFC tag ID, and Android only lets the app on screen take priority.
- Users will ask whether someone can just tap the watch while it is idle. Yes,
  in two taps. The first tap opens Samsung's "Select an app to use" screen. The
  wearer picks **TapDrop tag** and the phone taps again. The watch then sends
  the quick-tap item. The user picks it on the **Quick tap** screen, the last
  row of TapDrop's home list, also opened by tapping the TapDrop tile: any of
  their items, or "Nothing". Until
  they choose, it is the first item in the list. Tell them which item that is,
  since it goes out without the app showing it first.
- They will then ask for one tap. On a Samsung watch, the Quick tap screen
  leads to **One-tap sharing**. Turning it on switches off Samsung Health's NFC tag
  service, so TapDrop is the only app left to answer, with the app closed and
  with the display asleep. Explain the two costs before they decide: Samsung
  Health's NFC feature for gym machines stops working, and their quick-tap item
  goes to any phone that touches the watch, with nobody asked. This is theirs
  to turn on, on the watch. Don't switch it for them over adb. It can be turned
  off again on the same screen.
- Learn from phone copies the data on ordinary NFC stickers. It cannot copy
  metro, access or bank cards.
- iPhones act on links only. Location and plain links work. Contact, Wi-Fi and
  payment requests are untested on iPhone.
- The first location fix can take a while indoors.
