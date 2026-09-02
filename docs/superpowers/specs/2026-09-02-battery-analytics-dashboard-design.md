# Battery Analytics Dashboard Design

## Goal

Provide a private, local dashboard on the Mac for analyzing exported KMF battery history CSV files.

## Scope

V1 is a Streamlit application using pandas for loading, validation, filtering, and resampling, with Plotly charts. It defaults to `~/Downloads/battery-history.csv` and also accepts an uploaded or selected CSV. It does not modify the Android app, expose a network service, or synchronize data automatically.

## Data Contract

The source is `battery-history.csv` with `timestamp_iso8601` and `timestamp_ms`, plus voltage, current, power, charging, remaining/capacity Ah, SoC, and cumulative charge/discharge kWh. The loader requires the timestamp and core state columns, converts timestamps from UTC to the machine's local timezone for display, sorts chronologically, and reports a clear error for missing required columns or invalid timestamps.

## Dashboard

- Sidebar: default-path reload, CSV upload, inclusive date range, time-of-day range, charging-state selection, and raw/1 min/5 min/15 min/hour/day grouping.
- Summary: row count, selected interval, SoC start/end/change, net energy movement, average/min/max power, voltage range, and charging/discharging durations.
- Charts: linked Plotly time-series for SoC, voltage/current/power, and cumulative charge/discharge energy. Charts retain hover detail, zoom, and range selectors.
- Daily analysis: date-by-hour power heatmap and a daily summary table.
- Export: download the currently filtered/grouped rows as CSV.

## Performance and Correctness

Read the CSV through a cache keyed by file contents or modification time. Apply filters before resampling. Average instantaneous values when grouping; use the last reading for cumulative energy totals and state flags. Streamlit and Plotly operate locally; no telemetry leaves the Mac.

## Future Integration Boundary

A later authenticated phone-to-Mac synchronization service may supply the same normalized dataframe interface. A WebView may display a Mac-hosted dashboard, but it is not a substitute for secure data synchronization and is excluded from V1.
