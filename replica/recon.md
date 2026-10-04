# Recon map: vFlat Scan (Android)

Scope: the book/document capture loop: detect, auto-capture, flatten, split, clean up, OCR, export
For: the DocScanner Android app (`com.example.docscanner`)
Date: 2026-10-04

Clean-room: this map is from public pages only. No account was used and no app binary or network traffic was inspected.
Screens are inferred from public listings. Confirm them against your own install before building.

## Sources

| # | source | URL | notes |
| --- | --- | --- | --- |
| 1 | marketing site | https://vflat.com/en | feature list, free-tier caps, privacy note on OCR uploads |
| 2 | Google Play listing | https://play.google.com/store/apps/details?id=com.voyagerx.scanner | 10M+ installs; exports PDF/JPG/Word/TXT/ZIP, share links, TTS |
| 3 | App Store listing | https://apps.apple.com/us/app/vflat-scan-pdf-scanner/id1540238220 | auto-capture on hold-still / page turn |
| 4 | third-party review | https://tigerba.com/review-vflat-an-app-that-turns-your-phone-into-a-book-scanner/ | book-scanning workflow |
| 5 | user forum | https://discourse.devontechnologies.com/t/three-cheers-for-the-free-vflat-app-for-de-warping-iphone-photos-of-book-pages/72523 | real-world dewarping use |

## Core loop

Hold the phone over an open book, turn pages, and get flat, split, cleaned pages with no tapping.

## Screens (inferred, confidence medium)

| ID | screen | route / how to reach | purpose | key components | states seen |
| --- | --- | --- | --- | --- | --- |
| S01 | Library | app launch | list of scans/folders | list, FAB, search | empty, filled |
| S02 | Camera | FAB on S01 | capture | preview, edge overlay, mode switch (single / two-page / auto), shutter, page counter | no page found, page found, capturing, permission denied |
| S03 | Review / pages | after capture | check pages in a batch | thumbnails grid, reorder, delete, retake | 1 page, many pages |
| S04 | Page editor | tap a page in S03 | fix one page | corner handles, rotate, filter picker, finger-removal toggle | before/after |
| S05 | OCR text | action on S03/S04 | read/copy text | text view, copy, TTS play | recognising, done, failed/offline |
| S06 | Export / share | action on S03 | output | format picker (PDF/JPG/TXT/Word/ZIP), share sheet | exporting, done |
| S07 | Settings / plan | menu | prefs, subscription | toggles, paywall | free, premium |

## Flows

```
F01 Scan a book continuously
    S01 -> S02 (two-page + auto) -> [turn page, auto-capture] x N -> S03 -> S06
    happy path taps: ~4 regardless of page count
    edge: page turn not detected, hand in frame, glare, focus hunting, last page single-sided

F02 Scan a single document
    S01 -> S02 -> auto/manual capture -> S04 (adjust corners) -> S06
    happy path taps: ~4

F03 Get text from a page
    S03 -> S05 -> copy / export TXT
    edge: unsupported script, offline, low-quality flattening
```

## Components

| component | variants | states | used on |
| --- | --- | --- | --- |
| Edge overlay | quad, two-page with spine line | searching, locked, capturing | S02 |
| Mode switch | single, two-page, auto | selected | S02 |
| Page thumbnail | normal, selected, processing | dragging | S03 |
| Corner handles | 4-point | dragging, magnifier | S04 |
| Filter chip | original, color, B&W, gray | selected | S04 |

## Inferred data model

```
Document  id, title, created_at, updated_at, page_count, folder_id?
          evidence: library list (S01), export of multi-page PDF
          confidence: high
Page      id, document_id, index, original_uri, processed_uri, corners (json),
          filter, split_side (left|right|none), ocr_text?, ocr_status
          evidence: per-page editing, two-page split, OCR per page
          confidence: medium
Folder    id, name   (confidence: guess)
```

Relationships: Folder 1-n Document, Document 1-n Page.

## Feature matrix

See `features.csv`. Must: 14, should: 5, could: 8, skip: 1.

## Out of scope (cannot or should not be cloned)

- vFlat's trained models, server infrastructure and brand. Use open models (UVDoc, ML Kit, WeVisDoc) and your own name and assets.
- The free-tier caps. Those are their business choice, so set your own.

## Size

Screens 7, flows 3, entities 3. Hard parts: curved-page flattening, auto-capture on page turn, finger removal, plus OCR beyond 5 scripts if you need it.
Size: **L** (a quarter), driven by dewarping R&D. A flat-document MVP alone is M.
