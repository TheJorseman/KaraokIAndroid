# KaraokIAndroid

Android karaoke app that turns any local audio or video file into a
singable track. The user imports a song, the app isolates the vocal
melody with an on-device neural network, transcribes the lyrics with
`whisper.cpp`, and renders the karaoke view with word-by-word
highlighting. The whole pipeline runs on-device — the network is
limited to the model manager.

## Current status

| Area | Status |
| --- | --- |
| Kotlin / Compose UI | Compiles, runs on emulator-5554 (API 35, Android 16) |
| ONNX inference | ORT Mobile 1.28, XNNPACK + NNAPI providers, backend toggle |
| Separation — Fast (MDX-Net Karaoke 2) | ✅ Embedded in APK, streaming overlap-add Hann |
| Separation — Balanced (HTDemucs 6-stem FP16) | ✅ Wrapper, contract tests pass; runs out-of-memory on the 4 GB emulator |
| Separation — HQ (Mel-Band RoFormer FP16) | ✅ Wrapper, contract tests pass; same OOM caveat |
| Transcription — Fast (Whisper tiny multilingual) | ✅ Embedded in APK, GGML path |
| Transcription — Balanced / HQ (Whisper base / small) | ✅ Wrapper, runs after download |
| Karaoke lyrics engine | ✅ Word-level timing, line composition |
| Storage Access Framework import | ✅ SAF picker, Room cache by SHA-256 |
| Foreground Service for long pipeline | ✅ Real progress callback, cooperative cancel |
| Default song | ✅ "Te Juro Que Te Amo" MP3 bundled in APK |
| Auto-process on first launch | ✅ `DefaultTestAudioSeeder` kicks the pipeline off |
| Visible progress bar in-app | ✅ `PipelineProgressBanner` (stripe + card) |
| Backend selector (XNNPACK / NNAPI / CPU) | ✅ Debug intent `--es debug_set_backend …` |

## Repository layout

```
app/                        Compose app + foreground service
core/
  ai/                      ONNX session helpers, backend selector
  common/                  Result / coroutines / hash utilities
  data/                    Room + DataStore + Hilt module
  designsystem/            Reusable composables (progress banner)
  media/                   Audio extraction (mp3/flac/wav/m4a/mp4/mkv)
  whisper-jni/             whisper.cpp JNI bridge + transcriber
docs/                      Architecture + perf comparison + changelog
feature/
  import/                  SAF song picker
  karaoke-engine/          Pure-Kotlin line/word timing
  karaoke-player/          Compose lyrics view + Media3
  library/                 Library / song detail screens
  model-manager/           Catalog sync + download UI
  onboarding/              First-run screen
  pipeline/                Orchestrator + progress
  separation/              Fast / Balanced / HQ separators
  transcription/           Whisper glue
scripts/
  devtools/                adb / git / audio-diagnosis helpers
  fetch_whisper.ps1        Pins whisper.cpp at a fixed commit
  fixtures/                Small WAVs + Te Juro Que Te Amo MP3
  models/                   Quantize / probe / verify model graphs
  tests/                   Python unit + emulator integration tests
third_party/whisper.cpp/   (ignored) cloned by scripts/fetch_whisper.ps1
```

## What the user sees

1. First launch on a fresh install extracts the bundled MP3 from
   the APK into the app's private files dir and imports it into the
   library. The default tier is `FAST`, which uses the bundled
   MDX-Net Karaoke 2 ONNX (52 MB).
2. `pipelineAutoStart=true` by default, so `DefaultTestAudioSeeder`
   immediately launches the karaoke pipeline against the imported
   song. A determinate progress bar pinned to the top of every
   screen shows "Separando voz 5% Etapa 2/6", and the cached
   `vocals.wav` / `instrumental.wav` / `karaoke.json` /
   `transcript.json` are ready by the time the user opens the
   library.
3. The user can change tier from `FAST` to `BALANCED` (HTDemucs
   6-stem, downloads on demand) or `HQ` (Mel-Band RoFormer,
   downloads on demand).
