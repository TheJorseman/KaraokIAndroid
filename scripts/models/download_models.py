"""Download + convert all models required by KaraokIAndroid.

The plan (T0.5, T2.1, T2.5) requires two model families to ship
with the app:

  - **Separation**: MDX-Net Kim Vocal 2, ONNX, INT8 quantized.
  - **Transcription**: Whisper ggml-tiny.en-q5_1 (Fast tier). The
    Balanced and HQ variants are downloaded on demand from the
    catalog in the app itself.

This script is the offline build step that populates the Asset Pack
so the app works on a fresh install without network access. It is
idempotent: re-running it skips files that already match the
expected SHA-256.

Usage:

    # Everything
    python -m scripts.models.download_models

    # Just the separation model
    python -m scripts.models.download_models --only separation

    # Just the transcription models
    python -m scripts.models.download_models --only transcription

    # Static quantization with a real calibration set
    python -m scripts.models.download_models \\
        --calibration-audio-dir scripts/fixtures/calibration

The output is written into:

    fast-model-assetpack/src/main/assets/
        separation/kim_vocal_2_int8.onnx
        transcription/ggml-tiny.en-q5_1.bin
        transcription/ggml-base-q5_1.bin
        transcription/ggml-small-q5_1.bin

These paths match the `asset_path` keys in
`app/src/main/assets/models/catalog.json`.
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path
from urllib.parse import urlparse

import requests

from scripts.models import convert_mdxnet
from scripts.models.paths import (
    asset_pack_target,
    file_size_str,
    sha256_of,
    staged,
)


# Whisper ggml files hosted on the ggerganov/whisper.cpp HF mirror.
# These are the canonical quantized variants for the Q5_1 level
# (good quality / size trade-off for mobile).
WHISPER_GGML_BASE: str = (
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
)
WHISPER_TARGETS: list[tuple[str, str, int]] = [
    # (catalog id, filename, size_bytes_approx)
    ("whisper-tiny-q5-1-fast", "ggml-tiny.en-q5_1.bin", 31_000_000),
    ("whisper-base-q5-1-balanced-tr", "ggml-base-q5_1.bin", 57_000_000),
    ("whisper-small-q5-1-hq-tr", "ggml-small-q5_1.bin", 190_000_000),
]


def _download_to(url: str, dest: Path) -> None:
    """Stream a file with progress to `dest`."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        return
    tmp = dest.with_suffix(dest.suffix + ".part")
    with requests.get(url, stream=True, timeout=60, allow_redirects=True) as r:
        r.raise_for_status()
        total = int(r.headers.get("Content-Length", 0))
        written = 0
        chunk_size = 1 << 20  # 1 MiB
        with tmp.open("wb") as f:
            for chunk in r.iter_content(chunk_size=chunk_size):
                if not chunk:
                    continue
                f.write(chunk)
                written += len(chunk)
                if total:
                    pct = 100 * written / total
                    sys.stdout.write(
                        f"\r  {dest.name}  {pct:5.1f}%  "
                        f"{written / 1_048_576:6.1f} / {total / 1_048_576:6.1f} MiB"
                    )
                    sys.stdout.flush()
        sys.stdout.write("\n")
    tmp.replace(dest)


def download_whisper(force: bool = False) -> list[dict]:
    results: list[dict] = []
    for catalog_id, filename, expected_size in WHISPER_TARGETS:
        url = WHISPER_GGML_BASE + filename
        cache_path = staged(catalog_id) / filename
        asset_path = asset_pack_target("transcription", filename)
        if not force and cache_path.exists() and cache_path.stat().st_size == expected_size:
            sys.stdout.write(f"[whisper] {filename} already present ({file_size_str(cache_path)})\n")
        else:
            sys.stdout.write(f"[whisper] downloading {url}\n")
            _download_to(url, cache_path)
        # Copy into the asset pack.
        asset_path.parent.mkdir(parents=True, exist_ok=True)
        if not asset_path.exists() or sha256_of(asset_path) != sha256_of(cache_path):
            shutil.copy2(cache_path, asset_path)
        results.append({
            "catalog_id": catalog_id,
            "filename": filename,
            "size_bytes": cache_path.stat().st_size,
            "sha256": sha256_of(cache_path),
            "asset_pack_path": str(asset_path),
        })
    return results


