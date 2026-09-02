"""Probe mirrors for a viable Fast (small, public) separation ONNX."""
from __future__ import annotations

import json
import sys
import requests

PROBES = [
    ("HF jarredou MDX23C-Karaoke", "https://huggingface.co/jarredou/MDX23C-Karaoke/resolve/main/onnx/model.onnx"),
    ("HF alphapoint melbandroformer", "https://huggingface.co/alphapoint/melbandroformer/resolve/main/model.onnx"),
    ("HF aamitave7 roformer", "https://huggingface.co/aamitave7/melbandroformer/resolve/main/onnx/model.onnx"),
    ("GH L0SG audio-sep", "https://github.com/L0SG/Roformer-SS-Android/releases/latest/download/model.onnx"),
    ("GH hyc1217 karaoke-roformer", "https://github.com/hyc1217/Karaoke-Roformer/releases/latest/download/onnx/model.onnx"),
    ("GH jakebloom melbandroformer", "https://github.com/jakebloom/melbandroformer/releases/latest/download/model.onnx"),
    ("HF mcorvo mdx23c", "https://huggingface.co/mcorvo/mdx23c/resolve/main/onnx/model.onnx"),
    ("HF stark pth23c", "https://huggingface.co/stark/MDX23C-Karaoke/resolve/main/onnx/model.onnx"),
]

def main() -> int:
    headers = {"User-Agent": "KaraokeIAndroid-probe/1.0"}
    results: list[dict] = []
    for name, url in PROBES:
        try:
            r = requests.head(url, allow_redirects=True, timeout=15, headers=headers)
            results.append({
                "name": name,
                "url": url,
                "status": r.status_code,
                "size": r.headers.get("Content-Length", "?"),
            })
        except Exception as e:
            results.append({"name": name, "url": url, "status": "ERR", "error": str(e)})
    print(json.dumps(results, indent=2))
    return 0

if __name__ == "__main__":
    sys.exit(main())