4. The `KaraokePlayer` renders the synced lyrics with the
   instrumental track.

## Models bundled in the APK (offline-first)

| Asset | Size | Tier | SHA-256 |
| --- | --- | --- | --- |
| `assets/songs/te_juro_que_te_amo.mp3` | 3.7 MB | Demo song | (sha-256 of file contents) |
| `assets/separation/uvr_mdxnet_kara_2.onnx` | 50.4 MB | Fast separation | `bf32e15105a09c0f7dddd2b67346146334d6f3ecb399ed7638eba2ab07cbf5f4` |
| `assets/transcription/ggml-tiny-q5_1.bin` | 30.7 MB | Fast transcription | `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7` |

Final APK size: ~237 MB.

The catalog (`app/src/main/assets/models/catalog.json`) lists six
entries — the three embedded above plus three download-on-demand
ones (HTDemucs 4-stem / 6-stem / FT-Vocals for Balanced and HQ
separation; Whisper base and small for Balanced and HQ
transcription). URLs are the upstream Hugging Face mirrors.

## Build

The standard Android Studio toolchain. Build the debug APK with:

```
gradlew :app:assembleDebug
```

The debug APK uses the in-repo `dev.keystore` (password `devpass`).
For release builds, wire a real keystore outside the repo and update
`signingConfigs.release` in `app/build.gradle.kts` — see `TODO.md`
(Release section).

### Build environment

The Android Studio bundled JBR (`C:\Program Files\Android\Android
Studio\jbr`) is the only Java we tested with. System Java 8 is
unsupported. The Android SDK is expected at
`C:\Users\migue\AppData\Local\Android\Sdk`; `local.properties` is
machine-specific and ignored.

`scripts/requirements.txt` lists the Python deps the smoke tests
and model tools need. Create a venv, install them, run
`python -m scripts.tests.test_emulator_integration` against a live
emulator.

## Run on the emulator

```
# Start an AVD with API 35+ (the project ships with
# Medium_Phone_API_36.1) and make sure `adb` is on PATH.
adb devices                            # expect emulator-5554

# Install the APK and clear any old DB so the seeder re-runs.
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell pm clear com.karaokei.android.debug

# Cold-start the app. The seeder extracts the bundled MP3 and
# kicks off the pipeline automatically.
adb shell am start -n com.karaokei.android.debug/com.karaokei.android.MainActivity

# Watch the pipeline.
adb logcat -s MdxNetSeparator PipelineOrchestrator DefaultTestAudioSeeder
```

Useful debug intent extras:

- `--es debug_set_tier FAST|BALANCED|HQ` — override the preferred
  tier for the next pipeline run.
- `--es debug_set_backend AUTO|XNNPACK|CPU|NNAPI` — switch the
  ORT execution provider (compare latencies in logcat).
- `--es debug_seed_path <files-dir-file>` — import an existing
  file under `filesDir/` and trigger the pipeline on it.

## Tests

The repository ships three test layers; each can run independently.

### Python

```
PYTHONPATH=. KMP_DUPLICATE_LIB_OK=TRUE \
    python -m pytest scripts/tests
```

37 tests pass, 1 skipped (the skip is the `whisper-cli` smoke test,
gated by `whisper-cli` being on PATH).

Smoke tests that exercise the production graphs:

- `python -m scripts.devtools.mdxnet_kara2_smoke` — one chunk on the
  real 53 MB MDX-Net Fast graph.
- `python -m scripts.devtools.htdemucs_smoke` — one chunk on the
  136 MB HTDemucs 6-stem graph.
- `python -m scripts.devtools.htdemucs_4s_smoke` — same for the
  165 MB 4-stem graph.
- `python -m scripts.devtools.roformer_probe` — verify RoFormer
  graph contract.
- `python -m scripts.models.verify_models` — INT8 quantisation
  comparison (rejected for RoFormer; FP32 used).

### Emulator (`scripts/tests/test_emulator_integration.py`)