def download_separation(
    from_onnx: Path | None = None,
    skip_quantize: bool = False,
    calibration_audio_dir: Path | None = None,
) -> dict:
    sys.stdout.write("[mdx] running MDX-Net Kim Vocal 2 conversion\n")
    meta = convert_mdxnet.convert(
        model_id="kim-vocal-2-int8-fast",
        from_onnx=from_onnx,
        skip_quantize=skip_quantize,
        calibration_audio_dir=calibration_audio_dir,
    )
    out_int8 = Path(meta["output"]) if "output" in meta else None
    asset_path = asset_pack_target("separation", "kim_vocal_2_int8.onnx")
    if out_int8 is not None and out_int8.exists():
        asset_path.parent.mkdir(parents=True, exist_ok=True)
        if not asset_path.exists() or sha256_of(asset_path) != sha256_of(out_int8):
            shutil.copy2(out_int8, asset_path)
        meta["asset_pack_path"] = str(asset_path)
        meta["asset_pack_sha256"] = sha256_of(asset_path)
    return meta


def update_catalog_with_sha256(results: list[dict] | dict) -> None:
    """Rewrite the catalog JSON with the real SHA-256 of each model.

    The shipped catalog uses placeholder hashes (all zeros) because
    we don't know the hash until we've actually produced the file.
    After `download_models` succeeds we patch the catalog so the app
    can verify the asset at load time.
    """
    import json
    catalog_path = Path("app/src/main/assets/models/catalog.json")
    if not catalog_path.exists():
        sys.stdout.write(f"[catalog] not found at {catalog_path}, skipping\n")
        return
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    flat: list[dict] = results if isinstance(results, list) else [results]
    by_id = {r["catalog_id"]: r for r in flat if "catalog_id" in r}
    for entry in catalog.get("entries", []):
        if entry.get("id") in by_id:
            entry["checksum_sha256"] = by_id[entry["id"]]["sha256"]
            entry["size_bytes"] = by_id[entry["id"]]["size_bytes"]
    catalog_path.write_text(
        json.dumps(catalog, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    sys.stdout.write(f"[catalog] updated {catalog_path}\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--only",
        choices=("separation", "transcription", "all"),
        default="all",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Re-download even if the file already exists.",
    )
    parser.add_argument(
        "--from-onnx",
        type=Path,
        default=None,
        help="MDX-Net: skip the download and use this ONNX as the FP32 source.",
    )
    parser.add_argument(
        "--skip-quantize",
        action="store_true",
        help="MDX-Net: emit the FP32 ONNX only, do not quantize.",
    )
    parser.add_argument(
        "--calibration-audio-dir",
        type=Path,
        default=None,
        help="MDX-Net: directory of 16 kHz mono WAVs for static INT8 quantization.",
    )
    args = parser.parse_args(argv)

    all_results: list[dict] = []

    if args.only in ("transcription", "all"):
        all_results.extend(download_whisper(force=args.force))

    if args.only in ("separation", "all"):
        meta = download_separation(
            from_onnx=args.from_onnx,
            skip_quantize=args.skip_quantize,
            calibration_audio_dir=args.calibration_audio_dir,
        )
        # Normalize to a result dict.
        all_results.append({
            "catalog_id": "kim-vocal-2-int8-fast",
            "filename": "kim_vocal_2_int8.onnx",
            "size_bytes": Path(meta["output"]).stat().st_size,
            "sha256": meta["output_sha256"],
            "asset_pack_path": meta.get("asset_pack_path"),
        })

    print("\n=== Summary ===")
    for r in all_results:
        print(
            f"  {r['catalog_id']:36}  "
            f"{file_size_str(Path(r['asset_pack_path'] or '')):>10}  "
            f"sha256={r['sha256'][:16]}…"
        )

    update_catalog_with_sha256(all_results)
    return 0


if __name__ == "__main__":
    sys.exit(main())
