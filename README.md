# Oguri's Printer

A clean Android app for PPS1 mini thermal printers (the 57 mm,
384-dot pocket printers that ship with the Luck Jingle app). Works
with any unit of this model: no account, no templates, no ads.

## Features

- Finds printers over Bluetooth Low Energy and Classic Bluetooth,
  plus already-bonded devices, and lists them by name and address
  so you can pick the right one when several are nearby; a unit's
  Classic and BLE identities are shown as a single entry
- Connects over Classic SPP or BLE, whichever the way you found the
  printer suggests first, with the other as fallback; remembers the
  last printer you used
- Printer status, battery level, model, firmware and serial number,
  read from the printer; out-of-paper, cover-open, overheating and
  low-battery states are reported as they happen and block printing
- Print text: type or paste, choose the size (16 to 120 px), font
  (Sans, Serif or Monospace), bold, left/centre/right alignment,
  line spacing, or inverted white-on-black, and see an exact
  black-on-white preview of what will be printed
- Print images from the gallery, scaled to the 384-dot paper width,
  with Floyd-Steinberg dithering or a plain threshold
- Density Light / Medium / Dark, 1 to 3 copies, paper feed button

## Requirements

- Android 8 or newer with Bluetooth
- Bluetooth and (for Classic discovery) location permissions,
  requested by the app when needed

## Building

No Gradle: plain Java on the Android framework, built with aapt2,
javac, d8, zipalign and apksigner from the Android SDK build-tools
and a JDK 17. See `build.sh`. The printer protocol core is
Android-free Java with JVM tests in `test/`.

Package: `app.oguri.printer`, minSdk 26, targetSdk 34.
