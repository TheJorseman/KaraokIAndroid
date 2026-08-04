"""Calibration data reader for static INT8 quantization.

Reads a directory of 16 kHz / mono / WAV files and yields float32
tensors with the right shape for the ONNX graph. Used by
`quantize_onnx.py` when strategy='static' and the user supplies real
audio instead of the synthetic random reader.

The reader pads/truncates each audio file to a fixed number of
samples so the calibration runs are deterministic and reproducible.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import soundfile as sf
from onnxruntime.quantization import CalibrationDataReader


class AudioCalibrationReader(CalibrationDataReader):
    def __init__(
        self,
        audio_dir: Path,
        input_name: str,
        target_shape: tuple[int, ...],
        sample_rate: int = 16_000,
        max_files: int = 32,
    ):
        self._audio_dir = Path(audio_dir)
        self._input_name = input_name
        self._shape = tuple(target_shape)
        self._sample_rate = sample_rate
        self._files = sorted(
            p for p in self._audio_dir.glob("*.wav")
        )[:max_files]
        if not self._files:
            raise FileNotFoundError(f"no .wav files in {audio_dir}")
        self._index = 0

    def _load_one(self) -> dict[str, np.ndarray]:
        path = self._files[self._index]
        audio, sr = sf.read(str(path), always_2d=False)
        if audio.ndim > 1:
            audio = audio.mean(axis=1)
        if sr != self._sample_rate:
            # Crude resample; for calibration purposes this is good
            # enough. The proper resampler lives in core:media
            # (Media3) on Android.
            ratio = self._sample_rate / sr
            new_len = int(len(audio) * ratio)
            audio = np.interp(
                np.linspace(0, len(audio), new_len),
                np.arange(len(audio)),
                audio,
            ).astype("float32")
        audio = audio.astype("float32")
        # Pad / truncate to match the static input's total number of
        # elements. The reader doesn't know the semantics of the axes
        # (F vs T vs C), so it just flattens the audio and reshapes
        # back to the target tensor shape.
        target_len = int(np.prod(self._shape))
        if audio.size < target_len:
            audio = np.pad(audio, (0, target_len - audio.size))
        else:
            audio = audio[:target_len]
        return {self._input_name: audio.reshape(self._shape)}

    def get_next(self) -> dict[str, np.ndarray] | None:
        if self._index >= len(self._files):
            return None
        sample = self._load_one()
        self._index += 1
        return sample

    def rewind(self) -> None:
        self._index = 0
