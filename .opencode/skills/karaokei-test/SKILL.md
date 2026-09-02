---
name: karaokei-test
description: Build, install and exercise the KaraokIAndroid app on an emulator or device for end-to-end testing. Handles onboarding, library, model manager, debug pipeline trigger and the bundled fixture path.
---

# KaraokIAndroid Test Skill

End-to-end driver for the KaraokIAndroid karaoke app. Covers APK build,
install, the onboarding/home flow, library navigation, model manager
inspection, and triggering the pipeline through the `DebugPipelineTrigger`
intents so the separation / transcription / alignment stages can be
exercised without manual taps.

All commands are run from the repository root unless stated otherwise.
Use the Android Studio bundled JBR, not the system Java 8:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\migue\AppData\Local\Android\Sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

`local.properties` is machine-specific and ignored; the script reads
`$env:ANDROID_HOME` directly.

## Build & Install

Debug APK:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install on the default emulator (override with `--serial`):

```powershell
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

Application id is `com.karaokei.android.debug` (variant `debug`).
Main activity: `com.karaokei.android.MainActivity`.

Launch cold:

```powershell
adb -s emulator-5554 shell am force-stop com.karaokei.android.debug
adb -s emulator-5554 shell am start -n com.karaokei.android.debug/com.karaokei.android.MainActivity
```

Clear app state (drops `onboardingCompleted`, the model table, the
downloaded files and the per-song cache):

```powershell
adb -s emulator-5554 shell pm clear com.karaokei.android.debug
```

## App Architecture (relevant routes)

`KaraokeNavHost` (`app/src/main/java/com/karaokei/android/navigation/KaraokeNavHost.kt`)
gates the start destination on `UserPreferences.onboardingCompleted`:

| Route | Screen | Module |
| --- | --- | --- |
| `onboarding` | "Empezar" welcome screen | `feature:onboarding` |
| `library` | Song list + FAB Importar | `feature:library` |
| `detail/{songId}` | Song detail + Procesar | `feature:library` |
| `player/{songId}` | Karaoke playback | `feature:karaoke-player` |
| `import` | SAF picker | `feature:import` |
| `model_manager` | Tier cards + Descargar | `feature:model-manager` |

The library's top bar has a "Modelos" action that opens the Model
Manager. The library's FAB is "Importar". Each non-ready song row in
the library shows a "Procesar" button that fires
`PipelineForegroundService.start(...)`.

## Home / Onboarding Flow

The onboarding screen is the only path that sets
`onboardingCompleted = true`. Until tapped, the app always opens on
`onboarding`. Three `Text` blocks and a single `Button` labeled
"Empezar" (`feature/onboarding/src/main/java/com/karaokei/feature/onboarding/OnboardingScreen.kt:45`).

Two ways to drive past onboarding:

1. **Tap via UI** (after launching the app):

   ```powershell
   adb -s emulator-5554 shell input tap <x> <y>
   ```

   `input tap` needs the coordinates of the button; capture them
   with `uiautomator dump` and parse the resulting XML.

2. **Mark as completed via the debug trigger** (preferred for CI):

   ```powershell
   adb -s emulator-5554 shell pm clear com.karaokei.android.debug
   adb -s emulator-5554 shell am start -n com.karaokei.android.debug/com.karaokei.android.MainActivity
   # wait for catalog sync, then tap "Empezar" with input keyevent KEYCODE_TAB
   # then KEYCODE_ENTER if the button is focused, or use input tap with
   # the location from uiautomator dump.
   ```

`screenshots/` folder is the canonical place to verify what the user
sees. After every step, capture:

```powershell
adb -s emulator-5554 shell screencap -p /sdcard/step.png
adb -s emulator-5554 pull /sdcard/step.png screenshots/
```

## Library + Model Manager

After "Empezar", the app lands on `library`. The seeded fixture
(`DefaultTestAudioSeeder`) inserts a row for `Te Juro Que Te Amo` if
`filesDir/te_juro_que_te_amo.mp3` exists, otherwise it generates a
synthetic two-tone WAV named `karaokei-test-audio.wav`. Both names
share the same `karaokei-test-audio.wav` fixture marker.

Open the Model Manager from the library top bar action "Modelos"
(button at the top right). The screen replies with three tier cards
(Fast / Balanced / HQ) and a "Cerrar" button on the top bar.

The catalog is bundled at `app/src/main/assets/models/catalog.json`
(version 7 on the current tree, six entries: three separation + three
transcription). It is re-synced on every cold start via
`KaraokeApp.onCreate` (`KaraokeApp.kt:38`) so newly bundled entries
appear without a `pm clear`.

## Models and Catalog Verification

Pull the Room database (main + WAL + SHM) and inspect the `models` table:

```powershell
python -m scripts.devtools.dump_emulator_db
```

Quick programmatic check (the `models` table has at least six rows after
cold start, one per catalog entry):

```powershell
adb -s emulator-5554 exec-out run-as com.karaokei.android.debug sh -c "cat databases/karaoke.db databases/karaoke.db-wal databases/karaoke.db-shm" > db.dump
```

Then open `db.dump` with `sqlite3` (or `python -m scripts.devtools.inspect_db db.dump`).

Expected `id` values after a fresh install:

```text
mdx-net-kara-2-fast-sep
htdemucs-6s-fp16-balanced-sep
mel-band-roformer-vocals-hq-sep
whisper-tiny-q5-1-fast
whisper-base-q5-1-balanced-tr
whisper-small-q5-1-hq-tr
```

To force the model manager to show a model as already downloaded
without running the full download pipeline, push the file into
`filesDir/...` and seed the `models` row via the debug trigger:

```powershell
# Models already shipped as embedded assets live in app assets;
# for externally downloaded models:
adb -s emulator-5554 push model.onnx /data/local/tmp/model.onnx
adb -s emulator-5554 shell run-as com.karaokei.android.debug cp /data/local/tmp/model.onnx files/<relative-path>
adb -s emulator-5554 shell am start -n com.karaokei.android.debug/com.karaokei.android.MainActivity \
    --es debug_seed_model_id <model-id> \
    --es debug_seed_model_path <relative-path>
