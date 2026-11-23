# Contributing

TapDrop has been tested on one watch and one phone. The most useful thing you
can send is what happened on yours.

## Tell us about your device

Open an issue with:

- Watch model and its Wear OS / Android version
- Phone model and Android or iOS version
- Which items worked, and what the phone showed for each
- For anything that failed, the output of
  `python skill/tapdrop-watch/scripts/watch.py log`

Reports from iPhones, Pixel Watches and setups from macOS or Linux are the ones
we have none of.

## Changing the code

- The app is in `watch-app/src/dev/tapdrop/`. It uses plain Java and Android
  framework classes, plus the AndroidX Tiles libraries the tile cannot do
  without. Please keep it that way: no Gradle, and no library beyond those
  pinned in `watch-app/libraries.txt`, is what keeps the build to one script.
- [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md) explains the NFC side.
- Build with `watch-app/build.ps1` on Windows or `watch-app/build.sh` on macOS
  and Linux. A change to one should be made to the other.
- Run `watch-app/tests/test_emulator.py` on a Wear OS emulator if you touch the
  tile or the Quick tap page (how to run it is at the top of the file). NFC has
  no automated tests: say in your pull request what you checked on a real
  watch, and what you could not check.
- If you change how setup works, update
  `skill/tapdrop-watch/SKILL.md` in the same pull request. Coding assistants
  follow that file, so it has to stay true.

## A new kind of item

Most ideas need no code: Learn mode takes anything an NFC tag app can write. A
built-in kind earns its place when it needs something the watch has to compute,
the way Location and Pay me do. Add it in `Kinds.java`.
