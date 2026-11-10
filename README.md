# FlatDocScanner

An Android document and book scanner. Point your phone at a page and FlatDocScanner outlines it live, takes the photo by itself once you hold still, squares it up, cleans it and adds it to a multi-page document you can share as a PDF. Turn the page, and it captures the next one.

**Book mode** photographs an open book, finds the spine, splits the spread into two pages and flattens each curved page with an on-device neural network. It also evens out the gutter shadow and removes fingers holding the pages down. Everything runs on the phone: no account, no upload. The app does not even have internet permission.

## Features

- **Live page outline**: every preview frame is analysed, and the detected page is drawn over the camera view. The drawn outline glides between detections instead of jittering, and fades in and out as a page is found and lost
- **Auto-capture** (on by default, toggle with **Auto / Manual**):
  - fires once the page is large enough, sharp and held still for 0.7 s; the outline fills in as it counts down
  - **page-turn detection**: after a capture it waits for the page to leave the view or the outline to jump, and refuses to capture content that matches the previous page, so you can scan a book just by turning pages
  - auto-captured pages are cropped, filtered and saved in the background while you keep scanning; tap any page later to fine-tune it
  - a status pill tells you what it needs: *Looking for a page*, *Move closer*, *Hold steady while it focuses*, *Hold still*, *Turn the page*
- **Book mode** (toggle **Page / Book**): every photo of an open book becomes two pages
  - **spine detection**: finds the gutter shadow row by row and fits a straight line through it, robust to pictures and text; falls back to the middle of the spread
  - each half is flattened separately, because the model is trained on single pages
  - **gutter shadow correction** lifts the dark band next to the spine to the paper level of the rest of the page and trims the fold line
