<p align="center">
  <img src="https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <a href="https://github.com/mohammad-sharif99/CyberPrint/actions/workflows/build.yml"><img src="https://github.com/mohammad-sharif99/CyberPrint/actions/workflows/build.yml/badge.svg" alt="Build"></a>
  <a href="https://github.com/mohammad-sharif99/CyberPrint/releases/latest"><img src="https://img.shields.io/github/v/release/mohammad-sharif99/CyberPrint?label=release&color=0F766E" alt="Latest release"></a>
  <a href="https://github.com/mohammad-sharif99/CyberPrint/releases/latest/download/CyberPrint.apk"><img src="https://img.shields.io/github/downloads/mohammad-sharif99/CyberPrint/total?label=downloads&color=F59E0B" alt="Downloads"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green.svg" alt="MIT License"></a>
</p>

<h1 align="center">CyberPrint</h1>

<p align="center">
  <b>Bluetooth ESC/POS print service for Android.</b><br>
  Print from Chrome — or any app — to 58 mm / 80 mm thermal receipt printers, with pixel-perfect Arabic and RTL text.
</p>

<p align="center">
  <a href="https://github.com/mohammad-sharif99/CyberPrint/releases/latest/download/CyberPrint.apk"><b>⬇ Download APK</b></a>
  ·
  <a href="#quick-start">Quick start</a>
  ·
  <a href="#troubleshooting">Troubleshooting</a>
  ·
  <a href="#building-from-source">Build</a>
</p>

---

## Why CyberPrint?

Most Bluetooth receipt-printer apps send text to the printer and rely on its firmware to shape it. Cheap thermal printers have no Arabic font — so you get disconnected, reversed or missing glyphs. Many of those apps are also paid, closed source, and can't be used from the system print dialog.

CyberPrint takes a different approach:

- **It is a real Android Print Service.** Your Bluetooth printer appears in the standard print dialog of Chrome, Gmail, Drive, your POS web app — anything that can print.
- **Everything is rendered on the phone.** Pages, text and images are rasterised by Android's own text engine and sent to the printer as a bitmap. The printer's firmware, fonts and code pages become irrelevant — Arabic, Hebrew, Urdu, CJK, emoji: all correct.
- **Free and open source (MIT).** No ads, no licence keys, no telemetry.

## Features

| | |
|---|---|
| 🖨 **System print service** | Printer shows up in the Android print dialog; choose 58 mm or 80 mm paper (100–500 mm lengths) right there. |
| 🔤 **Correct RTL text** | Arabic shaping, bidi and diacritics rendered by Android, not the printer. |
| 📤 **Share-sheet printing** | Send plain text, images or PDFs (single or multiple) from any app. |
| 🎚 **Print tuning** | Darkness threshold, Floyd–Steinberg dithering for photos, feed lines, auto cut, cash-drawer kick. |
| 🧩 **Two raster modes** | `GS v 0` (modern) and `ESC *` 24-dot bit-image (legacy) for maximum firmware compatibility. |
| 🐢 **Slow mode** | Throttled transfer for printers with tiny receive buffers. |
| 🩺 **Diagnostics** | One-tap raw ASCII test to tell "not connected" from "wrong command mode". |
| ✂️ **Paper saver** | Trailing whitespace is trimmed from the last page. |
| 🔗 **Robust connection** | Secure, insecure and reflective RFCOMM fallbacks for picky clone printers. |

## Quick start

1. **Pair** the printer in Android Bluetooth settings (PIN is usually `0000` or `1234`).
2. Open CyberPrint → **Choose printer** → pick it from the paired list.
3. Tap **Enable print service** and switch CyberPrint on.
4. In Chrome: **⋮ → Share → Print** → select your printer and paper size.

Tap **Test print** at any time to print a sample receipt and dial in darkness.

## Troubleshooting

**"Printed successfully" but nothing comes out**

The Bluetooth link worked and the data was accepted; the printer ignored it.

1. Tap **Raw ASCII test**.
2. *It prints* → the firmware rejects `GS v 0`. Set **Image command → Compatible (ESC \*)** and retry.
3. *Nothing prints* → the printer is most likely in **CPCL / TSPL** (label) mode rather than ESC/POS. Print a self-test page (hold **Feed** while powering on) and check the listed command mode; switch it with the vendor's utility or the button sequence in its manual.

**Connection errors**

- Only one device can be connected to the printer at a time — disconnect other phones/tablets.
- Android 12+: make sure the **Nearby devices** permission is granted to CyberPrint.
- Turn the printer off and on, then *Forget* and re-pair it.

**Output too light / too dark** → adjust **Darkness**. Enable **Dithering** for photos and logos; leave it off for text.

## Building from source

Requirements: JDK 17, Android SDK with platform 35 and build-tools 35.

```bash
git clone https://github.com/mohammad-sharif99/CyberPrint.git
cd CyberPrint
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

Every push to `main` is built by GitHub Actions; pushing a `v*` tag publishes a release with the APK attached.

## Architecture

```
app/src/main/java/com/cyberlap/btprint
├── print/BtPrintService.kt   Android PrintService: printer discovery, capabilities, job handling
├── print/PrintPipeline.kt    PDF → bitmap → 1-bit → ESC/POS → Bluetooth
├── escpos/EscPos.kt          Command builder: init, GS v 0, ESC *, feed, cut, drawer
├── escpos/ImageUtils.kt      Scaling, greyscale, threshold / Floyd–Steinberg
├── bt/BtPrinter.kt           RFCOMM transport with connection fallbacks
├── text/TextRenderer.kt      Text → bitmap with RTL-aware layout
├── ShareActivity.kt          ACTION_SEND / SEND_MULTIPLE / VIEW handler
└── MainActivity.kt           Settings UI
```

Paper widths map to 203 dpi print heads: **58 mm → 384 dots**, **80 mm → 576 dots**. Media sizes advertised to the OS use the *printable* width so the print framework lays pages out at exactly that dot count.

## Tested printers

Reports welcome — open an issue with your model and the settings that worked.

| Model | Status |
|---|---|
| Rongta RPP320 | In progress |

## Contributing

Issues and pull requests are welcome. Please keep changes focused and include the printer model you tested on.

## License

[MIT](LICENSE) © 2026 Mohammad Alshref
