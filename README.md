# FlatDocScanner

An Android document and book scanner that works entirely on the phone. Point it at a page and it outlines the page live, takes the photo once you hold still, squares it up, cleans it and adds it to a multi-page folder you can share as a PDF. Turn the page and it captures the next one.

**Book mode** photographs an open book, finds the spine, splits the spread into two pages and flattens each curved page with an on-device neural network.

No account, no upload, no ads. Scans never leave the phone. The Play Store build has no internet permission at all; the build from GitHub uses the network for one thing, checking for and downloading its own updates.

## Install

Download the latest APK from [Releases](https://github.com/ogshrug/FlatDocScanner/releases): `arm64` for almost every phone, `universal` if that one won't install. Needs Android 7.0 or later.

There are two builds of every release, identical except for what runs the two neural networks: the standard one uses ONNX Runtime; the `ferrite` one uses [Ferrite](https://github.com/ogshrug/Ferrite-DocScanner), a small C inference runtime adapted for this app, and is about a third smaller. The Ferrite build installs beside the standard one as *FlatDoc Ferrite*, and **Settings → Model engine** shows which one you are running.

From then on the app updates itself: once a day it asks GitHub whether a newer release exists and offers it, or use **Settings → Check for updates**. A download is installed only if its checksum matches GitHub's and it is signed with the same key as the app you have; Android then asks you to confirm. The daily check can be switched off in Settings.

## Features

### Scanning

- **Live page outline** over the camera view, which glides between detections and fades as a page is found and lost.
- **Auto-capture**: fires once the page is large enough, sharp and held still for 0.7 s. A status pill says what it needs (*Move closer*, *Hold still*, *Turn the page*…).
- **Page-turn detection**: after a capture it waits for the page to change and refuses to capture the same content twice, so you can scan a book just by turning pages.
- **Camera controls**: Page / Book, Flash, Timer (a photo every 3 or 5 seconds) and Auto, each explained by a short hint when switched on. Tap to focus, pinch to zoom; the screen stays on.
- **Import** from the gallery, or share images to the app from anywhere.

### Page clean-up

- **Page detection** with a learned corner model ([DocAligner](https://github.com/DocsaidLab/DocAligner) LCNet-100). One hidden or folded corner is completed from the other three, and a classical edge-based detector is the fallback for captured photos. On the 130 real photos of the DocUNet benchmark it finds the right outline about 90% of the time.
- **Manual corners**: drag any corner, with a magnifier. Crossed corners turn the outline red and block saving.
- **Perspective correction** from the four corners.
- **Curved-page flattening** with [UVDoc](https://github.com/tanguymagne/UVDoc). Pages without a clean four-corner outline are treated as curved automatically; the crop screen has a Flat / Curved switch.
- **Book mode**: spine detection, a separate flatten for each half, and gutter shadow correction.
- **Finger removal**: skin-coloured blobs touching the page edge are inpainted, judged against that page's own paper colour.
- **Filters**: Original, Color (contrast boost on lightness only), Grayscale, Black & white (adaptive threshold).

### Library

- **Folders** with covers and page counts; search, sort, select several to make one PDF or ZIP.
- **Pages**: grid or continuous reader view, drag to reorder, rotate, re-crop from the kept original photo, move between folders, set as cover, save to gallery.
- **Full-screen viewer**: swipe between pages, pinch or double-tap to zoom.
- **Trash**: deleted folders and pages can be restored for 30 days. Deleting happens at once with an Undo bar; pages return to their old place.
- **Themes**: eight colour themes, each light or dark, or following the phone.
- **Settings**: save scans to gallery, vibrate, two-page order (for right-to-left books), timer length, start in the camera, text language, storage used.

### Text and export

- **Text recognition, opt-in**: per page, selection or folder, on-device with ML Kit (Latin and Devanagari). Nothing is recognised unless you ask.
- **Search** finds words in recognised text and marks them on the page images.
- **Text view** with copy, share and read aloud.
- **Exports**: PDF (page size, quality, colour and order options; searchable text layer on recognised pages), images, ZIP, TXT, Markdown and Word (.docx).

## How it works

Live preview, every frame at about 640×480:

```
CameraX ImageAnalysis (RGBA, turned upright)
      │
      ▼
FrameAnalyzer ── corner model outline + sharpness + 24×32 page signature
      │
      ▼
AutoCapture ── state machine: TOO_SMALL / BLURRY / HOLD_STILL → CAPTURE → TURN_PAGE
      │            (re-arms on page lost or outline jump; rejects duplicate content)
      ▼
ImageCapture (full resolution) → ScanPipeline → saved page
```

A flat page:

```
Photo → PageDetector (corner model, then classical fallback) → corners confirmed or adjusted
      → perspective warp → finger removal → filter → rotation
      → ScanRepository (Room + JPEG files in app storage)
```

A book spread:

```
Photo → PageDetector (curved outlines allowed) → SpineDetector
      → for each side: other page painted out → Dewarper (UVDoc) → gutter correction
      → finger removal → filter
```

Corners are stored as fractions of the image size and the original photo is kept, so a page can be re-cropped at any time without losing quality.

## Building

Requirements: Android Studio with JDK 17 or later (the bundled runtime works), Android SDK platform 37, and a device or emulator on Android 7.0 (API 24) or later.

```bash
git submodule update --init                          # Ferrite sources (third_party/ferrite)
./gradlew :app:assembleGithubOnnxDebug               # debug APK
./gradlew :app:installGithubOnnxDebug                # install on a connected device
./gradlew :app:testGithubOnnxDebugUnitTest           # unit tests: geometry, auto-capture, spine fit, gutter gains, text export, updater
./gradlew :app:lintGithubOnnxDebug                   # Android lint
./gradlew :app:connectedGithubOnnxDebugAndroidTest   # on a device: detection, both models, OCR, PDF
```

- Variants combine two choices. The **engine**: `onnx` runs the models on ONNX Runtime; `ferrite` runs them on Ferrite, built from the `third_party/ferrite` submodule with the NDK (`app/src/ferrite/cpp`), with its own package name (`.ferrite`) so both install side by side. Swap `Onnx` for `Ferrite` in any task name. The model code talks to either through `ModelRunner` / `ModelEngine` (one per engine in `app/src/onnx` and `app/src/ferrite`), and each engine has its own copy of the two model files.
- The **channel**: `github` (above) includes the self-updater, and with it the internet and install-packages permissions. `play` has neither: Play delivers updates and forbids apps that update themselves. Swap `Github` for `Play` in any task name to build that one. The updater lives in `app/src/github`, a do-nothing stand-in in `app/src/play`.

- Add `-PtargetAbi=arm64-v8a` (or `x86_64` for an emulator) to package one CPU type. Without it the APK carries native libraries for all four and is about 350 MB.
- Debug builds use the package `io.github.ogshrug.flatdocscanner.debug`, so they install beside the release app.
- Every push runs the unit tests, lint and debug builds of the variants on GitHub Actions ([ci.yml](.github/workflows/ci.yml)).
- Release builds are shrunk with R8 and signed with a key kept outside git; see [RELEASING.md](RELEASING.md).

To regenerate the flattening model:

```bash
git clone https://github.com/tanguymagne/UVDoc
python tools/export_uvdoc_onnx.py --uvdoc UVDoc --out uvdoc_full.onnx   # needs torch, onnx, onnxruntime
python tools/halve_model.py uvdoc_full.onnx app/src/main/assets/uvdoc.onnx
```

## Tech stack

| Area | Library |
|---|---|
| Language / UI | Kotlin, Android Views, Material 3 |
| Camera | CameraX 1.6 |
| Computer vision | OpenCV 4.14 (Maven Central `org.opencv:opencv`) |
| Page detection | DocAligner LCNet-100 via ONNX Runtime, OpenCV fallback |
| Curved pages | UVDoc via ONNX Runtime 1.30 |
| Alternative engine | [Ferrite](https://github.com/ogshrug/Ferrite-DocScanner) (C11, NEON on arm64, AVX2 on x86-64), the `ferrite` build |
| Text recognition | ML Kit Text Recognition v2 (Latin + Devanagari, bundled, offline) |
| Storage | Room 2.8 (KSP) |
| PDF | `android.graphics.pdf.PdfDocument` |
| Build | AGP 9.4, Gradle 9.6, version catalog |

<details>
<summary><b>Project structure</b></summary>

```
app/src/main/java/io/github/ogshrug/flatdocscanner/
├── DocScannerApp.kt          Application: loads OpenCV, creates the repository
├── AppTheme.kt               Colour themes and light/dark, applied to every screen
├── scan/ModelRunner.kt       What both engines implement (ModelEngine per engine in app/src/onnx, app/src/ferrite)
├── update/UpdateFeature.kt   Self-updater (in app/src/github; a stub in app/src/play)
├── scan/
│   ├── Quad.kt               Corner geometry: ordering, convexity, output size (pure Kotlin)
│   ├── PageDetector.kt       Page detection: corner model, then classical fallback
│   ├── ImageProcessor.kt     Perspective warp and filters
│   ├── FrameAnalyzer.kt      Live CameraX analyser: outline, sharpness, signature
│   ├── AutoCapture.kt        Auto-capture and page-turn state machine (pure Kotlin)
│   ├── ScanPipeline.kt       Photo → finished page (geometry, then finishing), auto-save
│   ├── PageSpec.kt           Page settings, spine line fit, gutter gains (pure Kotlin)
│   ├── CornerModel.kt        DocAligner page-corner model via ONNX Runtime
│   ├── ModelAssets.kt        Memory-maps the model files straight from the APK
│   ├── Dewarper.kt           UVDoc curved-page flattening via ONNX Runtime
│   ├── SpineDetector.kt      Book gutter detection
│   └── FingerRemover.kt      Finger detection and inpainting
├── data/
│   ├── Entities.kt           DocumentEntity, PageEntity
│   ├── ScanDao.kt            Room queries
│   ├── AppDatabase.kt
│   └── ScanRepository.kt     Database + page-image file management
├── export/PdfExporter.kt     PDF generation
├── export/ImageExporter.kt   Page images for sharing
├── export/DocxExporter.kt    Word (.docx) and ZIP export, no library
├── ocr/PageText.kt           Recognised text model, TXT and Markdown export (pure Kotlin)
├── ocr/TextRecognizer.kt     ML Kit recognition, JSON storage, language setting
├── ui/
│   ├── MainActivity.kt       Library
│   ├── CameraActivity.kt     Live preview, auto/manual capture, import
│   ├── PageOverlayView.kt    Live page outline over the preview
│   ├── CropActivity.kt       Corners → filter → save
│   ├── CornerEditView.kt     Draggable corner editor with magnifier
│   ├── DocumentActivity.kt   Page grid, reorder, select, share
│   ├── PageViewerActivity.kt Full-screen swipe-and-zoom viewer, rotate, search marks
│   ├── TextReaderActivity.kt Full-screen recognised text with read aloud
│   ├── TrashActivity.kt      Restore or delete for good
│   ├── SettingsActivity.kt   Settings
│   ├── PdfSheet.kt           Create PDF options
│   ├── ActionSheet.kt        Grid of actions in a bottom sheet (More, Share)
│   ├── ImportActivity.kt     Scans images shared to the app
│   ├── HighlightImageView.kt Page image with search marks and fixed proportions
│   ├── ZoomImageView.kt      Pinch/double-tap zoom that hands swipes to the pager at the edge
│   ├── Sharing.kt            Share-sheet intents for images, PDF and text
│   ├── TextUi.kt             Opt-in OCR: page text sheet, recognise many, language
│   └── UiUtils.kt            Insets and thumbnail cache
└── util/Bitmaps.kt           EXIF-aware sampled decoding, JPEG writing

app/src/main/assets/uvdoc.onnx   UVDoc model (16 MB), exported by tools/export_uvdoc_onnx.py, then halved
app/src/main/assets/docaligner_lcnet100.onnx   DocAligner LCNet-100 corner model (2.4 MB), DocsaidLab's weights, halved
tools/export_uvdoc_onnx.py       Re-exports the model and checks it against PyTorch
tools/halve_model.py             Stores a model's weights as 16-bit floats (half the file, same computation)
tools/make_themes.py             Generates the colour themes from Astryx's tokens
```

</details>

## Developer notes

**Performance**

- Camera frames are read in place from native memory, and the corner model reuses its buffers, so the analyser allocates almost nothing per frame.
- Both models are memory-mapped from the APK. The corner model loads in the background at app start; the flattening model loads ahead of time when Book mode is on.
- Model weights are stored as 16-bit floats and widened at load, which halves both files (31.6 → 15.8 MB and 4.8 → 2.4 MB). The largest change in either model's output on test images was 0.001.
- Convolutions run on ONNX Runtime's XNNPACK provider, with the default CPU path as a fallback.
- Finishing steps that don't apply to a page (gutter, fingers, filter, rotation) are skipped.
- Grids update by diff; thumbnail loads for views that scroll away are cancelled, and at most four run at once.
- `SpeedDeviceTest` times detection, finishing and flattening on a connected device (logcat tag `SPEED`); debug builds also log per-frame camera timings under `FrameAnalyzer`.

**Tuning.** The auto-capture thresholds live in `AutoCapture.Config`: steady time, jitter, minimum coverage, sharpness, the re-arm triggers, the duplicate-page distance and the cooldown. The defaults are first estimates and should be tuned on real devices and books. The same goes for the spine valley depth in `SpineDetector`, the skin margins in `FingerRemover` and the gutter zone in `Gutter`.

**Design.** Eight colour themes, each in light and dark, chosen in Settings (Theme and Appearance). Teal is the app's own and the default, in dark. Neutral, Stone, Butter, Chocolate, Matcha, Gothic and Y2K take their colours and corner radii from [Astryx](https://github.com/facebook/astryx), Meta's open-source design system: its background, text, accent and border roles map onto Material's colour roles, and its inner / element / container / page radii drive Material's shape scale. Astryx's typefaces are not bundled; themes with a serif heading face use the system serif for titles. The camera and the full-screen viewer always use a theme's dark colours.

Themes are generated: `python tools/make_themes.py` writes `theme_colors.xml` (with a night copy) and `theme_overlays.xml`, and `AppTheme.kt` lays the chosen overlay over `Theme.DocScanner` on every screen. Layouts read colours through theme attributes (`?attr/colorPrimary`, `?attr/colorOnSurface`, `?attr/colorSurfaceContainer…`), never fixed colours.

## Privacy

No account, analytics, ads or servers. Scans live in app-private storage and are never uploaded; Android cloud backup includes settings only.

- **Play Store build**: no internet permission, so nothing can leave the device.
- **GitHub build**: has the internet permission for its updater, which makes two kinds of request, both to GitHub: "what is the latest release?" and the download of that release. Neither carries anything about you or your scans.
- In both builds, the parts of ML Kit and ONNX Runtime that could report usage statistics are removed in the manifest.

Full policy: [PRIVACY.md](PRIVACY.md).

## Roadmap

- [x] Flat scanner: capture, detection, manual corners, perspective correction, filters, PDF export
- [x] Real-time capture: live outline, focus scoring, auto-capture, page-turn detection
- [x] Book pages: spine split, curved-page flattening, gutter correction, finger removal
- [x] On-device text: opt-in OCR, searchable PDFs, TXT / Markdown / Word export
- [ ] Structured text: tables and formulas, more scripts
- [ ] Hardening: low-end device performance, more export formats

## License

FlatDocScanner is licensed under the [Apache License 2.0](LICENSE).

It bundles the UVDoc model (MIT, © 2023 Tanguy Magne), the DocAligner model (Apache 2.0, DocsaidLab), OpenCV (Apache 2.0), ONNX Runtime (MIT), Ferrite (MIT, in the Ferrite build) and other libraries under their own licences; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