- **Curved-page flattening** with [UVDoc](https://github.com/tanguymagne/UVDoc) (MIT), running on-device with ONNX Runtime. The model predicts a 31×45 grid of where the flat page lies in the photo; the full-resolution photo is remapped through it, and the page's real proportions are estimated from the grid. Single pages without a clean 4-corner outline are treated as curved automatically, and the crop screen has a **Flat / Curved** switch.
- **Finger removal**: finds skin-coloured blobs touching the page edge, judged against that page's own paper colour so cream or yellowed paper isn't mistaken for skin, and inpaints them. On by default; switch per page.
- **Focus check**: variance of the Laplacian inside the page, judged against the sharpest recent frame so plain pages aren't mistaken for blurry ones
- **Camera toolbar**: labelled Page / Book, **Flash** (torch), **Timer** (a photo every 3 or 5 seconds, for turning pages at your own pace; pauses when the app is in the background) and Auto. Switching one on explains it in a short hint above the controls. The detected page is tinted, and an AUTO badge fills up while you hold still.
- **Tap to focus, pinch to zoom** on the preview. The screen stays on while you scan.
- **Camera capture** with CameraX, plus **import from gallery**
- **Share photos to the app**: share one or more images from the gallery or any app (or "Open with"), and each is scanned into a new document
- **Automatic page detection** with a learned corner model ([DocAligner](https://github.com/DocsaidLab/DocAligner) LCNet, Apache-2.0, 4.8 MB) running on-device with ONNX Runtime. It predicts a heatmap per corner, and one hidden or folded corner is completed from the other three. For a captured photo, a classical fallback looks for the page in grey edges, colour edges and the bright paper region, fits a straight line to each side, and keeps the outline that best follows real edges. On the 130 real photos of the DocUNet benchmark, this finds the right outline about 90% of the time; the previous edge-only detector managed 17%.
- **Manual corner adjustment**: drag any corner, with a magnifier that shows exactly where it sits. If the corners cross, the outline turns red and saving is blocked until they're fixed.
- **Perspective correction** squares the page from its four corners
- **Filters**:
  - Original
  - Color (CLAHE contrast boost on lightness only, so colours stay true)
  - Grayscale
  - Black & white (adaptive threshold for a crisp "scanned" look)
- **Multi-page documents**: keep shooting page after page, then reorder by drag and drop, delete, or re-edit any page later. The original photo is kept, so re-cropping never loses quality.
- **Share as PDF** using Android's built-in `PdfDocument` (no AGPL dependencies)
- **Library of folders**: a grid of covers with page counts; new empty folders; search, sort (modified / created / name); select folders to make one PDF or ZIP, or move them to the Trash
- **Folder screen**: bottom bar with Import, Text, Share, Create PDF and More (move pages to another folder, save to gallery, set as cover, crop, rotate left/right, delete); actions apply to the selected pages or the whole folder
- **Create PDF**: file name with quick templates (folder name, date), page size (fit, A4, Letter), quality, colour (as scanned, grayscale, black & white) and page order
- **Trash**: deleted folders and pages can be restored for 30 days, then they are deleted for good. Deleting happens at once with an **Undo** bar (pages return to their old place) instead of a confirmation dialog
- **Settings**: save scans to gallery, vibrate, two-page order (left or right page first, for right-to-left books), interval timer length, start in the camera, text recognition language, storage used
- **Grid or reader view**: pages as a grid, or as a continuous full-width scroll like an e-book
- **Search**: finds words in the recognised text and marks them on the page images, in the document and in the full-screen viewer
- **Rotate** a page a quarter turn (kept when the page is re-cropped)
- **Full-screen viewer**: tap a page to open it; swipe for the next or previous page, pinch or double-tap to zoom. While zoomed, a swipe pans the page and only turns it once you reach the edge. Edit, share and delete from the top bar.
- **Share as images**: the whole document, one page, or a selection. Hold a page (or use **Select pages**) to start selecting; hold and drag to reorder instead.
- **Text recognition (OCR), opt-in**: nothing is recognised unless you ask, for one page (**Recognize text** in the viewer, with Copy, Share and Remove), a selection, or the whole document. Runs on-device with ML Kit Text Recognition v2; **Text language** switches between Latin and Devanagari (Hindi, Marathi, Nepali… plus Latin). Pages with text show "· Text" in the grid; re-cropping a page clears its text.
- **Image | Text switch** in the page viewer, like a reader app
- **Text view**: the recognised text full screen, a page per swipe, with copy, share and **read aloud** (Android text-to-speech; continues onto the next page)
- **Text exports**: share the document as **TXT** or **Markdown** (paragraphs re-flowed, words hyphenated across lines re-joined). **Word (.docx)** with a page break between pages. **PDF** adds an invisible text layer on recognised pages, so it is searchable and its text can be copied. Page images can also be shared as a **ZIP**.
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

Book spread (Book mode):

```
Photo → PageDetector (loose: curved outlines allowed) → SpineDetector
      → for each side: ImageProcessor.halfPage (other page painted out)
      → Dewarper (UVDoc, ONNX Runtime) → correctGutter → FingerRemover → filter
```

Flat page, per photo (auto-captured, or after a manual shot in the crop screen):

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
app/src/main/java/io/github/ogshrug/flatdocscanner/
├── DocScannerApp.kt          Application: loads OpenCV, creates the repository
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

app/src/main/assets/uvdoc.onnx   UVDoc model (31 MB), exported by tools/export_uvdoc_onnx.py
app/src/main/assets/docaligner_lcnet100.onnx   DocAligner LCNet-100 corner model (4.8 MB), as published by DocsaidLab
tools/export_uvdoc_onnx.py       Re-exports the model and checks it against PyTorch
```

## Tech stack

| Area | Library |
|---|---|
| Language / UI | Kotlin, Android Views, Material 3 |
| Camera | CameraX 1.6 |
| Computer vision | OpenCV 4.14 (Maven Central `org.opencv:opencv`) |
| Page detection | DocAligner LCNet-100 via ONNX Runtime, OpenCV fallback |
| Curved pages | UVDoc via ONNX Runtime 1.30 |
| Text recognition | ML Kit Text Recognition v2 (Latin + Devanagari, bundled, offline) |
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
./gradlew :app:testDebugUnitTest    # unit tests (geometry, auto-capture, spine fit, gutter gains, text export)
./gradlew :app:lintDebug            # Android lint
./gradlew :app:connectedDebugAndroidTest   # on a device/emulator: detection, both models, OCR, PDF
```

The debug APK bundles OpenCV's and ONNX Runtime's native libraries for all four CPU architectures, so it is large (~330 MB). Publish through an Android App Bundle (`bundleRelease`) so that each device downloads only its own architecture; the model itself adds 31 MB.

To regenerate the model:

```bash
git clone https://github.com/tanguymagne/UVDoc
python tools/export_uvdoc_onnx.py --uvdoc UVDoc --out app/src/main/assets/uvdoc.onnx   # needs torch, onnx, onnxruntime
```

## Roadmap

- [x] **Phase 1: Flat scanner MVP**: capture, detection, manual corners, perspective correction, filters, multi-page documents, PDF export
- [x] **Phase 2: Real-time capture**: live outline, focus scoring, stability-based auto-capture, page-turn detection for continuous book scanning
- [x] **Phase 3: Book pages**: spine detection and split, on-device curved-page flattening (UVDoc), gutter shadow correction, finger removal
- [x] **Phase 4: Text (on-device)**: opt-in OCR per page or document (ML Kit, Latin and Devanagari), searchable PDFs, TXT/Markdown export
- [ ] **Phase 4b: Structured text (cloud)**: optional server-side parsing for tables and formulas, more scripts
- [ ] **Phase 5: Hardening**: offline queueing, low-end device performance, more export formats

## Privacy

- No account, no analytics, no ads, no servers. The app has **no internet permission**: ML Kit's network permissions (used only for Google usage statistics) are removed in the manifest, so nothing can leave the device.
- Scans are stored in app-private storage. Android cloud backup includes settings only; device-to-device transfer (new phone setup) includes scans.
- Full policy: [PRIVACY.md](PRIVACY.md).

## Release builds

Release builds are shrunk with R8 and signed with an upload key kept outside git. See [RELEASING.md](RELEASING.md) for the key, the Play Console setup (Data safety answers, listing) and the per-release checklist; the store listing draft is in [docs/store-listing.md](docs/store-listing.md).

```bash
./gradlew :app:bundleRelease     # Play upload: app/build/outputs/bundle/release/app-release.aab
./gradlew :app:assembleRelease   # installable APK for testing the shrunk build
```

## Performance notes

- **Live preview**: camera frames are read in place from native memory (no per-frame copies into the Java heap), and the corner model reuses its input and output buffers, so the analyser allocates almost nothing per frame.
- **Models**: both `.onnx` files are memory-mapped from the APK rather than read into byte arrays, and the corner model is loaded in the background at app start so the camera's first frame isn't held up. The flattening model is loaded ahead of time when Book mode is on.
- **Saving a page**: finishing steps that don't apply (gutter, fingers, filter, rotation) are skipped instead of copying the page through.
- **Lists**: the library and folder grids update by diff, thumbnails for views that scroll away are cancelled, and at most four are decoded at once.

## Design

Dark only: near-black surfaces with a faint teal cast and a single teal accent (`#2DD4BF`), defined in `res/values/colors.xml`. The accent is kept for the main action, selection and the "ready" page outline.

## Tuning

The auto-capture thresholds live in `AutoCapture.Config`: steady time, jitter, minimum coverage, sharpness, the re-arm triggers, the duplicate-page distance and the cooldown. The defaults are first estimates and should be tuned on real devices and books. The same goes for the spine valley depth in `SpineDetector`, the skin margins in `FingerRemover`, and the gutter zone in `Gutter`.

## Third-party licenses

- [DocAligner](https://github.com/DocsaidLab/DocAligner) model weights: Apache-2.0, DocsaidLab (license shipped in `assets/docaligner_LICENSE.txt`)
- [UVDoc](https://github.com/tanguymagne/UVDoc) model weights: MIT, © 2023 Tanguy Magne (notice shipped in `assets/uvdoc_LICENSE.txt`)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime): MIT

- [OpenCV](https://opencv.org/license/): Apache 2.0
- [AndroidX](https://developer.android.com/jetpack/androidx) (CameraX, Room, AppCompat, RecyclerView…): Apache 2.0
- [Material Components for Android](https://github.com/material-components/material-components-android): Apache 2.0
- [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines): Apache 2.0
