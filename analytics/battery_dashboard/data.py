from __future__ import annotations

from collections.abc import Collection
from datetime import time
from pathlib import Path
from typing import IO

import pandas as pd

CsvSource = str | Path | IO[str]

REQUIRED_COLUMNS = {
    "timestamp_iso8601",
    "voltage_v",
    "current_a",
    "power_w",
    "charging",
    "minutes_remaining",
    "remaining_ah",
    "capacity_ah",
    "soc_percent",
    "charge_kwh",
    "discharge_kwh",
}
MEAN_COLUMNS = [
    "voltage_v",
    "current_a",
    "power_w",
    "minutes_remaining",
    "remaining_ah",
    "capacity_ah",
    "soc_percent",
]
LAST_COLUMNS = ["charging", "charge_kwh", "discharge_kwh"]


def load_battery_csv(source: CsvSource) -> pd.DataFrame:
    """Load and validate the Android battery-history CSV as a UTC time series."""
    frame = pd.read_csv(source)
    missing = REQUIRED_COLUMNS.difference(frame.columns)
    if missing:
        columns = ", ".join(sorted(missing))
        raise ValueError(f"CSV is missing required columns: {columns}")

    frame["timestamp"] = pd.to_datetime(
        frame["timestamp_iso8601"], format="ISO8601", utc=True, errors="coerce"
    )
    if frame["timestamp"].isna().any():
        raise ValueError("CSV contains an invalid timestamp_iso8601 value")

    for column in MEAN_COLUMNS + ["charge_kwh", "discharge_kwh"]:
        frame[column] = pd.to_numeric(frame[column], errors="coerce")
    if frame[MEAN_COLUMNS + ["charge_kwh", "discharge_kwh"]].isna().any().any():
        raise ValueError("CSV contains an invalid numeric battery measurement")

    frame["charging"] = _parse_bool(frame["charging"])
    return frame.sort_values("timestamp", kind="stable").reset_index(drop=True)


def filter_samples(
    frame: pd.DataFrame,
    start_date: str,
    end_date: str,
    start_time: str,
    end_time: str,
    charging_states: Collection[bool],
) -> pd.DataFrame:
    """Return rows in an inclusive display date/time range and selected charge states."""
    time_column = "display_timestamp" if "display_timestamp" in frame else "timestamp"
    timezone = frame[time_column].dt.tz
    start = pd.Timestamp(start_date, tz=timezone)
    end = pd.Timestamp(end_date, tz=timezone) + pd.Timedelta(days=1)
    filtered = frame.loc[(frame[time_column] >= start) & (frame[time_column] < end)].copy()

    lower = time.fromisoformat(start_time)
    upper = time.fromisoformat(end_time)
    clock = filtered[time_column].dt.time
    if lower <= upper:
        filtered = filtered.loc[(clock >= lower) & (clock <= upper)]
    else:
        filtered = filtered.loc[(clock >= lower) | (clock <= upper)]

    return filtered.loc[filtered["charging"].isin(charging_states)].reset_index(drop=True)


def group_samples(frame: pd.DataFrame, interval: str) -> pd.DataFrame:
    """Group selected rows while preserving cumulative totals as end-of-bucket values."""
    if interval == "raw":
        return frame.copy()

    aggregation = {column: "mean" for column in MEAN_COLUMNS if column in frame}
    aggregation.update({column: "last" for column in LAST_COLUMNS if column in frame})
    grouped = (
        frame.set_index("timestamp")
        .sort_index()
        .resample(interval)
        .agg(aggregation)
        .dropna(how="all", subset=MEAN_COLUMNS)
        .reset_index()
    )
    return grouped


def summarize_samples(frame: pd.DataFrame) -> dict[str, float]:
    """Calculate period-level indicators for dashboard metric cards."""
    if frame.empty:
        return {
            "sample_count": 0,
            "soc_change": 0.0,
            "energy_in_kwh": 0.0,
            "energy_out_kwh": 0.0,
            "net_energy_kwh": 0.0,
            "average_power_w": 0.0,
            "minimum_power_w": 0.0,
            "maximum_power_w": 0.0,
            "minimum_voltage_v": 0.0,
            "maximum_voltage_v": 0.0,
        }

    first = frame.iloc[0]
    last = frame.iloc[-1]
    energy_in = float(last["charge_kwh"] - first["charge_kwh"])
    energy_out = float(last["discharge_kwh"] - first["discharge_kwh"])
    return {
        "sample_count": float(len(frame)),
        "soc_change": float(last["soc_percent"] - first["soc_percent"]),
        "energy_in_kwh": energy_in,
        "energy_out_kwh": energy_out,
        "net_energy_kwh": energy_in - energy_out,
        "average_power_w": float(frame["power_w"].mean()),
        "minimum_power_w": float(frame["power_w"].min()),
        "maximum_power_w": float(frame["power_w"].max()),
        "minimum_voltage_v": float(frame["voltage_v"].min()),
        "maximum_voltage_v": float(frame["voltage_v"].max()),
    }


def _parse_bool(values: pd.Series) -> pd.Series:
    normalized = values.astype(str).str.strip().str.lower()
    parsed = normalized.map({"true": True, "false": False})
    if parsed.isna().any():
        raise ValueError("CSV contains a charging value other than true or false")
    return parsed.astype(bool)
