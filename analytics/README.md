# Battery analytics dashboard

Local Streamlit dashboard for the Android app's `battery-history.csv` export.

```bash
python3 -m venv analytics/.venv
analytics/.venv/bin/pip install -r analytics/requirements.txt
analytics/.venv/bin/streamlit run analytics/app.py --server.address 127.0.0.1

```

It loads `~/Downloads/battery-history.csv` by default. Use the sidebar upload control to choose another export without moving it. All filtering, charting, and downloads run locally on the Mac.