```

The accepted extras are defined in
`app/src/main/java/com/karaokei/android/debug/DebugPipelineTrigger.kt:177-180`.

## Triggering the Pipeline (Demo / Debug)

The `DebugPipelineTrigger` is the canonical way to exercise the
pipeline end to end without manual taps. MainActivity reads four
intent extras on `onCreate`:

| Extra | Action |
| --- | --- |
| `debug_seed_uri` | `content://` URI; copied to `filesDir` and imported |
| `debug_seed_path` | File name under `filesDir` (or absolute path) |
| `debug_seed_model_id` | Marks a model as downloaded |
| `debug_seed_model_path` | Relative path under `filesDir` for the model |

Use the bundled synthetic fixture (12 s two-tone WAV, no model
required):

```powershell
adb -s emulator-5554 shell pm clear com.karaokei.android.debug
adb -s emulator-5554 shell am start -n com.karaokei.android.debug/com.karaokei.android.MainActivity
# wait for catalog sync (~5 s) and seeder
adb -s emulator-5554 shell am start -n com.karaokei.android.debug/com.karaokei.android.MainActivity \
    --es debug_seed_path karaokei-test-audio.wav
```

The second `am start` invocation picks up the existing process and
forwards the extra to `MainActivity.onCreate`, which delegates to
`DebugPipelineTrigger.handleFileCopy`. It copies the fixture into
`filesDir/debug_karaokei-test-audio.wav`, imports it through
`SongRepository.import`, and starts `PipelineForegroundService`.

For a real song, push the MP3 first and trigger with its basename:

```powershell
python -m scripts.devtools.push_real_song --serial emulator-5554
# pushes scripts/fixtures/te_juro_que_te_amo.mp3 to /sdcard/Music/
# Once the user imports it through the UI it routes to the
# foreground service end-to-end. For a fully automated path:
adb -s emulator-5554 shell am start -n com.karaokei.android.debug/com.karaokei.android.MainActivity \
    --es debug_seed_path te_juro_que_te_amo.mp3
```

Wait for the pipeline to finish (poll Room or `processing_cache`):

```powershell
$timeout = 90
$deadline = (Get-Date).AddSeconds($timeout)
while ((Get-Date) -lt $deadline) {
    $cache = adb -s emulator-5554 shell run-as com.karaokei.android.debug sqlite3 databases/karaoke.db "SELECT stage, completed_at FROM processing_cache ORDER BY completed_at DESC LIMIT 5" 2>$null
    if ($cache -match "DONE|READY") { break }
    Start-Sleep -Seconds 2
}
```

