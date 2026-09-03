"""Stage, commit and push the current fix set with a descriptive message."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

REPO = Path(r"C:\Users\migue\OneDrive\Documentos\GitHub\KaraokIAndroid")


def _git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=REPO, capture_output=True, text=True)


def main() -> int:
    _git("add", "-A")
    message = (
        "Fix MDX-Net Infinity output and add vocal RMS normalisation\n\n"
        "- `MdxNetSeparator`: force `OrtSessionFactory.Backend.CPU`.\n"
        "  XNNPACK's fused kernels overflow the UVR Karaoke 2 graph on\n"
        "  x86_64 and emit Infinity/NaN, which WavWriter clamps to\n"
        "  silence (the reported 'no lyrics / silent separation').\n"
        "- `OrtSessionFactory.createSessionOptions(env, backend)` overload\n"
        "  + `OrtSessionHandle.openFile(path, backend)` so callers can\n"
        "  pin a provider per model without touching the global toggle.\n"
        "- `MdxNetSeparator`: RMS-normalise the vocal estimate to\n"
        "  `0.7x` the mix RMS and recompute `instrumental = mix - vocals`\n"
        "  so the ~1000x unnormalised model output doesn't clip into a\n"
        "  square wave Whisper can't transcribe.\n"
        "- Add `scripts/devtools/mdxnet_realsong_host.py` to reproduce\n"
        "  the host-vs-emulator numerical difference (real 96%-dense\n"
        "  signal on host CPU vs sparse spike on Android).\n"
    )
    commit = _git("commit", "-m", message)
    print(commit.stdout, commit.stderr)
    push = _git("push", "origin", "main")
    print(push.stdout, push.stderr)
    return push.returncode


if __name__ == "__main__":
    raise SystemExit(main())
