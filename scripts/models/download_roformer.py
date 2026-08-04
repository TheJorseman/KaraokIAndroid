"""Download the production Mel-Band RoFormer vocal model.

Sources a public MIT ONNX export. The graph is small (~5 MB) but
references an external FP16 weight file (~707 MB) that **must live
side-by-side** with the graph. Both files are written into the
asset pack so the app can load them directly without network on first
launch.

Host contract from the upstream model card (silverdaw):

- Sample rate: 44.1 kHz.
- n_fft: 2048, hop: 441, periodic Hann window, reflect centre pad.
- Input: `[1, 2050, 1101, 2]` = (batch, `(n_fft/2 + 1) * channels`, frames,
  complex), with packed bin index `2 * freq + channel`.
- Output: same shape complex mask, applied by host before iSTFT.

The Android inference path lives in `core:ai`; this script only
prepares the bytes and validates their integrity. The pipeline at
runtime is still responsible for the STFT/iSTFT and overlap-add.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
from pathlib import Path

import requests

from scripts.models.paths import asset_pack_target, file_size_str

GRAPH_NAME = "syhft_core_folded_fp16_webgpu.onnx"
SIDE_NAME = "syhft_core_folded_fp16_webgpu.onnx.data"
BASE_URL = (
    "https://huggingface.co/silverdaw/mel-band-roformer-vocals-onnx/resolve/main/"
)

# Known good SHA-256 (verified at build time against the upstream
# mirror). Keeping them in code prevents drift between the script and
# the app: any change to the weights breaks this check and forces an
# explicit update.
EXPECTED_HASHES = {
    GRAPH_NAME: "dde2bfe8f85d2c12efa24ce4d45cc13e8709b8a72e277a93f130d496d948e918",
    SIDE_NAME: "b08cfc80905e3560a4dd5d30f641299a47dd96d309ebbe9524d9d6c9d2a0356f",
}


def _expected_dir(repo_root: Path) -> Path:
    """Where the downloaded files should land so the app sees them."""
    return repo_root / "fast-model-assetpack" / "src" / "main" / "assets" / "separation"


def _download(url: str, dest: Path, expected_sha256: str) -> None:
    if dest.exists() and _sha256(dest) == expected_sha256:
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    # Stream into a temp file outside OneDrive so the final rename is
    # atomic and OneDrive's antivirus/indexer can't race the hash.
    temp_dir = Path(os.environ.get("TMP", os.environ.get("TEMP", "/tmp")))
    temp_target = temp_dir / f"{dest.name}.{os.getpid()}.part"
    hasher = hashlib.sha256()
    written = 0
    start = time.time()
    try:
        with requests.get(url, stream=True, timeout=120, headers={"User-Agent": "Mozilla/5.0"}) as response:
            response.raise_for_status()
            with temp_target.open("wb") as output:
                for chunk in response.iter_content(1 << 20):
                    if not chunk:
                        continue
                    output.write(chunk)
                    hasher.update(chunk)
                    written += len(chunk)
                    elapsed = time.time() - start
                    rate = written / elapsed if elapsed > 0 else 0
                    print(
                        f"\r  {dest.name}: {written / 1_048_576:.1f} MiB "
                        f"({rate / 1_048_576:.1f} MiB/s)",
                        end="", flush=True,
                    )
        print()
        digest = hasher.hexdigest()
        if digest != expected_sha256:
            temp_target.unlink(missing_ok=True)
            raise SystemExit(
                f"SHA-256 mismatch for {dest.name}: expected {expected_sha256}, got {digest}"
            )
        # Atomic-ish replace: delete the existing file first, then
        # move. OneDrive handles the file deletion lazily; the move
        # is what makes the destination appear.
        if dest.exists():
            dest.unlink()
        temp_target.replace(dest)
        print(f"  {dest.name}: sha256 verified ({digest[:16]}...)")
    finally:
        if temp_target.exists():
            temp_target.unlink(missing_ok=True)


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(repo_root: Path | None = None) -> dict:
    repo_root = repo_root or Path(__file__).resolve().parents[2]
    target_dir = _expected_dir(repo_root)
    manifest = {
        "model_id": "mel-band-roformer-vocals",
        "source": "silverdaw/mel-band-roformer-vocals-onnx",
        "license": "MIT",
        "sidecar": True,
        "sample_rate": 44100,
        "n_fft": 2048,
        "hop": 441,
        "input_shape": [1, 2050, 1101, 2],
        "output_shape": [1, 2050, 1101, 2],
        "files": {},
    }
    for filename in (GRAPH_NAME, SIDE_NAME):
        url = BASE_URL + filename
        dest = target_dir / filename
        print(f"[roformer] {url}")
        _download(url, dest, EXPECTED_HASHES[filename])
        manifest["files"][filename] = {
            "path": str(dest),
            "size_bytes": dest.stat().st_size,
            "sha256": _sha256(dest),
        }
        print(f"  -> {file_size_str(dest)} stored at {dest}")
    manifest_path = target_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"[roformer] wrote {manifest_path}")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
    )
    args = parser.parse_args()
    fetch(args.repo_root)