## Foreground Service & Notification

`PipelineForegroundService` shows a `CATEGORY_PROGRESS` notification
with the current stage and per-window progress. For the fixture, the
notification label is `Procesando canción de prueba…` /
`Transcribiendo (demo)…` / `Alineando (demo)…` — never
`Separando voz…` — because the fixture path skips the AI model.

```powershell
adb -s emulator-5554 shell dumpsys notification --noredact | Select-String -Pattern "karaokei"
```

Verify the notification belongs to the app and contains the expected
stage label during the run.

## Automated Tests

Python contract tests (no device required):

```powershell
$env:PYTHONPATH = "."
$env:KMP_DUPLICATE_LIB_OK = "TRUE"
& "C:\Users\migue\anaconda3\python.exe" -m pytest scripts/tests -v
```

The Whisper CLI test is skipped unless `whisper-cli` is on `PATH`.

Device integration tests (requires a running emulator/device):

```powershell
$env:KARAOKEI_EMULATOR_SERIAL = "emulator-5554"
& "C:\Users\migue\anaconda3\python.exe" -m pytest scripts/tests/test_emulator_integration.py -v
```

The five integration tests cover:

- `test_emulator_alive` — ADB shell sanity.
- `test_catalog_sync_runs_at_cold_start` — 6 catalog entries after `pm clear`.
- `test_demo_fixture_reaches_ready` — Pipeline finishes on the fixture.
- `test_notification_label_avoids_separando_voz_for_fixture` — Notification copy.
- `test_three_tier_dispatch_routes_to_correct_separator` — Tier dispatch by `tierClass`.

Android instrumented tests (run via Gradle):

```powershell
.\gradlew.bat :feature:separation:connectedDebugAndroidTest --no-daemon
```

This downloads UVR MDX-Net KARA_2 from Hugging Face and asserts the
real ORT inference on a synthetic 6 s mix.

## Failure Modes to Watch

- **OOM in emulator** with HTDemucs (136 MB) / RoFormer (711 MB): the
  emulator only has 4 GB and no NNAPI, so the XNNPACK allocator
  inflates RSS to ~3 GB and `lowmemorykiller` kills the process. This
  is expected; the same model runs on the host in seconds.
- **INT8 RoFormer graphs are rejected** by ORT (`DynamicQuantizeLinear`
  on `float16`); the catalog only ships FP16. Do not try to enable an
  INT8 build.
- **Whisper multi-idioma** is the default; the Fast tier uses
  `ggml-tiny-q5_1.bin`, Balanced uses `ggml-base-q5_1.bin`, HQ uses
  `ggml-small-q5_1.bin`. All three are multilingual.
- **Emulator-only**: seeded fixture is the synthetic WAV; to use the
  real `Te Juro Que Te Amo.mp3` you have to push it (see
  `scripts/devtools/push_real_song.py`).

## Key Files

- Entry point: `app/src/main/java/com/karaokei/android/MainActivity.kt`
- Navigation: `app/src/main/java/com/karaokei/android/navigation/KaraokeNavHost.kt`
- Debug trigger: `app/src/main/java/com/karaokei/android/debug/DebugPipelineTrigger.kt`
- Onboarding screen: `feature/onboarding/src/main/java/com/karaokei/feature/onboarding/OnboardingScreen.kt`
- Library screen: `feature/library/src/main/java/com/karaokei/feature/library/LibraryScreen.kt`
- Model manager screen: `feature/model-manager/src/main/java/com/karaokei/feature/modelmanager/ModelManagerScreen.kt`
- Catalog asset: `app/src/main/assets/models/catalog.json`
- Test SEED: `app/src/main/java/com/karaokei/android/testaudio/DefaultTestAudioSeeder.kt`
- Pipeline orchestrator: `feature/pipeline/src/main/java/com/karaokei/feature/pipeline/PipelineOrchestrator.kt`
- Integration tests: `scripts/tests/test_emulator_integration.py`
- Devtools: `scripts/devtools/push_real_song.py`,
  `scripts/devtools/dump_emulator_db.py`, `scripts/devtools/inspect_db.py`
