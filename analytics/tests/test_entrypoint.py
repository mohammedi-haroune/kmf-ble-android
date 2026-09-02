from __future__ import annotations

import importlib
import importlib.util
from pathlib import Path

from streamlit.testing.v1 import AppTest


def test_streamlit_entrypoint_exposes_dashboard_main() -> None:
    module_spec = importlib.util.find_spec("analytics.app")
    assert module_spec is not None, "Streamlit entrypoint is missing"
    entrypoint = importlib.import_module("analytics.app")

    assert callable(entrypoint.main)


def test_streamlit_entrypoint_renders_without_a_default_csv() -> None:
    entrypoint_path = Path(__file__).parents[1] / "app.py"
    app = AppTest.from_file(entrypoint_path).run(timeout=10)

    assert not app.exception
    assert app.title[0].value == "Battery history"
