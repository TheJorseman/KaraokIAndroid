"""Central path management for the model build pipeline.

Every output of the download/convert/quantize scripts is computed from
the absolute root of the repo so the scripts are safe to invoke from
any working directory.
"""
from __future__ import annotations

import os
from pathlib import Path

# Repo root: <repo>/scripts/models/paths.py -> <repo>
REPO_ROOT: Path = Path(__file__).resolve().parents[2]

# Where raw downloads (PyTorch weights, FP32 ONNX, full-precision
# Whisper ggml) live. Kept OUTSIDE the AAB — only the quantized
# outputs are shipped.
DOWNLOAD_CACHE: Path = REPO_ROOT / "scripts" / "download_cache"
DOWNLOAD_CACHE.mkdir(parents=True, exist_ok=True)

# Where quantized production models land before they are placed in
# the Asset Pack. Files here are byte-for-byte identical to what the
# app ships.
BUILD_OUTPUT: Path = REPO_ROOT / "scripts" / "build_output"
BUILD_OUTPUT.mkdir(parents=True, exist_ok=True)

# Final shipping location. The Android Gradle Plugin's asset-pack
# mechanism picks this folder up via the `bundle { assetPacks { ... } }`
# block in :app/build.gradle.kts.
ASSET_PACK_ROOT: Path = (
    REPO_ROOT / "fast-model-assetpack" / "src" / "main" / "assets"
)
ASSET_PACK_SEPARATION: Path = ASSET_PACK_ROOT / "separation"
ASSET_PACK_TRANSCRIPTION: Path = ASSET_PACK_ROOT / "transcription"
ASSET_PACK_SEPARATION.mkdir(parents=True, exist_ok=True)
ASSET_PACK_TRANSCRIPTION.mkdir(parents=True, exist_ok=True)

# Test fixtures (small audio samples, expected outputs).
FIXTURES_DIR: Path = REPO_ROOT / "scripts" / "fixtures"
FIXTURES_DIR.mkdir(parents=True, exist_ok=True)

# Where the int8-quantized outputs are staged for the asset pack.
# Each model has its own subdirectory so multiple variants can coexist.
def staged(model_id: str) -> Path:
    p = BUILD_OUTPUT / model_id
    p.mkdir(parents=True, exist_ok=True)
    return p


def asset_pack_target(category: str, filename: str) -> Path:
    if category == "separation":
        return ASSET_PACK_SEPARATION / filename
    if category == "transcription":
        return ASSET_PACK_TRANSCRIPTION / filename
    raise ValueError(f"unknown category: {category}")


def sha256_of(path: Path) -> str:
    """Hex SHA-256 of a file. Streams so it works on big model files."""
    import hashlib
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def file_size_str(path: Path) -> str:
    size = path.stat().st_size
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024.0:
            return f"{size:.1f} {unit}"
        size /= 1024.0
    return f"{size:.1f} TB"


# Quiet the OMP duplicate-library warning that some Python builds
# emit when both torch and onnxruntime are loaded in the same process.
os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")
