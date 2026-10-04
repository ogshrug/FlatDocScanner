# FlatDocScanner

An Android document and book scanner. Point your phone at a page and FlatDocScanner outlines it live, takes the photo by itself once you hold still, squares it up, cleans it and adds it to a multi-page document you can share as a PDF. Turn the page, and it captures the next one.

The long-term goal is complete hands-free **book** scanning, including flattening of curved pages, splitting of two-page spreads, and OCR (see [Roadmap](#roadmap)).

## Features

- **Live page outline**: every preview frame is analysed, and the detected page is drawn over the camera view
- **Auto-capture** (on by default, toggle with **Auto / Manual**):
  - fires once the page is large enough, sharp and held still for 0.7 s; the outline fills in as it counts down
  - **page-turn detection**: after a capture it waits for the page to leave the view or the outline to jump, and refuses to capture content that matches the previous page, so you can scan a book just by turning pages
  - auto-captured pages are cropped, filtered and saved in the background while you keep scanning; tap any page later to fine-tune it
  - a status pill tells you what it needs: *Looking for a page*, *Move closer*, *Hold steady while it focuses*, *Hold still*, *Turn the page*
- **Focus check**: variance of the Laplacian inside the page, judged against the sharpest recent frame so plain pages aren't mistaken for blurry ones
- **Camera capture** with CameraX, plus **import from gallery**
- **Automatic page detection** with OpenCV: edge and contour analysis finds the page's four corners, and a threshold-based pass catches low-contrast edges
- **Manual corner adjustment**: drag any corner, with a magnifier that shows exactly where it sits. If the corners cross, the outline turns red and saving is blocked until they're fixed.
- **Perspective correction** squares the page from its four corners
- **Filters**:
  - Original
  - Color (CLAHE contrast boost on lightness only, so colours stay true)
  - Grayscale
  - Black & white (adaptive threshold for a crisp "scanned" look)
- **Multi-page documents**: keep shooting page after page, then reorder by drag and drop, delete, or re-edit any page later. The original photo is kept, so re-cropping never loses quality.
- **Share as PDF** using Android's built-in `PdfDocument` (no AGPL dependencies)
- **Library** of all scans with cover thumbnails, rename and delete
- Everything is on-device: no account and no network access

## How it works

Live capture (about 640×480, every frame):

```
CameraX ImageAnalysis (Y plane = grayscale, rotated upright)
      │
      ▼
FrameAnalyzer ── PageDetector outline + sharpness + 24×32 page signature
      │
      ▼
AutoCapture ── state machine: TOO_SMALL / BLURRY / HOLD_STILL → CAPTURE → TURN_PAGE
      │            (re-arms on page lost or outline jump; rejects duplicate content)
      ▼
ImageCapture (full resolution) → ScanPipeline → saved page
```

Per photo (auto-captured, or after a manual shot in the crop screen):

```
Camera / gallery
      │
      ▼
PageDetector ── grayscale → blur → auto-threshold Canny → dilate → contours
      │          → largest convex 4-point polygon (Otsu-threshold fallback)
      ▼
CornerEditView ── user confirms or adjusts the corners
      │
      ▼
ImageProcessor.warp ── perspective transform to a flat rectangle
      │
      ▼
ImageProcessor.applyFilter ── Original / Color / Grayscale / B&W
      │
      ▼
ScanRepository ── Room (documents, pages) + JPEG files in app storage
      │
      ▼
PdfExporter ── multi-page PDF → Android share sheet
```

Corners are stored as fractions of the image size, so a page can be re-cropped from its original photo at any time.

## Project structure

```
app/src/main/java/com/example/docscanner/
├── DocScannerApp.kt          Application: loads OpenCV, creates the repository
├── scan/
│   ├── Quad.kt               Corner geometry: ordering, convexity, output size (pure Kotlin)
│   ├── PageDetector.kt       OpenCV page detection
│   ├── ImageProcessor.kt     Perspective warp and filters
│   ├── FrameAnalyzer.kt      Live CameraX analyser: outline, sharpness, signature
│   ├── AutoCapture.kt        Auto-capture and page-turn state machine (pure Kotlin)
│   └── ScanPipeline.kt       Photo → saved page without user input
├── data/
│   ├── Entities.kt           DocumentEntity, PageEntity
│   ├── ScanDao.kt            Room queries
│   ├── AppDatabase.kt
│   └── ScanRepository.kt     Database + page-image file management
├── export/PdfExporter.kt     PDF generation
├── ui/
│   ├── MainActivity.kt       Library
│   ├── CameraActivity.kt     Live preview, auto/manual capture, import
│   ├── PageOverlayView.kt    Live page outline over the preview
│   ├── CropActivity.kt       Corners → filter → save
│   ├── CornerEditView.kt     Draggable corner editor with magnifier
│   ├── DocumentActivity.kt   Page grid, reorder, share PDF
│   └── UiUtils.kt            Insets and thumbnail cache
└── util/Bitmaps.kt           EXIF-aware sampled decoding, JPEG writing
```

## Tech stack

| Area | Library |
|---|---|
| Language / UI | Kotlin, Android Views, Material 3 |
| Camera | CameraX 1.6 |
| Computer vision | OpenCV 4.14 (Maven Central `org.opencv:opencv`) |
| Storage | Room 2.8 (KSP) |
| PDF | `android.graphics.pdf.PdfDocument` |
| Build | AGP 9.4, Gradle 9.6, version catalog |

## Building

Requirements:

- Android Studio with a JDK 17+ (the bundled JetBrains Runtime works)
- Android SDK platform 37
- A device or emulator on Android 7.0 (API 24) or later

```bash
./gradlew :app:assembleDebug        # build the debug APK
./gradlew :app:installDebug         # install on a connected device
./gradlew :app:testDebugUnitTest    # unit tests (corner geometry, auto-capture state machine)
./gradlew :app:lintDebug            # Android lint
```

The debug APK bundles OpenCV's native libraries for all four CPU architectures, so it is large (~160 MB). Publish through an Android App Bundle (`bundleRelease`) so that each device downloads only its own architecture.

## Roadmap

- [x] **Phase 1: Flat scanner MVP**: capture, detection, manual corners, perspective correction, filters, multi-page documents, PDF export
- [x] **Phase 2: Real-time capture**: live outline, focus scoring, stability-based auto-capture, page-turn detection for continuous book scanning
- [ ] **Phase 3: Book pages**: two-page spine detection and split, learned curved-page dewarping (UVDoc, MIT-licensed), finger removal
- [ ] **Phase 4: Text**: on-device OCR (ML Kit), searchable PDFs, TXT/Markdown export, optional cloud parsing for tables and formulas
- [ ] **Phase 5: Hardening**: offline queueing, low-end device performance, more export formats

## Tuning

The auto-capture thresholds live in `AutoCapture.Config`: steady time, jitter, minimum coverage, sharpness, the re-arm triggers, the duplicate-page distance and the cooldown. The defaults are first estimates and should be tuned on real devices and books.

## Third-party licenses

- [OpenCV](https://opencv.org/license/): Apache 2.0
- [AndroidX](https://developer.android.com/jetpack/androidx) (CameraX, Room, AppCompat, RecyclerView…): Apache 2.0
- [Material Components for Android](https://github.com/material-components/material-components-android): Apache 2.0
- [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines): Apache 2.0
