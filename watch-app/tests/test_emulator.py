#!/usr/bin/env python3
"""Tests for TapDrop's quick-tap tile, run on a Wear OS emulator. Standard library only.

    watch-app/build.sh --debuggable --no-install       (Windows: build.ps1 -Debuggable -NoInstall)
    python3 watch-app/tests/test_emulator.py [-v] [-k name]

They install watch-app/build/tapdrop.apk on the first Wear OS emulator adb lists,
drive it with adb input, and read the screen with uiautomator. They never run on
a real watch, since each test starts TapDrop from empty. The app's data on the
emulator is saved first and put back afterwards, which is why the build has to
be debuggable: run-as is how the tests reach that data.

Not covered, because the emulator has no NFC: what a phone receives on a tap.
Check that on a watch with `watch.py log`.
"""
import re
import struct
import subprocess
import sys
import time
import unittest
import uuid
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "skill" / "tapdrop-watch" / "scripts"))
from watch import ADB, MAIN, PACKAGE  # noqa: E402  (the setup helper's adb lookup)

APK = ROOT / "watch-app" / "build" / "tapdrop.apk"
TILE = PACKAGE + "/.TapDropTileService"
QUICK_TAP_PAGE = PACKAGE + "/.OneTapActivity"
PREFS = "shared_prefs/tapdrop.xml"

# Icon disc colours, from Kinds.color() and Ui.NEUTRAL.
LINK_COLOR = 0x8E63D9
LOCATION_COLOR = 0xE5566A
NOTHING_COLOR = 0x46464A

serial = None
saved_prefs = None


def adb(*args, input=None, timeout=60):
    """Runs adb against the emulator and returns stdout as bytes; raises on failure."""
    done = subprocess.run([ADB, "-s", serial, *args], input=input, capture_output=True, timeout=timeout)
    if done.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {(done.stdout + done.stderr).decode(errors='replace')}")
    return done.stdout


def sh(command, **kwargs):
    return adb("shell", command, **kwargs).decode(errors="replace").strip()


def find_emulator():
    """The serial of the first Wear OS emulator, or None. Real devices are never picked."""
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
    for line in out.splitlines()[1:]:
        parts = line.split("\t")
        if len(parts) != 2 or parts[1] != "device":
            continue
        props = subprocess.run([ADB, "-s", parts[0], "shell",
                                "getprop ro.kernel.qemu; getprop ro.build.characteristics"],
                               capture_output=True, text=True).stdout.split()
        if props[:1] == ["1"] and any("watch" in p for p in props[1:]):
            return parts[0]
    return None


def setUpModule():
    global serial, saved_prefs
    if ADB is None:
        raise unittest.SkipTest("adb was not found")
    serial = find_emulator()
    if serial is None:
        raise unittest.SkipTest("no Wear OS emulator is running (these tests never use a real watch)")
    if not APK.is_file():
        raise unittest.SkipTest(f"{APK} is missing: run watch-app/build.sh --debuggable --no-install")
    adb("install", "-r", str(APK), timeout=120)
    try:
        sh(f"run-as {PACKAGE} true")
    except RuntimeError:
        raise unittest.SkipTest("the installed build is not debuggable: rebuild with --debuggable")
    try:
        saved_prefs = adb("exec-out", f"run-as {PACKAGE} cat {PREFS}")
    except RuntimeError:
        saved_prefs = None                      # nothing saved yet
    sh("svc power stayon true")
    sh("input keyevent KEYCODE_WAKEUP")


def tearDownModule():
    if serial is None:
        return
    sh(f"am force-stop {PACKAGE}")
    if saved_prefs is None:
        sh(f"run-as {PACKAGE} rm -f {PREFS}")
    else:
        adb("shell", f"run-as {PACKAGE} sh -c 'cat > {PREFS}'", input=saved_prefs)
    sh("svc power stayon false")
    tile("add-tile")                            # leave the tile in the carousel, redrawn from the restored data


# ---- driving the emulator ------------------------------------------------------

def tile(operation):
    sh(f"am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE "
       f"--es operation {operation} --ecn component {TILE}")


def screen():
    """What is on screen: a list of (text, class, (left, top, right, bottom)) for every view."""
    for _ in range(4):
        try:
            sh("uiautomator dump /sdcard/tapdrop-ui.xml")
            root = ET.fromstring(adb("exec-out", "cat /sdcard/tapdrop-ui.xml"))
            break
        except (RuntimeError, ET.ParseError):
            time.sleep(1)                       # the dump fails while the screen is animating
    else:
        raise AssertionError("uiautomator could not read the screen")
    nodes = []
    for node in root.iter("node"):
        box = tuple(int(n) for n in re.findall(r"\d+", node.get("bounds", "")))
        nodes.append((node.get("text", ""), node.get("class", ""), box))
    return nodes


def texts():
    return [text for text, _, _ in screen() if text]


