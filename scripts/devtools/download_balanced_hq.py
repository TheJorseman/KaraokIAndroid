"""Download the remaining Balanced / HQ / Whisper Base+Small models in
parallel and report SHA-256 + size for the catalog update.
"""
from __future__ import annotations

import hashlib
import os
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

CACHE = Path(os.environ.get("KARAOKEI_DOWNLOAD_CACHE", r"C:\Users\migue\Downloads\hf-cache"))


MODELS = [
    (
        "htdemucs-6s-fp16-balanced.onnx",
        "https://huggingface.co/StemSplitio/htdemucs-6s-onnx/resolve/main/htdemucs_6s_fp16weights.onnx",
    ),
    (
        "htdemucs-4s-fp16-balanced.onnx",
        "https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx",
    ),
    (
        "htdemucs-ft-fp16-hq.onnx",
        "https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx/resolve/main/htdemucs_ft_fp16weights.onnx",
    ),
    (
        "roformer-hq.onnx",
        "https://huggingface.co/silverdaw/mel-band-roformer-vocals-onnx/resolve/main/syhft_core_folded_fp16_webgpu.onnx",
    ),
    (
        "roformer-hq.onnx.data",
        "https://huggingface.co/silverdaw/mel-band-roformer-vocals-onnx/resolve/main/syhft_core_folded_fp16_webgpu.onnx.data",
    ),
    (
        "whisper-base-q5_1.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
    ),
    (
        "whisper-small-q5_1.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
    ),
]


def _hash(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _download(name: str, url: str) -> tuple[str, str, int, float]:
    target = CACHE / name
    target.parent.mkdir(parents=True, exist_ok=True)
    t0 = time.perf_counter()
    if not target.exists() or target.stat().st_size == 0:
        subprocess.run(
            ["curl", "--fail", "--location", "--silent", "--show-error", "-o", str(target), url],
            check=True,
        )
    elapsed = time.perf_counter() - t0
    size = target.stat().st_size
    sha = _hash(target)
    return name, sha, size, elapsed


def main() -> int:
    CACHE.mkdir(parents=True, exist_ok=True)
    print(f"downloading to {CACHE}")
    rows = []
    with ThreadPoolExecutor(max_workers=4) as ex:
        futs = {ex.submit(_download, n, u): (n, u) for n, u in MODELS}
        for fut in as_completed(futs):
            try:
                name, sha, size, elapsed = fut.result()
                rows.append((name, sha, size, elapsed))
                mb = size / (1024 * 1024)
                print(f"  {name:40s}  {mb:7.1f} MB  sha256={sha}  ({elapsed:.1f}s)")
            except Exception as exc:
                n, _ = futs[fut]
                print(f"  {n}: ERROR {exc}")
    print("\n# Catalog-ready rows:")
    for name, sha, size, _ in sorted(rows):
        print(f"# {name}  size={size}  sha256={sha}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
