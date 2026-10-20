from __future__ import annotations

import pandas as pd
import plotly.graph_objects as go
from plotly.subplots import make_subplots


def build_timeseries_figure(frame: pd.DataFrame) -> go.Figure:
    timestamp_column = _timestamp_column(frame)
    figure = make_subplots(
        rows=4,
        cols=1,
        shared_xaxes=True,
        vertical_spacing=0.06,
        subplot_titles=(
            "State of charge (%)",
            "Voltage (V)",
            "Power (W)",
            "Energy totals (kWh)",
        ),
    )
    _line(figure, frame, timestamp_column, "soc_percent", "State of charge", 1, "#60a5fa")
    _line(figure, frame, timestamp_column, "voltage_v", "Voltage", 2, "#a78bfa")
    _line(figure, frame, timestamp_column, "power_w", "Power", 3, "#f59e0b")
    _line(figure, frame, timestamp_column, "charge_kwh", "Charged", 4, "#22c55e")
    _line(figure, frame, timestamp_column, "discharge_kwh", "Discharged", 4, "#ef4444")
    figure.update_yaxes(title_text="State of charge (%)", row=1, col=1)
    figure.update_yaxes(title_text="Voltage (V)", row=2, col=1)
    figure.update_yaxes(title_text="Power (W)", row=3, col=1)
    figure.update_yaxes(title_text="Energy totals (kWh)", row=4, col=1)
    figure.update_xaxes(
        title_text="Time (local)",
        tickformat="%d %b<br>%H:%M",
        rangeslider_visible=True,
        rangeselector={
            "buttons": [
                {"count": 6, "label": "6h", "step": "hour", "stepmode": "backward"},
                {"count": 1, "label": "1d", "step": "day", "stepmode": "backward"},
                {"count": 7, "label": "7d", "step": "day", "stepmode": "backward"},
                {"step": "all", "label": "All"},
            ]
        },
        row=4,
        col=1,
    )
    figure.update_layout(
        height=1_050,
        hovermode="x unified",
        margin={"l": 80, "r": 20, "t": 70, "b": 90},
        legend={"orientation": "h", "y": -0.12, "yanchor": "top", "x": 0.5, "xanchor": "center"},
    )
    return figure


def build_hourly_power_heatmap(frame: pd.DataFrame) -> go.Figure:
    local = frame.copy()
    timestamp_column = _timestamp_column(local)
    local["date"] = local[timestamp_column].dt.date
    local["hour"] = local[timestamp_column].dt.hour
    heatmap = local.pivot_table(index="date", columns="hour", values="power_w", aggfunc="mean")
    figure = go.Figure(
        go.Heatmap(
            z=heatmap.values,
            x=heatmap.columns,
            y=heatmap.index.astype(str),
            colorscale="RdYlGn",
            colorbar={"title": "W"},
            hovertemplate="Date %{y}<br>Hour %{x}:00<br>Average power %{z:.2f} W<extra></extra>",
        )
    )
    figure.update_layout(title="Average power by day and hour", xaxis_title="Hour of day", yaxis_title="Date")
    return figure


def build_daily_summary(frame: pd.DataFrame) -> pd.DataFrame:
    local = frame.copy()
    local["date"] = local[_timestamp_column(local)].dt.date
    return (
        local.groupby("date", as_index=False)
        .agg(
            average_power_w=("power_w", "mean"),
            minimum_soc_percent=("soc_percent", "min"),
            maximum_soc_percent=("soc_percent", "max"),
            final_charge_kwh=("charge_kwh", "last"),
            final_discharge_kwh=("discharge_kwh", "last"),
            samples=("power_w", "size"),
        )
        .sort_values("date")
    )


def _line(
    figure: go.Figure,
    frame: pd.DataFrame,
    timestamp_column: str,
    column: str,
    name: str,
    row: int,
    color: str,
) -> None:
    figure.add_trace(
        go.Scatter(
            x=frame[timestamp_column],
            y=frame[column],
            name=name,
            mode="lines",
            line={"color": color},
        ),
        row=row,
        col=1,
    )


def _timestamp_column(frame: pd.DataFrame) -> str:
    return "display_timestamp" if "display_timestamp" in frame else "timestamp"