def wait_for(text, timeout=10):
    """Waits until a view shows exactly this text and returns its bounds."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        for shown, _, box in screen():
            if shown == text:
                return box
        time.sleep(0.5)
    raise AssertionError(f"'{text}' did not appear. On screen: {texts()}")


def wait_gone(text, timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if text not in texts():
            return
        time.sleep(0.5)
    raise AssertionError(f"'{text}' is still on screen")


def tap(box):
    sh(f"input tap {(box[0] + box[2]) // 2} {(box[1] + box[3]) // 2}")


def top_activity():
    out = sh("dumpsys activity activities | grep -m1 topResumedActivity")
    match = re.search(r"\S+/\S+", out)
    return match.group(0) if match else ""


def reset():
    """TapDrop with no items and no quick-tap choice, and no TapDrop screen open."""
    sh(f"am force-stop {PACKAGE}")
    sh(f"run-as {PACKAGE} rm -f {PREFS}")
    sh("logcat -b all -c")


def add_item(kind, item_id, **fields):
    """Adds an item the way watch.py set does, through the app, and waits for it to confirm."""
    token = uuid.uuid4().hex[:8]
    extras = "".join(f" --es {key} '{value}'" for key, value in fields.items())
    sh(f"am start --activity-clear-top -n {MAIN} --es token {token} --es type {kind} "
       f"--es id {item_id} --ez quiet true{extras}")
    deadline = time.time() + 10
    while time.time() < deadline:
        if f"item set from adb: {kind} #{token}" in sh("logcat -d -s TapDrop:D"):
            return
        time.sleep(0.5)
    raise AssertionError(f"TapDrop did not confirm the {kind} item")


def show_tile(redraw=True):
    """Goes to the TapDrop tile and returns what it shows.

    With redraw, the tile is removed and added again, so the host asks TapDrop
    for a new layout. Without it, the tile shows whatever TapDrop last pushed,
    which is how a test checks that a change refreshes the tile by itself.
    """
    if redraw:
        tile("remove-tile")
        tile("add-tile")
    for _ in range(4):
        # Home, then Back on the watch face to close system pop-ups (Find Hub asking for
        # a screen lock), then Home again. Back first would close an open TapDrop screen.
        sh("input keyevent KEYCODE_HOME")
        time.sleep(1)
        sh("input keyevent KEYCODE_BACK")
        sh("input keyevent KEYCODE_HOME")
        time.sleep(1.5)
        sh("input swipe 420 240 60 240 200")    # the tile was added first, so it is one swipe away
        time.sleep(2)
        shown = texts()
        if "Tap to change" in shown:
            return shown
    raise AssertionError(f"the TapDrop tile was not found. On screen: {texts()}")


def tile_until(expected, timeout=15):
    """Waits for the tile already on screen to show the expected item."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if expected in texts():
            return
        time.sleep(1)
    raise AssertionError(f"the tile did not change to '{expected}'. It shows: {texts()}")


def icon_color():
    """The colour of the tile icon's disc, sampled beside the glyph, as 0xRRGGBB."""
    images = [box for _, cls, box in screen() if cls == "android.widget.ImageView"]
    if not images:
        raise AssertionError("the tile shows no icon")
    left, top, right, bottom = images[0]
    x = left + (right - left) * 12 // 100       # inside the disc, left of the glyph
    y = (top + bottom) // 2
    return pixel(adb("exec-out", "screencap -p"), x, y)


