from __future__ import annotations

import importlib
import importlib.util

import pandas as pd


def test_builds_linked_battery_timeseries_with_range_selector() -> None:
    module_spec = importlib.util.find_spec("analytics.battery_dashboard.charts")
    assert module_spec is not None, "battery dashboard chart module is missing"
    charts = importlib.import_module("analytics.battery_dashboard.charts")
    frame = pd.DataFrame(
        {
            "timestamp": pd.to_datetime(
                ["2026-09-02T08:00:00Z", "2026-09-02T09:00:00Z"], utc=True
            ),
            "soc_percent": [50.0, 51.0],
            "voltage_v": [13.0, 13.2],
            "current_a": [1.0, 2.0],
            "power_w": [10.0, 20.0],
            "charge_kwh": [1.0, 1.5],
            "discharge_kwh": [2.0, 2.0],
        }
    )

    figure = charts.build_timeseries_figure(frame)

    assert [trace.name for trace in figure.data] == [
        "State of charge",
        "Voltage",
        "Power",
        "Charged",
        "Discharged",
    ]
    assert figure.layout.yaxis.title.text == "State of charge (%)"
    assert figure.layout.yaxis2.title.text == "Voltage (V)"
    assert figure.layout.yaxis3.title.text == "Power (W)"
    assert figure.layout.yaxis4.title.text == "Energy totals (kWh)"
    assert figure.layout.xaxis4.title.text == "Time (local)"
    assert figure.layout.xaxis4.rangeslider.visible is True
    assert figure.layout.legend.y < 0
