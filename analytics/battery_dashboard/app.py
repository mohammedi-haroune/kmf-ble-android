from __future__ import annotations

from datetime import datetime, time
from io import BytesIO
from pathlib import Path

import streamlit as st

from .charts import (
    build_daily_summary,
    build_hourly_power_heatmap,
    build_timeseries_figure,
)
from .data import filter_samples, group_samples, load_battery_csv, summarize_samples

DEFAULT_CSV_PATH = Path.home() / "Downloads" / "battery-history.csv"
INTERVALS = {
    "Raw samples": "raw",
    "1 minute": "1min",
    "5 minutes": "5min",
    "15 minutes": "15min",
    "Hourly": "1h",
    "Daily": "1d",
}


def main() -> None:
    st.set_page_config(page_title="Battery history", page_icon="battery", layout="wide")
    st.title("Battery history")
    st.caption("Private local analysis of KMF battery-history.csv exports")

    frame = _choose_source()
    if frame is None:
        return

    frame = _with_local_display_time(frame)
    timestamp_for_controls = frame["display_timestamp"]
    with st.sidebar:
        st.header("Filter")
        dates = st.date_input(
            "Date range (local time)",
            value=(timestamp_for_controls.min().date(), timestamp_for_controls.max().date()),
        )
        if not isinstance(dates, tuple) or len(dates) != 2:
            st.info("Choose both a start and end date.")
            return
        start_time = st.time_input("Start time (local)", value=time.min)
        end_time = st.time_input("End time (local)", value=time(23, 59))
        states = st.multiselect(
            "Battery state",
            options=["Charging", "Discharging"],
            default=["Charging", "Discharging"],
        )
        interval_label = st.selectbox("Group samples", options=list(INTERVALS), index=2)

    charging_states = {name == "Charging" for name in states}
    filtered = filter_samples(
        frame,
        start_date=dates[0].isoformat(),
        end_date=dates[1].isoformat(),
        start_time=start_time.isoformat(timespec="minutes"),
        end_time=end_time.isoformat(timespec="minutes"),
        charging_states=charging_states,
    )
    if filtered.empty:
        st.warning("No battery samples match these filters.")
        return

    grouped = _with_local_display_time(group_samples(filtered, INTERVALS[interval_label]))
    _render_metrics(filtered)
    st.plotly_chart(build_timeseries_figure(grouped), width="stretch")

    left, right = st.columns((3, 2))
    with left:
        st.plotly_chart(build_hourly_power_heatmap(filtered), width="stretch")
    with right:
        st.subheader("Daily summary")
        st.dataframe(build_daily_summary(filtered), width="stretch", hide_index=True)

    st.download_button(
        "Download filtered data",
        data=grouped.to_csv(index=False).encode("utf-8"),
        file_name="battery-history-filtered.csv",
        mime="text/csv",
    )


def _choose_source():
    with st.sidebar:
        st.header("Data source")
        uploaded = st.file_uploader("Upload a battery-history.csv", type="csv")
        if uploaded is None:
            st.caption(f"Default: {DEFAULT_CSV_PATH}")
            if not DEFAULT_CSV_PATH.exists():
                st.info("Export the CSV from the Android app or upload a file to begin.")
                return None
            source = DEFAULT_CSV_PATH
        else:
            source = BytesIO(uploaded.getvalue())

    try:
        return load_battery_csv(source)
    except (OSError, ValueError) as error:
        st.error(f"Could not load battery history: {error}")
        return None


def _with_local_display_time(frame):
    local_zone = datetime.now().astimezone().tzinfo
    displayed = frame.copy()
    displayed["display_timestamp"] = displayed["timestamp"].dt.tz_convert(local_zone)
    return displayed


def _render_metrics(frame) -> None:
    summary = summarize_samples(frame)
    cards = st.columns(4)
    cards[0].metric("Samples", f"{summary['sample_count']:,.0f}")
    cards[1].metric("SoC change", f"{summary['soc_change']:+.2f}%")
    cards[2].metric("Energy in", f"{summary['energy_in_kwh']:.3f} kWh")
    cards[3].metric("Energy out", f"{summary['energy_out_kwh']:.3f} kWh")
    cards = st.columns(4)
    cards[0].metric("Average power", f"{summary['average_power_w']:.1f} W")
    cards[1].metric("Power range", f"{summary['minimum_power_w']:.1f} to {summary['maximum_power_w']:.1f} W")
    cards[2].metric("Voltage range", f"{summary['minimum_voltage_v']:.2f} to {summary['maximum_voltage_v']:.2f} V")
    cards[3].metric("Net energy", f"{summary['net_energy_kwh']:+.3f} kWh")


if __name__ == "__main__":
    main()