def pixel(png, x, y):
    """Reads one pixel from screencap's PNG (8-bit RGBA, not interlaced)."""
    pos, idat, width = 8, b"", 0
    while pos < len(png):
        length, kind = struct.unpack(">I4s", png[pos:pos + 8])
        data = png[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width, _, depth, color_type = struct.unpack(">IIBB", data[:10])
            assert (depth, color_type) == (8, 6), "unexpected screenshot format"
        elif kind == b"IDAT":
            idat += data
        pos += 12 + length
    raw, stride, prev = zlib.decompress(idat), width * 4, bytearray(width * 4)
    for row in range(y + 1):
        line = raw[row * (stride + 1):(row + 1) * (stride + 1)]
        kind, cur = line[0], bytearray(line[1:])
        for i in range(stride):                 # undo the PNG row filter
            a = cur[i - 4] if i >= 4 else 0
            b = prev[i]
            c = prev[i - 4] if i >= 4 else 0
            if kind == 1: cur[i] = (cur[i] + a) & 255
            elif kind == 2: cur[i] = (cur[i] + b) & 255
            elif kind == 3: cur[i] = (cur[i] + (a + b) // 2) & 255
            elif kind == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                cur[i] = (cur[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        prev = cur
    r, g, b = prev[x * 4:x * 4 + 3]
    return (r << 16) | (g << 8) | b


def scroll_to(text, tries=10):
    """Scrolls the open TapDrop screen until the text is in the readable middle, and returns its bounds."""
    for _ in range(tries):
        for shown, _, box in screen():
            if shown == text and 60 < (box[1] + box[3]) // 2 < 400:
                return box
        sh("input swipe 240 360 240 200 300")
        time.sleep(1)
    raise AssertionError(f"'{text}' was not found on {top_activity()}")


# ---- tests --------------------------------------------------------------------

class TileTest(unittest.TestCase):
    def setUp(self):
        reset()

    def tearDown(self):
        crashes = sh("logcat -d -b crash")
        self.assertNotIn(PACKAGE, crashes, "TapDrop crashed:\n" + crashes)

    def assertColor(self, expected, actual):
        for shift in (16, 8, 0):
            self.assertLessEqual(abs((expected >> shift & 255) - (actual >> shift & 255)), 8,
                                 f"icon colour {actual:06X}, expected {expected:06X}")

    def test_app_starts(self):
        sh(f"am start -n {MAIN}")
        wait_for("TapDrop")
        self.assertEqual(top_activity(), MAIN)

    def test_tile_with_no_items_shows_nothing(self):
        shown = show_tile()
        self.assertIn("Nothing", shown)
        self.assertIn("Shares nothing", shown)
        self.assertColor(NOTHING_COLOR, icon_color())

    def test_tile_defaults_to_first_item(self):
        add_item("link", "alpha", label="Alpha", url="example.com/a")
        add_item("location", "here")
        shown = show_tile()
        self.assertIn("Alpha", shown)
        self.assertIn("NFC is off", shown)      # the emulator has no NFC, so no "Sharing now" and no rings
        self.assertColor(LINK_COLOR, icon_color())

    def test_tile_updates_when_an_item_is_added(self):
        self.assertIn("Nothing", show_tile())
        add_item("location", "here")
        show_tile(redraw=False)
        tile_until("My location")
        self.assertColor(LOCATION_COLOR, icon_color())

    def test_tapping_tile_opens_quick_tap_page(self):
        add_item("link", "alpha", label="Alpha", url="example.com/a")
        show_tile()
        tap(wait_for("Tap to change"))
        time.sleep(2)
        self.assertEqual(top_activity(), QUICK_TAP_PAGE)
        self.assertIn("Quick tap", texts())

    def test_choice_on_quick_tap_page_updates_tile(self):
        add_item("link", "alpha", label="Alpha", url="example.com/a")
        add_item("location", "here")
        show_tile()
        tap(wait_for("Tap to change"))
        tap(scroll_to("My location"))
        wait_gone("Live position")              # its subtitle becomes "Shared when closed"
        show_tile(redraw=False)
        tile_until("My location")
        self.assertColor(LOCATION_COLOR, icon_color())

    def test_choosing_nothing_updates_tile(self):
        add_item("link", "alpha", label="Alpha", url="example.com/a")
        show_tile()
        tap(wait_for("Tap to change"))
        tap(scroll_to("Nothing"))
        wait_for("Silent when closed")
        show_tile(redraw=False)
        tile_until("Nothing")
        self.assertIn("Shares nothing", texts())
        self.assertColor(NOTHING_COLOR, icon_color())

    def test_choice_survives_screen_off_and_restart(self):
        add_item("link", "alpha", label="Alpha", url="example.com/a")
        add_item("location", "here")
        sh(f"am start -n {QUICK_TAP_PAGE}")
        tap(scroll_to("My location"))
        wait_gone("Live position")              # its subtitle becomes "Shared when closed"

        sh("input keyevent KEYCODE_SLEEP")
        time.sleep(2)
        sh("input keyevent KEYCODE_WAKEUP")
        sh(f"am force-stop {PACKAGE}")
        self.assertIn("My location", show_tile())

        sh(f"am start -n {MAIN}")
        wait_for("TapDrop")
        scroll_to("Shares My location")

    def test_back_from_tile_returns_to_tile(self):
        # An item's share screen left open in the app's own task must not come back.
        sh(f"am start -n {MAIN} --es type link --es id alpha --es label Alpha --es url example.com/a")
        wait_for("Edit")                        # the share screen draws its title, so wait on its button
        self.assertEqual(top_activity(), PACKAGE + "/.ShareActivity")
        show_tile()
        tap(wait_for("Tap to change"))
        time.sleep(2)
        self.assertEqual(top_activity(), QUICK_TAP_PAGE)
        sh("input keyevent KEYCODE_BACK")
        time.sleep(2)
        self.assertNotIn(PACKAGE, top_activity())
        self.assertIn("Tap to change", texts())

    def test_back_from_quick_tap_page_in_app_returns_home(self):
        sh(f"am start -n {MAIN}")
        wait_for("TapDrop")
        tap(scroll_to("Quick tap"))
        time.sleep(2)
        self.assertEqual(top_activity(), QUICK_TAP_PAGE)
        sh("input keyevent KEYCODE_BACK")
        time.sleep(2)
        self.assertEqual(top_activity(), MAIN)

    def test_one_tap_sharing_section_opens(self):
        # The section looks up a CardEmulation method by name, which shrinking must not break.
        sh(f"am start -n {QUICK_TAP_PAGE}")
        scroll_to("One-tap sharing")


if __name__ == "__main__":
    unittest.main()
