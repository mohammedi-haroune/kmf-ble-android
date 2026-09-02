from __future__ import annotations

import importlib
import importlib.util
from io import StringIO

import pandas as pd


def test_groups_instantaneous_readings_by_mean_and_energy_totals_by_last() -> None:
    package_spec = importlib.util.find_spec("analytics.battery_dashboard")
    assert package_spec is not None, "battery dashboard package is missing"
    module_spec = importlib.util.find_spec("analytics.battery_dashboard.data")
    assert module_spec is not None, "battery dashboard data module is missing"
    data = importlib.import_module("analytics.battery_dashboard.data")
    source = StringIO(
        "timestamp_iso8601,timestamp_ms,voltage_v,current_a,power_w,charging,minutes_remaining,"
        "remaining_ah,capacity_ah,soc_percent,charge_kwh,discharge_kwh\n"
        "2026-09-02T08:00:00Z,1,13.0,1.0,10.0,true,60,50.0,100.0,50.0,1.0,2.0\n"
        "2026-09-02T08:30:00Z,2,13.2,3.0,20.0,true,55,51.0,100.0,51.0,1.5,2.0\n"
        "2026-09-02T09:00:00Z,3,13.4,-2.0,-15.0,false,50,50.0,100.0,50.0,1.5,2.4\n"
    )

    frame = data.load_battery_csv(source)
    grouped = data.group_samples(frame, "1h")

    assert list(grouped["power_w"]) == [15.0, -15.0]
    assert list(grouped["charge_kwh"]) == [1.5, 1.5]
    assert list(grouped["discharge_kwh"]) == [2.0, 2.4]
    assert list(grouped["charging"]) == [True, False]
    assert str(frame["timestamp"].dtype) == "datetime64[ns, UTC]"


def test_filters_by_date_time_and_charging_state() -> None:
    package_spec = importlib.util.find_spec("analytics.battery_dashboard")
    assert package_spec is not None, "battery dashboard package is missing"
    module_spec = importlib.util.find_spec("analytics.battery_dashboard.data")
    assert module_spec is not None, "battery dashboard data module is missing"
    data = importlib.import_module("analytics.battery_dashboard.data")
    frame = pd.DataFrame(
        {
            "timestamp": pd.to_datetime(
                ["2026-09-02T08:00:00Z", "2026-09-02T20:00:00Z"], utc=True
            ),
            "charging": [True, False],
            "power_w": [10.0, -15.0],
        }
    )

    filtered = data.filter_samples(
        frame,
        start_date="2026-09-02",
        end_date="2026-09-02",
        start_time="07:00",
        end_time="12:00",
        charging_states={True},
    )

    assert filtered["power_w"].tolist() == [10.0]


def test_filters_in_display_timezone_when_available() -> None:
    from analytics.battery_dashboard.data import filter_samples

    timestamps = pd.to_datetime(["2026-09-02T08:00:00Z"], utc=True)
    frame = pd.DataFrame(
        {
            "timestamp": timestamps,
            "display_timestamp": timestamps.tz_convert("Europe/Paris"),
            "charging": [True],
            "power_w": [10.0],
        }
    )

    filtered = filter_samples(
        frame,
        start_date="2026-09-02",
        end_date="2026-09-02",
        start_time="10:00",
        end_time="10:00",
        charging_states={True},
    )

    assert len(filtered) == 1


def test_loads_mixed_iso8601_precision() -> None:
    from analytics.battery_dashboard.data import load_battery_csv

    source = StringIO(
        "timestamp_iso8601,voltage_v,current_a,power_w,charging,minutes_remaining,"
        "remaining_ah,capacity_ah,soc_percent,charge_kwh,discharge_kwh\n"
        "2026-09-02T08:00:00.123Z,13.0,1.0,13.0,true,60,50,100,50,1.0,2.0\n"
        "2026-09-02T08:00:01Z,13.1,1.1,14.4,true,59,50,100,50,1.0,2.0\n"
    )

    frame = load_battery_csv(source)

    assert len(frame) == 2
    assert str(frame["timestamp"].dtype) == "datetime64[ns, UTC]"
