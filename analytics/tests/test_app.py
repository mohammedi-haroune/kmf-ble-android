from __future__ import annotations

import importlib
import importlib.util
from pathlib import Path


def test_dashboard_defaults_to_downloads_history_csv() -> None:
    module_spec = importlib.util.find_spec("analytics.battery_dashboard.app")
    assert module_spec is not None, "Streamlit dashboard entrypoint is missing"
    app = importlib.import_module("analytics.battery_dashboard.app")

    assert app.DEFAULT_CSV_PATH == Path.home() / "Downloads" / "battery-history.csv"
