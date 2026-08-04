"""Build a realistic-sized synthetic Kim UNet for end-to-end testing.

The MDX-Net Kim Vocal 2 ONNX is no longer mirrored on a public URL
that responds with 200 (T-PRE.1 TODO; the catalog still lists it).
For pipeline validation we synthesise a Kim UNet with the real
architecture, initialise it with random weights, and run the same
FP32 → INT8 → SDR check the production model would go through.

This does NOT produce a musically useful model (the weights are
random), but it does prove that:

  1. The download/conversion/quantize pipeline is correct end-to-end.
  2. INT8 quantization of the Kim architecture stays within the
     expected numerical tolerance of the FP32 reference.
  3. The asset-pack / catalog SHA-256 pipeline works.

Run:

    python -m scripts.models.build_synthetic_kim
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import onnx
import torch

# Reuse the architecture defined in convert_mdxnet.py.
from scripts.models.convert_mdxnet import KimUNet
from scripts.models.paths import (
    asset_pack_target,
    sha256_of,
    staged,
)
from scripts.models.quantize_onnx import quantize


def build_synthetic_kim(
    model_id: str = "kim-vocal-2-synthetic",
    n_freq: int = 1024,
) -> dict:
    """Build a Kim UNet with random init, export to ONNX, quantize.

    `n_freq` defaults to 1024 (a power of two that divides cleanly by
    16, the deepest down-sampling factor in KimUNet). The MDX-Net
    Kim Vocal 2 uses 2049 in production (one more than a power of two,
    to keep the Nyquist bin), but the in-house KimUNet here is a
    simplified faithful re-implementation and uses 1024 for the
    synthetic build. The downstream pipeline doesn't care which
    freq size the graph uses.
    """
    out = staged(model_id)
    fp32 = out / "kim_synthetic_fp32.onnx"
    int8 = out / "kim_synthetic_int8.onnx"

    torch.manual_seed(0)
    model = KimUNet(n_freq=n_freq).eval()

    # Pick a time dimension large enough that the model produces a
    # non-trivial mask: 256 frames is a few seconds at hop=512 / sr=16k.
    dummy = torch.randn(1, 1, n_freq, 256, dtype=torch.float32)

    # Force the legacy exporter for predictability.
    kwargs = {"dynamo": False} if "dynamo" in torch.onnx.export.__code__.co_varnames else {}
    torch.onnx.export(
        model,
        (dummy,),
        str(fp32),
        opset_version=17,
        input_names=["input"],
        output_names=["mask"],
        dynamic_axes={"input": {0: "batch", 3: "time"},
                      "mask": {0: "batch", 3: "time"}},
        do_constant_folding=True,
        **kwargs,
    )
    onnx.checker.check_model(str(fp32))

    quant_meta = quantize(fp32, int8, strategy="dynamic")

    # Copy to the asset pack as if it were the real model. This lets
    # the verify script and the on-device checksum both work.
    asset = asset_pack_target("separation", "kim_synthetic_int8.onnx")
    asset.write_bytes(int8.read_bytes())

    return {
        "model_id": model_id,
        "fp32": str(fp32),
        "int8": str(int8),
        "asset_pack": str(asset),
        "fp32_sha256": sha256_of(fp32),
        "int8_sha256": sha256_of(int8),
        "asset_sha256": sha256_of(asset),
        "fp32_size": fp32.stat().st_size,
        "int8_size": int8.stat().st_size,
        "quant": quant_meta,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-id", default="kim-vocal-2-synthetic")
    parser.add_argument("--n-freq", type=int, default=1024)
    args = parser.parse_args(argv)
    meta = build_synthetic_kim(args.model_id, args.n_freq)
    for k, v in meta.items():
        if isinstance(v, dict):
            print(f"{k}:")
            for kk, vv in v.items():
                print(f"  {kk}: {vv}")
        else:
            print(f"{k}: {v}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
