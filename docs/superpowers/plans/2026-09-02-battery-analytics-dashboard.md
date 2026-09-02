# Battery Analytics Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local Streamlit dashboard for filtering and exploring battery-history CSV exports.

**Architecture:** A pure pandas analysis module validates, filters, resamples, and summarizes data. A Streamlit entrypoint renders that module with Plotly figures and a file-source sidebar.

**Tech Stack:** Python 3, pandas, Streamlit, Plotly, pytest.

**Spec:** `docs/superpowers/specs/2026-09-02-battery-analytics-dashboard-design.md`

## Global Constraints

- Default to `~/Downloads/battery-history.csv` and allow a CSV upload override.
- Run locally only; do not add Android, network, or synchronization changes.
- Preserve UTC source timestamps and display local time.
- Apply filters before grouping; average instantaneous values and retain the last cumulative energy values.

---

### Task 1: Data loading, filtering, grouping, and summaries

**Files:**
- Create: `analytics/battery_dashboard/data.py`
- Create: `analytics/tests/test_data.py`
- Create: `analytics/requirements.txt`

**Interfaces:**
- Produces `load_battery_csv(source) -> pandas.DataFrame`, `filter_samples(frame, filters) -> pandas.DataFrame`, `group_samples(frame, interval) -> pandas.DataFrame`, and `summarize_samples(frame) -> dict[str, float]`.

- [ ] **Step 1: Write failing loader and grouping tests**

```python
def test_hourly_grouping_averages_power_and_keeps_final_energy(sample_csv):
    frame = load_battery_csv(sample_csv)
    grouped = group_samples(frame, "1h")
    assert grouped.loc[0, "power_w"] == 15.0
    assert grouped.loc[0, "charge_kwh"] == 2.0
```

- [ ] **Step 2: Run the focused test and confirm it fails because the data module is absent.**

- [ ] **Step 3: Implement schema validation, timezone conversion, filtering, grouping, and summary calculations.**

- [ ] **Step 4: Run all data tests and confirm they pass.**

### Task 2: Streamlit dashboard

**Files:**
- Create: `analytics/battery_dashboard/app.py`
- Create: `analytics/README.md`

**Interfaces:**
- Consumes the Task 1 functions and a local path or uploaded CSV.
- Produces the local Streamlit dashboard.

- [ ] **Step 1: Write a failing dashboard smoke test that imports the app module and builds figures from a valid dataframe.**
- [ ] **Step 2: Run the smoke test and confirm it fails because the dashboard module is absent.**
- [ ] **Step 3: Implement sidebar source/filter controls, metrics, Plotly time-series, heatmap, daily summary, and filtered CSV download.**
- [ ] **Step 4: Run the tests and a `streamlit run` smoke launch.**

### Task 3: Documentation and verification

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`

- [ ] **Step 1: Document dependency installation, launch, default CSV path, and upload override.**
- [ ] **Step 2: Run pytest, syntax compilation, and a Streamlit startup check.**
- [ ] **Step 3: Commit the implementation and documentation.**