Pytest-driven tests that drive `adb` to install / clear / start the
app and inspect the Room DB:

```
PYTHONPATH=. KMP_DUPLICATE_LIB_OK=TRUE \
    python -m pytest scripts/tests/test_emulator_integration.py -v
```

7 tests. The slower ones (`test_demo_fixture_reaches_ready`)
wait up to 10 minutes for the full pipeline to land in `READY`.

### Android instrumented (`feature:separation`)

```
gradlew :feature:separation:connectedDebugAndroidTest
```

Two tests run against a live emulator:

- `MdxNetInstrumentedTest#mdx_net_fast_runs_on_a_synthetic_mix`
  downloads the MDX-Net graph from HF, opens it in ORT, runs
  inference over a 6 s synthetic mix and validates the output
  buffers. About 9 s end-to-end on the x86_64 emulator.
- `MdxNetInstrumentedTest#stft_produces_peak_at_sine_carrier`
  verifies the host STFT concentrates a 440 Hz tone in bin
  112–113. Model-free; runs anywhere.

## How to switch the runtime inference backend

```
adb shell am start -n com.karaokei.android.debug/com.karaokei.android.MainActivity
# pipeline runs with XNNPACK (default)

adb shell am start -n com.karaokei.android.debug/com.karaokei.android.MainActivity \
    --es debug_set_backend CPU
# CPU-only baseline

adb shell am start -n com.karaokei.android.debug/com.karaokei.android.MainActivity \
    --es debug_set_backend NNAPI
# force NPU (Pixel 7/8, Tensor G2/G3)
```

The orchestrator logs `Separation finished in N ms (backend=X)` and
`Transcription finished in N ms (backend=X)` after each run so you
can grep timings by backend. See `docs/perf-comparison.md` for the
PyTorch-vs-ORT comparison and published Snapdragon 8 Gen 2 numbers.

## Known limitations

- **HTDemucs / RoFormer on the emulator OOMs.** ORT Mobile XNNPACK
  inflates RSS to ~3 GB for these graphs; the 4 GB emulator with
  no NPU kills the process. They run fine on host CPU and on real
  devices with NNAPI. The streaming overlap-add in `MdxNetSeparator`
  keeps the Fast tier well under 200 MB even on 4-minute songs.
- **RoFormer INT8 is rejected by ORT.** `DynamicQuantizeLinear` on
  the existing FP16 export fails to load. The graph stays FP32/FP16
  until a quantisation-compatible export is available.
- **whisper.cpp on Android is CPU-only.** Whisper.tflite via NNAPI
  would shave ~30% off transcription latency on Tensor-equipped
  devices, but the conversion is out of scope here.
- **The synthetic `karaokei-test-audio.wav` fallback** is generated
  on the fly if the bundled asset extraction fails. CI sandboxes
  that strip app assets still produce a valid pipeline run.

## Project conventions

- Pure-Kotlin modules (`core:common`, `core:designsystem`,
  `feature:karaoke-engine`) ship no Android dependencies and stay
  unit-testable on the JVM.
- All cross-module Android code goes through Hilt; `AppResult<T>`
  and the `getOrThrow()` extension are the canonical error path.
- The Room schema uses `MIGRATION_1_2` and `MIGRATION_2_3`; new
  fields on `models` are additive and never destructive.
- The `models` table's `asset_path` is the source of truth for the
  embedded extract — `ModelLoader` resolves it via
  `context.assets.open(assetPath)`.

## License notes

- App code: MIT.
- `whisper.cpp` (third_party): MIT, pinned by `scripts/fetch_whisper.ps1`.
- UVR MDX-Net Karaoke 2: MIT, distributed at
  `huggingface.co/masszhou/mdxnet`.
- HTDemucs 6-stem / FT-Vocals: MIT, distributed at
  `huggingface.co/StemSplitio`.
- Mel-Band RoFormer vocals: MIT, distributed at
  `huggingface.co/silverdaw/mel-band-roformer-vocals-onnx`.

A proper SBOM is tracked in `TODO.md` (Release section).
