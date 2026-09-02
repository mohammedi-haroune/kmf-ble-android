"""Streamlit entrypoint for the local battery analytics dashboard."""

import sys
from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parent
if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from battery_dashboard.app import main


if __name__ == "__main__":
    main()
