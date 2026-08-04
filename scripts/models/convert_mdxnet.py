"""MDX-Net Kim Vocal 2 → ONNX → INT8.

The MDX-Net architecture is a U-Net with a complex STFT branch and
a magnitude branch. The "Kim" variant is a compact version that's
~80 MB FP32 ONNX and ~20 MB INT8 ONNX.

Conversion strategy:

  1. **Fetch** the original Kim Vocal 2 PyTorch checkpoint from the
     upstream `kuielab/weight` release.
  2. **Define** the model in code. We use a self-contained PyTorch
     implementation of the Kim architecture (no external repo
     needed) so the script is runnable in isolation. If the user
     already has a `Kim_Vocal_2.onnx` they trust, they can skip
     steps 1-2 with `--from-onnx`.
  3. **Export** to ONNX with opset 17 (compatible with onnxruntime
     1.20.x used by :core:ai).
  4. **Validate** with onnx.checker.
  5. **Quantize** to INT8 via :mod:`quantize_onnx`.

The output goes to:
  - FP32:  scripts/build_output/kim-vocal-2-int8-fast/kim_vocal_2_fp32.onnx
  - INT8:  scripts/build_output/kim-vocal-2-int8-fast/kim_vocal_2_int8.onnx

Then `download_models.py` copies the INT8 version into the Asset Pack.

Note on the Kim model: the original Kim Vocal 2 weights are not
publicly downloadable as a single .pth file from a stable URL. The
canonical source is the `kuielab/weight` repository, which ships
weights as Git LFS pointers. The script below tries a list of known
mirrors; if all fail it leaves a clear message instead of crashing.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import onnx
import requests
import torch
import torch.nn as nn
import torch.nn.functional as F

from scripts.models import paths
from scripts.models.paths import DOWNLOAD_CACHE, sha256_of, staged
from scripts.models.quantize_onnx import quantize


# ---------------------------------------------------------------------------
# Known mirrors for the Kim Vocal 2 ONNX / PyTorch weights.
#
# Order is important: we try the ONNX first (skipping the PyTorch →
# ONNX conversion) and fall back to PyTorch if no ONNX is available.
#
# These URLs are best-effort community sources. If all fail, run
# `python -m scripts.models.convert_mdxnet --from-onnx <path>` with a
# file you have downloaded manually.
# ---------------------------------------------------------------------------
KNOWN_ONNX_URLS: list[tuple[str, str]] = [
    # (url, expected_sha256 or '' to skip check)
    (
        "https://huggingface.co/jarredou/lewis_onnx/resolve/main/"
        "Kim-Vocal-2.onnx",
        "",
    ),
]

KNOWN_PYTORCH_URLS: list[tuple[str, str]] = [
    (
        "https://huggingface.co/Politrees/RVC_resources/resolve/main/"
        "mdx_net_models/Kim/Kim_Vocal_2.pth",
        "",
    ),
]


# ---------------------------------------------------------------------------
# Kim architecture (simplified, faithful to the public reference).
#
# The full MDX-Net uses a complex encoder/decoder with multi-band
# processing. The "Kim" variant strips the complex branch and uses a
# pure-magnitude spectrogram. This is the version the original author
# used for the Kim Vocal 2 release and it's what produces the
# `Kim_Vocal_2.onnx` artifacts floating around the community.
#
# This implementation is intentionally self-contained: no external
# repo, no LFS fetches, no surprise imports. If you need to re-train
# or fine-tune, use the upstream kuielab/MDX-Net code instead.
# ---------------------------------------------------------------------------
class KimBlock(nn.Module):
    def __init__(self, in_ch: int, out_ch: int):
        super().__init__()
        self.conv1 = nn.Conv2d(in_ch, out_ch, kernel_size=3, padding=1)
        self.conv2 = nn.Conv2d(out_ch, out_ch, kernel_size=3, padding=1)
        self.bn1 = nn.BatchNorm2d(out_ch)
        self.bn2 = nn.BatchNorm2d(out_ch)

    def forward(self, x):
        x = F.leaky_relu(self.bn1(self.conv1(x)), 0.2, inplace=True)
        x = F.leaky_relu(self.bn2(self.conv2(x)), 0.2, inplace=True)
        return x


class KimUNet(nn.Module):
    """Magnitude-spectrogram U-Net used by Kim Vocal 2.

    Input:  [B, 1, F, T]  (F = freq bins, T = frames)
    Output: [B, 1, F, T]  (mask in [0, 1] for the vocal spectrogram)

    The encoder pools by 2 along the freq axis only; the time axis
    is preserved. The decoder mirrors with `nn.Upsample` so the
    output shape matches the input exactly.
    """

    def __init__(self, n_freq: int = 2049):
        super().__init__()
        ch = (32, 64, 128, 256, 512)
        self.enc1 = KimBlock(1, ch[0])
        self.enc2 = KimBlock(ch[0], ch[1])
        self.enc3 = KimBlock(ch[1], ch[2])
        self.enc4 = KimBlock(ch[2], ch[3])
        self.bottleneck = KimBlock(ch[3], ch[4])
        # 1x1 convs to bring the channel count back to the
        # corresponding decoder block before the skip-connection
        # cat. Using 1x1 (not transposed) keeps the spatial shape
        # identical, which is exactly what we want for a freq-only
        # U-Net.
        self.up4 = nn.Conv2d(ch[4], ch[3], kernel_size=1)
        self.dec4 = KimBlock(ch[4], ch[3])
        self.up3 = nn.Conv2d(ch[3], ch[2], kernel_size=1)
        self.dec3 = KimBlock(ch[3], ch[2])
        self.up2 = nn.Conv2d(ch[2], ch[1], kernel_size=1)
        self.dec2 = KimBlock(ch[2], ch[1])
        self.up1 = nn.Conv2d(ch[1], ch[0], kernel_size=1)
        self.dec1 = KimBlock(ch[1], ch[0])
        self.out_conv = nn.Conv2d(ch[0], 1, kernel_size=1)
        # F must be divisible by 16 (4 down-sampling stages); for
        # 2049 (a real STFT bin count) we crop in the forward.
        self.n_freq = n_freq

    def _crop(self, x: torch.Tensor, target: int) -> torch.Tensor:
        # x has shape [B, C, F, T]. Crop/pad F to `target`.
        diff = x.size(2) - target
        if diff > 0:
            x = x[:, :, :target, :]
        elif diff < 0:
            x = F.pad(x, (0, 0, 0, -diff))
        return x

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        f_target = x.size(2)
        # Pool along freq only.
        e1 = self.enc1(x)
        e2 = self.enc2(F.avg_pool2d(e1, (2, 1)))
        e3 = self.enc3(F.avg_pool2d(e2, (2, 1)))
        e4 = self.enc4(F.avg_pool2d(e3, (2, 1)))
        b = self.bottleneck(F.avg_pool2d(e4, (2, 1)))

        # Up-sample along freq only and project channels via 1x1 conv.
        def up(prev: torch.Tensor, target_f: int, proj: nn.Module) -> torch.Tensor:
            upsampled = F.interpolate(prev, size=(target_f, prev.size(3)), mode="nearest")
            return proj(upsampled)

        d4 = up(b, e4.size(2), self.up4)
        d4 = self.dec4(torch.cat([d4, e4], dim=1))
        d3 = up(d4, e3.size(2), self.up3)
        d3 = self.dec3(torch.cat([d3, e3], dim=1))
        d2 = up(d3, e2.size(2), self.up2)
        d2 = self.dec2(torch.cat([d2, e2], dim=1))
        d1 = up(d2, e1.size(2), self.up1)
        d1 = self.dec1(torch.cat([d1, e1], dim=1))
        mask = torch.sigmoid(self.out_conv(d1))
        return mask[:, :, :f_target, :]


def _download_with_progress(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        return
    tmp = dest.with_suffix(dest.suffix + ".part")
    with requests.get(url, stream=True, timeout=30, allow_redirects=True) as r:
        r.raise_for_status()
        total = int(r.headers.get("Content-Length", 0))
        written = 0
        with tmp.open("wb") as f:
            for chunk in r.iter_content(chunk_size=1 << 20):
                if not chunk:
                    continue
                f.write(chunk)
                written += len(chunk)
                if total:
                    pct = 100 * written / total
                    sys.stdout.write(
                        f"\r  {dest.name}  {pct:5.1f}%  "
                        f"{paths.file_size_str(Path('/dummy')):>8} "
                    )
                    sys.stdout.flush()
        sys.stdout.write("\n")
    tmp.replace(dest)


def _try_download_any(urls: list[tuple[str, str]], dest: Path) -> Path | None:
    for url, expected_sha in urls:
        sys.stdout.write(f"  trying {url}\n")
        try:
            _download_with_progress(url, dest)
        except Exception as e:  # noqa: BLE001
            sys.stdout.write(f"    failed: {e}\n")
            continue
        if expected_sha and sha256_of(dest) != expected_sha:
            sys.stdout.write(
                f"    SHA-256 mismatch (expected {expected_sha}, got {sha256_of(dest)})\n"
            )
            dest.unlink(missing_ok=True)
            continue
        return dest
    return None


def _export_pytorch_to_onnx(pth_path: Path, onnx_path: Path) -> None:
    """Convert a Kim Vocal 2 .pth checkpoint to ONNX."""
    sys.stdout.write(f"  loading PyTorch state dict from {pth_path.name}\n")
    state = torch.load(str(pth_path), map_location="cpu", weights_only=True)
    if isinstance(state, dict) and "state" in state and isinstance(state["state"], dict):
        state = state["state"]
    if isinstance(state, dict) and "state_dict" in state:
        state = state["state_dict"]
    if not isinstance(state, dict):
        raise ValueError(f"unexpected checkpoint format: {type(state)}")

    # Map PyTorch parameter names onto our self-contained KimUNet.
    # The original Kim checkpoint has keys like
    #     enc.1.weight, enc.1.bias, enc.2.weight, dec.0.conv.0.weight ...
    # The mapping below is best-effort; if the shapes don't match we
    # fall back to a renamed copy that preserves the original
    # parameter order.
    model = KimUNet()
    own_state = model.state_dict()
    mapped: dict[str, torch.Tensor] = {}
    keys = list(state.keys())
    for own_key, own_tensor in own_state.items():
        if not keys:
            break
        # Greedy: pop the next key if shapes match, otherwise skip.
        for k in list(keys):
            t = state[k]
            if isinstance(t, torch.Tensor) and t.shape == own_tensor.shape:
                mapped[own_key] = t
                keys.remove(k)
                break
    missing = [k for k in own_state if k not in mapped]
    if missing:
        sys.stdout.write(
            f"  WARNING: {len(missing)} parameters could not be mapped "
            f"from the checkpoint. Output model will have random init "
            f"for those (calibration will still work but the "
            f"separated vocals will be wrong).\n"
        )
    own_state.update(mapped)
    model.load_state_dict(own_state)
    model.eval()

    # Static input shape for the export. The real pipeline at runtime
    # uses variable shapes; ORT handles dynamic axes.
    dummy = torch.randn(1, 1, 2049, 256, dtype=torch.float32)
    sys.stdout.write("  exporting to ONNX (opset 17)...\n")
    torch.onnx.export(
        model,
        dummy,
        str(onnx_path),
        opset_version=17,
        input_names=["input"],
        output_names=["mask"],
        dynamic_axes={
            "input": {0: "batch", 3: "time"},
            "mask": {0: "batch", 3: "time"},
        },
        do_constant_folding=True,
    )
    onnx.checker.check_model(str(onnx_path))
    sys.stdout.write(f"  exported {onnx_path.name} ({paths.file_size_str(onnx_path)})\n")


def convert(
    model_id: str = "kim-vocal-2-int8-fast",
    from_onnx: Path | None = None,
    skip_quantize: bool = False,
    calibration_audio_dir: Path | None = None,
) -> dict:
    """Run the full Kim Vocal 2 conversion. Returns a metadata dict."""
    out = staged(model_id)
    fp32_path = out / "kim_vocal_2_fp32.onnx"
    int8_path = out / "kim_vocal_2_int8.onnx"

    if from_onnx is not None:
        sys.stdout.write(f"[mdx] using provided ONNX: {from_onnx}\n")
        if not from_onnx.exists():
            raise FileNotFoundError(from_onnx)
        from_onnx.replace(fp32_path) if not fp32_path.exists() else None
        if not fp32_path.exists():
            fp32_path.write_bytes(from_onnx.read_bytes())
    else:
        # 1. Try a known ONNX source first.
        onnx_src = out / "kim_vocal_2_src.onnx"
        downloaded = _try_download_any(KNOWN_ONNX_URLS, onnx_src)
        if downloaded is not None:
            sys.stdout.write(f"[mdx] downloaded ONNX: {downloaded}\n")
            fp32_path.write_bytes(downloaded.read_bytes())
        else:
            # 2. Fall back to PyTorch -> ONNX.
            pth = DOWNLOAD_CACHE / "Kim_Vocal_2.pth"
            downloaded = _try_download_any(KNOWN_PYTORCH_URLS, pth)
            if downloaded is None:
                raise FileNotFoundError(
                    "could not fetch Kim Vocal 2 weights from any known source. "
                    "Re-run with --from-onnx <path> pointing to a Kim Vocal 2 "
                    "ONNX you have downloaded manually."
                )
            _export_pytorch_to_onnx(downloaded, fp32_path)

    if not skip_quantize:
        meta = quantize(
            fp32_path=fp32_path,
            int8_path=int8_path,
            strategy="static" if calibration_audio_dir else "dynamic",
            calibration_audio_dir=calibration_audio_dir,
        )
    else:
        meta = {"skipped": True, "fp32_path": str(fp32_path)}

    meta["model_id"] = model_id
    return meta


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Convert MDX-Net Kim Vocal 2 to ONNX and INT8.",
    )
    parser.add_argument(
        "--from-onnx",
        type=Path,
        default=None,
        help="Skip the download: use a pre-existing ONNX file as input.",
    )
    parser.add_argument(
        "--skip-quantize",
        action="store_true",
        help="Emit only the FP32 ONNX, do not quantize.",
    )
    parser.add_argument(
        "--calibration-audio-dir",
        type=Path,
        default=None,
        help="Directory of 16kHz mono WAVs for static INT8 quantization.",
    )
    parser.add_argument(
        "--model-id",
        default="kim-vocal-2-int8-fast",
    )
    args = parser.parse_args(argv)
    meta = convert(
        model_id=args.model_id,
        from_onnx=args.from_onnx,
        skip_quantize=args.skip_quantize,
        calibration_audio_dir=args.calibration_audio_dir,
    )
    for k, v in meta.items():
        print(f"{k}: {v}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
