# scripts/models — model build & verification pipeline

Offline build steps that produce the AI models shipped inside the
`fast-model-assetpack/` of the Android app. The output of this
pipeline is what the app reads at first launch.

## What it produces

```
fast-model-assetpack/src/main/assets/
├── separation/
│   └── kim_vocal_2_int8.onnx     # MDX-Net Kim Vocal 2, INT8 quantized
└── transcription/
    ├── ggml-tiny.en-q5_1.bin     # Whisper tiny (Fast tier)
    ├── ggml-base-q5_1.bin        # Whisper base (Balanced tier)
    └── ggml-small-q5_1.bin       # Whisper small (HQ tier)
```

Plus a refreshed SHA-256 in `app/src/main/assets/models/catalog.json`.

## Quick start

```bash
# 1. Install Python deps (one-shot)
pip install -r scripts/requirements.txt

# 2. Build everything
python -m scripts.models.download_models

# 3. Verify quality (FP32 vs INT8 SDR, Whisper smoke test)
python -m scripts.models.verify_models
```

Both scripts are idempotent. Re-running skips files that already
match the expected SHA-256 / size.

## Customisation

| Flag | Effect |
|---|---|
| `--only separation` / `--only transcription` | Build only one family |
| `--force` | Re-download even if the file is present |
| `--from-onnx <path>` | Use a pre-existing Kim Vocal 2 ONNX (skip the download/conversion) |
| `--skip-quantize` | Emit only the FP32 MDX-Net ONNX (no INT8) |
| `--calibration-audio-dir <dir>` | Static INT8 quantization with real audio instead of the dynamic fallback |

The full Whisper set is downloaded by default. If you only want the
Fast tier (the one that ships in the Asset Pack), edit
`WHISPER_TARGETS` in `download_models.py` and remove the entries
you don't need.

## Fallback: synthetic Kim model

The public mirrors for the real Kim Vocal 2 ONNX are unreliable
(404s and gated HF repos as of late 2025). For pipeline validation
the script `build_synthetic_kim.py` produces a Kim UNet of the same
architecture with random init:

```bash
python -m scripts.models.build_synthetic_kim
```

The output is byte-identical to what a real Kim Vocal 2 would
produce *structurally* — same input/output shape, same operator
set, same quantization behaviour — but the random weights are not
musically useful. The asset pack ships it under
`separation/kim_synthetic_int8.onnx` so the verify script and the
integration tests can run end-to-end on any machine that has
`torch` installed, with no network.

When the real Kim Vocal 2 ONNX becomes reachable, the production
filename `separation/kim_vocal_2_int8.onnx` takes precedence in
the catalog. The synthetic file is left in place as a regression
target; the catalog skips it.

## Fallback: Mel-Band RoFormer

The public MIT fallback is available through:

```bash
python -m scripts.models.download_roformer
```

It consists of an ONNX graph and a sidecar `.onnx.data` file of roughly
746 MB. The files must remain beside each other. Its host contract is
44.1 kHz, `n_fft=2048`, `hop=441`, input `[1, 2050, 1101, 2]`, and a
complex mask output. It is intentionally kept outside the APK cache
until the host-side STFT/iSTFT and external-data ORT path are integrated;
it must not be substituted silently for the production MDX pipeline.

## Network sources

The script tries these URLs in order. If both fail for a given
model, re-run with `--from-onnx <path>` (for MDX-Net) or download
the file manually into `scripts/download_cache/`:

- **Whisper ggml**: <https://huggingface.co/ggerganov/whisper.cpp/resolve/main/>
- **MDX-Net ONNX**: <https://huggingface.co/jarredou/lewis_onnx/resolve/main/Kim-Vocal-2.onnx>
- **MDX-Net PyTorch**: <https://huggingface.co/Politrees/RVC_resources/resolve/main/mdx_net_models/Kim/Kim_Vocal_2.pth>

If all the ONNX/PyTorch mirrors disappear, the script preserves the
last downloaded checkpoint in `scripts/download_cache/` and falls
back to it. The model is then re-exported from PyTorch using the
self-contained `KimUNet` definition in `convert_mdxnet.py`.

## Quality verification

`verify_models.py` is the manual companion to the test suite. It
loads both the FP32 and INT8 versions of the separation model, runs
them on a 2-second test audio (synthetic if no WAV is supplied), and
reports the Source-to-Distortion Ratio (SDR). A healthy INT8
quantization lands around 40 dB SDR vs. the FP32 reference; we ship
the model only when the SDR is ≥ 30 dB.

For transcription, the script also runs the bundled
`ggml-tiny.en-q5_1.bin` through `whisper-cli` if it's on the PATH.
This is a sanity check (the model loads and produces output), not a
full WER benchmark — for that, run a labelled test set and compare
against `ggml-tiny.en` (FP16) or `ggml-tiny` (FP32).

## Tests

The pytest suite under `scripts/tests/` covers the quantization
pipeline in isolation (no real Kim Vocal 2 needed) and runs the
end-to-end check on the real model once it's downloaded:

```bash
pip install pytest
python -m pytest scripts/tests -v
```

Tests that require the real models are auto-skipped if the files
aren't present, so the suite is safe to run on a fresh checkout
without network access.
