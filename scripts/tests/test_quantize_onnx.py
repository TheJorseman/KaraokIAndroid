"""Tests for the ONNX quantization pipeline (T8.1).

These tests do NOT require a real Kim Vocal 2 checkpoint. They build
a tiny representative ONNX graph (a single Conv2D) in-process, run
both FP32 and INT8 quantizations, and assert:

  - the INT8 model is smaller than the FP32 one,
  - the INT8 model produces output close enough to the FP32 model
    (SDR above a generous threshold, since the test graph is small
    and the relative quantization error is bounded but not zero),
  - the SHA-256 of the output is deterministic across runs (so the
    catalog can pin it).

The same checks are run against the real Kim Vocal 2 model in
`test_separation_quality.py` once the full weights are available.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import pytest
import torch
import torch.nn as nn

from scripts.models.paths import sha256_of
from scripts.models.quantize_onnx import quantize


class _TinyModel(nn.Module):
    """A single Conv2D: deterministic, easy to reason about."""

    def __init__(self):
        super().__init__()
        self.conv = nn.Conv2d(1, 1, kernel_size=3, padding=1)

    def forward(self, input):  # noqa: A002 - matches input_names below
        return torch.relu(self.conv(input))


def _export_tiny_model(dest: Path) -> None:
    m = _TinyModel().eval()
    x = torch.zeros(1, 1, 16, 16, dtype=torch.float32)
    # Force the legacy TorchScript-based exporter for predictability:
    # the new dynamo-based one is finicky about input names and
    # dynamic shapes, and we don't need it for a single Conv2D.
    kwargs = {}
    if "dynamo" in torch.onnx.export.__code__.co_varnames:
        kwargs["dynamo"] = False
    torch.onnx.export(
        m,
        (x,),
        str(dest),
        opset_version=17,
        input_names=["input"],
        output_names=["output"],
        dynamic_axes={"input": {0: "batch"}, "output": {0: "batch"}},
        do_constant_folding=True,
        **kwargs,
    )
    onnx.checker.check_model(str(dest))


def test_dynamic_quantization_runs(tmp_path: Path) -> None:
    fp32 = tmp_path / "tiny_fp32.onnx"
    int8 = tmp_path / "tiny_int8_dynamic.onnx"
    _export_tiny_model(fp32)
    meta = quantize(fp32, int8, strategy="dynamic")
    assert int8.exists()
    # The INT8 model must contain the expected quantization metadata
    # fields, even when the raw model is too small for the file size
    # to shrink (which is normal for toy graphs like this one).
    assert meta["strategy"] == "dynamic"
    assert meta["output_sha256"]
    assert meta["source_sha256"]
    assert meta["output_size_bytes"] > 0


def test_static_quantization_runs(tmp_path: Path, calibration_dir: Path) -> None:
    fp32 = tmp_path / "tiny_fp32.onnx"
    int8 = tmp_path / "tiny_int8_static.onnx"
    _export_tiny_model(fp32)
    meta = quantize(
        fp32,
        int8,
        strategy="static",
        calibration_audio_dir=calibration_dir,
    )
    assert int8.exists()
    assert meta["strategy"] == "static"


def test_quantization_shrinks_realistic_model(tmp_path: Path) -> None:
    """A realistic Conv2D with enough weights to actually save space
    when quantized. We use 32 input channels and 64 output channels
    so the FP32 weight tensor alone is ~73 KB; the INT8 version is
    ~18 KB plus metadata. The test guards against the real failure
    mode (broken quantization that grows the file) without depending
    on absolute sizes, which are quantizer-version dependent.
    """
    class _BiggerModel(nn.Module):
        def __init__(self):
            super().__init__()
            self.conv = nn.Conv2d(32, 64, kernel_size=3, padding=1)

        def forward(self, input):  # noqa: A002
            return torch.relu(self.conv(input))

    m = _BiggerModel().eval()
    fp32 = tmp_path / "bigger_fp32.onnx"
    int8 = tmp_path / "bigger_int8.onnx"
    x = torch.zeros(1, 32, 16, 16, dtype=torch.float32)
    kwargs = {"dynamo": False} if "dynamo" in torch.onnx.export.__code__.co_varnames else {}
    torch.onnx.export(
        m, (x,), str(fp32), opset_version=17,
        input_names=["input"], output_names=["output"],
        dynamic_axes={"input": {0: "batch"}, "output": {0: "batch"}},
        do_constant_folding=True, **kwargs,
    )
    onnx.checker.check_model(str(fp32))
    quantize(fp32, int8, strategy="dynamic")
    assert int8.stat().st_size < fp32.stat().st_size, (
        f"INT8 ({int8.stat().st_size} B) should be smaller than "
        f"FP32 ({fp32.stat().st_size} B) for a model with real weights"
    )


def test_int8_output_is_close_to_fp32(tmp_path: Path) -> None:
    """The INT8 graph should produce output that matches the FP32
    graph within a small numerical tolerance (this is a sanity check
    on the quantization pipeline itself, not on the model)."""
    fp32 = tmp_path / "tiny_fp32.onnx"
    int8 = tmp_path / "tiny_int8.onnx"
    _export_tiny_model(fp32)
    quantize(fp32, int8, strategy="dynamic")

    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess_fp32 = ort.InferenceSession(str(fp32), sess_options=so, providers=["CPUExecutionProvider"])
    sess_int8 = ort.InferenceSession(str(int8), sess_options=so, providers=["CPUExecutionProvider"])

    rng = np.random.default_rng(0)
    x = rng.standard_normal((1, 1, 16, 16)).astype(np.float32)
    y_fp32 = sess_fp32.run(None, {"input": x})[0]
    y_int8 = sess_int8.run(None, {"input": x})[0]
    # Max abs diff is generous: a 3x3 conv on FP16-class tensors can
    # drift ~0.05 after INT8. For a meaningful separation model this
    # is checked in test_separation_quality with the SDR metric.
    max_diff = float(np.max(np.abs(y_fp32 - y_int8)))
    assert max_diff < 0.2, f"INT8 output diverges from FP32 by {max_diff}"


def test_output_sha256_is_deterministic(tmp_path: Path) -> None:
    """Two quantization runs on the same input produce byte-identical
    output. The catalog relies on this to pin checksums."""
    fp32 = tmp_path / "tiny_fp32.onnx"
    int8_a = tmp_path / "tiny_a.onnx"
    int8_b = tmp_path / "tiny_b.onnx"
    _export_tiny_model(fp32)
    quantize(fp32, int8_a, strategy="dynamic")
    quantize(fp32, int8_b, strategy="dynamic")
    assert sha256_of(int8_a) == sha256_of(int8_b)
