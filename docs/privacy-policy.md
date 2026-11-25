# Privacy Policy — KaraokIAndroid

_Last updated: 2026-11-25_

KaraokIAndroid is a karaoke app that processes audio **on your
device**. This policy explains what data leaves the device and what
does not.

## What never leaves your device

- **Your audio and video files.** Songs you import are processed
  entirely on-device (vocal separation, transcription, karaoke
  generation). The app never uploads audio content anywhere.
- **Processed outputs.** The separated vocals/instrumental tracks,
  transcripts and karaoke files are stored only in the app's private
  storage.
- **Usage data.** No analytics, tracking or advertising SDKs are
  included.

## What may leave your device (optional features)

- **Model downloads.** Separation and transcription models are
  downloaded from Hugging Face when you select a tier or model in the
  Model manager. Only the model file is requested.
- **Online lyrics sync (opt-in, on by default; can be disabled in
  Ajustes → "Sincronizar en línea").** To find the real lyrics of the
  song being processed, the app sends the **track title, artist name
  and duration** to the [LRCLIB](https://lrclib.net) public API.
  Never audio, never file names of your other files. The response is
  cached locally and re-checked offline.

## Storage and permissions

- Audio files are read through the system file picker (Storage Access
  Framework); the app only sees files you explicitly select.
- A notification permission is used for the processing progress
  foreground service.
- Internet access is used only for the two features above.

## Children

The app does not target children specifically and collects no
personal data.

## Changes

Material changes to this policy will be reflected in this file and in
the repository history.

## Contact

Open an issue in the project repository.
