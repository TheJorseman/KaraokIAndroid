"""Generic ONNX quantizer.

Used by `convert_mdxnet.py` (and any future pipeline that produces an
ONNX graph) to take a full-precision graph and emit an INT8
quantized one. The output preserves the same I/O tensor names and
shapes so the consumer (ORT, our :core:ai module) doesn't need any
changes.

Two strategies are supported:

  - **dynamic**: weights are quantized ahead of time, activations are
    quantized on the fly per batch. Fastest to produce (no calibration
    set required). Good default for the first iteration.

  - **static**: a calibration set is needed. Produces a slightly
    sharper model at the cost of running the FP32 model once over a
    representative dataset. Use when the SDR delta from dynamic INT8
    to FP32 is too large (T8.1 in the plan).

This script is intentionally small and dependency-light: it depends
only on `onnxruntime.quantization` (which is bundled with
onnxruntime) and `onnx`. The output is byte-stable for a given input
+ seed so the SHA-256 in the catalog can be reproduced.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import onnx
from onnxruntime.quantization import (
    CalibrationDataReader,
    QuantType,
    quantize_dynamic,
    quantize_static,
)

from scripts.models.paths import (
    BUILD_OUTPUT,
    sha256_of,
    staged,
)


class _RandomCalibrationReader(CalibrationDataReader):
    """Fallback calibration set: random tensors in the input shape.

    Only used when the caller doesn't supply real audio. It's strictly
    worse than feeding actual audio but still gives the quantizer
    *something* to estimate activation ranges from, so the static path
    is testable without an external dataset.
    """

    def __init__(self, input_name: str, shape, num_samples: int = 16, seed: int = 0):
        import numpy as np
        self._rng = np.random.default_rng(seed)
        self._input_name = input_name
        self._shape = tuple(shape)
        self._remaining = num_samples

    def get_next(self):
        if self._remaining <= 0:
            return None
        self._remaining -= 1
        import numpy as np
        return {self._input_name: self._rng.standard_normal(self._shape).astype("float32")}

    def rewind(self):
        self._remaining = 16


def _input_meta(model_path: Path) -> tuple[str, list[int]]:
    m = onnx.load(str(model_path))
    if not m.graph.input:
        raise ValueError(f"model {model_path} has no inputs")
    first = m.graph.input[0]
    dims = []
    for d in first.type.tensor_type.shape.dim:
        if d.dim_value <= 0:
            dims.append(1)  # dynamic axis: collapse to 1 for calibration
        else:
            dims.append(d.dim_value)
    return first.name, dims


def quantize(
    fp32_path: Path,
    int8_path: Path,
    strategy: str = "dynamic",
    calibration_audio_dir: Path | None = None,
) -> dict:
    """Quantize `fp32_path` to `int8_path`. Returns a metadata dict.

    Parameters
    ----------
    fp32_path : Path
        Path to the FP32 ONNX model.
    int8_path : Path
        Destination path for the INT8 model.
    strategy : 'dynamic' | 'static'
        See module docstring.
    calibration_audio_dir : Path | None
        If strategy='static', a directory of 16 kHz / mono / WAV files
        to use as calibration set. If None, a synthetic random reader
        is used (only useful for smoke-testing the pipeline).
    """
    int8_path.parent.mkdir(parents=True, exist_ok=True)

    if strategy == "dynamic":
        quantize_dynamic(
            model_input=str(fp32_path),
            model_output=str(int8_path),
            weight_type=QuantType.QInt8,
        )
    elif strategy == "static":
        input_name, shape = _input_meta(fp32_path)
        if calibration_audio_dir is not None:
            from scripts.models._calibration import AudioCalibrationReader
            reader = AudioCalibrationReader(
                audio_dir=calibration_audio_dir,
                input_name=input_name,
                target_shape=tuple(shape),
            )
        else:
            reader = _RandomCalibrationReader(input_name, shape)
        quantize_static(
            model_input=str(fp32_path),
            model_output=str(int8_path),
            calibration_data_reader=reader,
            weight_type=QuantType.QInt8,
            activation_type=QuantType.QInt8,
        )
    else:
        raise ValueError(f"unknown strategy: {strategy!r}")

    meta = {
        "source": str(fp32_path),
        "output": str(int8_path),
        "strategy": strategy,
        "source_sha256": sha256_of(fp32_path),
        "output_sha256": sha256_of(int8_path),
        "source_size_bytes": fp32_path.stat().st_size,
        "output_size_bytes": int8_path.stat().st_size,
    }
    return meta


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Quantize an ONNX model to INT8.")
    parser.add_argument("input", type=Path, help="FP32 ONNX model")
    parser.add_argument("output", type=Path, help="Destination for INT8 model")
    parser.add_argument(
        "--strategy",
        choices=("dynamic", "static"),
        default="dynamic",
    )
    parser.add_argument(
        "--calibration-audio-dir",
        type=Path,
        default=None,
        help="Directory of 16kHz mono WAVs for static quantization",
    )
    args = parser.parse_args(argv)
    meta = quantize(args.input, args.output, args.strategy, args.calibration_audio_dir)
    for k, v in meta.items():
        print(f"{k}: {v}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
