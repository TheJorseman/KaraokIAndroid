# Agent Instructions

## Build Environment

- Use the Android Studio bundled JBR, not the system Java 8: `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`.
- The Android SDK is normally at `C:\Users\migue\AppData\Local\Android\Sdk`; `local.properties` is machine-specific and ignored.
- The repository now includes `gradlew.bat` and pins Gradle `8.10.2`; use the wrapper, not a global Gradle installation.
- Use explicit command timeouts for Gradle and model commands; Android/NDK tasks can take several minutes.

## Commands

- Android debug build: `gradlew.bat :app:assembleDebug --no-daemon`.
- Focused Android module compile: `gradlew.bat :core:ai:compileDebugKotlin --no-daemon`.
- Regenerate/verify the wrapper: `gradlew.bat wrapper --gradle-version 8.10.2 --distribution-type bin`.
- Python model tests: use an environment containing `scripts/requirements.txt`, set `PYTHONPATH=.` and `KMP_DUPLICATE_LIB_OK=TRUE`, then run `python -m pytest scripts/tests -v`. On this machine the tested interpreter is `C:\Users\migue\anaconda3\python.exe`; system `C:\Python311\python.exe` lacks pytest/model dependencies.
- Model verification: `python -m scripts.models.verify_models`; transcription verification is skipped unless `whisper-cli` is on `PATH`.
- Fetch the native transcription dependency when a fresh checkout lacks it: `powershell -ExecutionPolicy Bypass -File scripts/fetch_whisper.ps1`.

## Module Boundaries

- `app` owns navigation, Hilt application setup, and the pipeline foreground service.
- `core:common` is JVM-only; keep shared serializable/domain types here. It must not depend on Android modules.
- `core:data`, `core:media`, `core:ai`, and `core:whisper-jni` are Android library modules.
- `feature:karaoke-engine` is JVM-only and contains pure lyric/position logic. Android/database orchestration belongs in `feature:pipeline`.
- The directory is `feature/import`, but its Kotlin package and namespace are `com.karaokei.feature.importer`; `import` is a Java keyword.
- `core:data` uses package `com.karaokei.core.data.importer`, not `.import`.

## Important Quirks

- Do not add `androidx.media3:media3-decoder-ffmpeg` as a Maven dependency; it is not published there. Native Media3 extraction is the current buildable path; compiling FFmpeg requires a separate AndroidX source build.
- The Asset Pack directory exists for future packaging, but it is not registered in `app/build.gradle.kts`. Current model delivery is through `feature:model-manager` and SHA-256 verification.
- `core:whisper-jni` links the checkout at `third_party/whisper.cpp`; run `scripts/fetch_whisper.ps1` before CMake builds on a fresh checkout. The checkout is ignored and pinned by the script.
- Do not add a custom test runner under `app/src/main`; instrumentation runners belong under test sources. The app uses `androidx.test.runner.AndroidJUnitRunner`.
- Hilt classes with `@Inject` constructors are auto-provided. Do not add `@Provides fun provideX(impl: X): X`, which creates a Dagger dependency cycle.
- Pure JVM modules target bytecode 17 using the current JDK; do not use `jvmToolchain(17)` unless a JDK 17 is installed.

## Models

- `scripts/models/download_models.py` downloads Whisper ggml models and attempts Kim Vocal 2 sources. Public Kim mirrors may be gated or unavailable.
- `scripts/models/build_synthetic_kim.py` creates a random-weight structural fallback only for pipeline tests; it is not musically usable and must not replace production weights silently.
- `scripts/models/verify_models.py` compares FP32/INT8 outputs. The acceptance threshold is SDR > 30 dB; real production weights still require verification before distribution.
- Generated `scripts/download_cache`, `scripts/build_output`, and calibration fixtures are ignored. Do not commit large downloaded model artifacts without an explicit request.

## Network Constraint

- Only `feature:model-manager` may contain model download/network logic. Do not introduce HTTP clients or URL connections in media, AI inference, transcription, rendering, or playback modules.
