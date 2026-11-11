# FlatDocScanner privacy policy

_Last updated: 11 November 2026_

FlatDocScanner is a document and book scanner that works entirely on your phone.

## What the app collects

**Nothing.** FlatDocScanner has no account, no analytics, no advertising and no servers.
Your scans, and the text recognised in them, are never uploaded.

## Network access

There are two builds of the app, and they differ only here.

- **From Google Play**: the app does not request internet access, so it cannot send or
  receive anything. Play delivers its updates.
- **From GitHub**: the app requests internet access for one purpose, updating itself. Once a
  day (you can switch this off in Settings), or when you tap "Check for updates", it asks
  GitHub's public API which release is the latest. If you accept an update, it downloads that
  release's APK from GitHub. These requests contain nothing about you, your phone's contents
  or your scans. Like any download, they reveal your IP address to GitHub, whose own
  [privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement)
  covers what it does with that.

In both builds, the parts of the bundled text-recognition and model libraries that could
report usage statistics to their makers are removed.

## What stays on your phone

- **Photos you take or import, and the pages made from them**, are stored in the app's private
  storage. They are processed on your phone: page detection, flattening, cleanup and text
  recognition all run locally, using models bundled with the app.
- **Recognised text** is stored with the page it came from, only when you ask for it.
- **Settings** you choose.

Deleted folders and pages go to the app's Trash and are erased for good after 30 days, or
when you empty the Trash.

## When something leaves the app

Only when you choose to:

- **Sharing or exporting** (PDF, images, ZIP, text, Word) hands a file to the app you pick in
  Android's share sheet. What happens next is up to that app.
- **Save to gallery**, or the "Save scans to gallery" setting, puts copies of pages in your
  phone's Pictures/FlatDocScanner folder, where other apps with photo access can see them.
- **Backups**: Android's cloud backup includes only the app's settings, never your scans.
  When you move to a new phone with Android's device-to-device transfer, your scans move too.

## Permissions

- **Camera**: to photograph documents. Photos are used only for scanning.
- **Storage** (Android 9 and older only): to save copies of pages to your gallery when you ask.
- **Internet** and **Install apps** (GitHub build only): to download an update and hand it to
  Android's installer, which asks you before installing.

## Children

The app does not collect personal information from anyone, including children.

## Contact

Questions about this policy: open an issue at https://github.com/ogshrug/FlatDocScanner or
email armaanguha@gmail.com.
