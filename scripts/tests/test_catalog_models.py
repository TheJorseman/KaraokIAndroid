"""Network-backed catalog checks for the downloadable models."""
from __future__ import annotations

import json
from pathlib import Path

import pytest
import requests


CATALOG = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "models" / "catalog.json"


def _entries() -> list[dict]:
    return json.loads(CATALOG.read_text(encoding="utf-8"))[
        "entries"
    ]


def test_catalog_has_no_placeholder_urls() -> None:
    entries = _entries()
    assert all("example.invalid" not in (entry.get("url") or "") for entry in entries)
    assert all("example.invalid" not in (entry.get("sidecar_url") or "") for entry in entries)


@pytest.mark.network
def test_catalog_download_urls_are_reachable() -> None:
    """Verify every configured download URL responds without downloading GBs."""
    for entry in _entries():
        for key in ("url", "sidecar_url"):
            url = entry.get(key)
            if not url:
                continue
            response = requests.head(
                url,
                allow_redirects=True,
                timeout=30,
                headers={"User-Agent": "KaraokIAndroid-model-test/1.0"},
            )
            assert response.status_code == 200, (
                f"{entry['id']} {key} returned HTTP {response.status_code}: {url}"
            )


def test_models_with_sidecars_declare_both_paths() -> None:
    for entry in _entries():
        if entry.get("sidecar_url"):
            assert entry.get("sidecar_path"), entry["id"]
            assert entry["asset_path"].endswith(".onnx"), entry["id"]
