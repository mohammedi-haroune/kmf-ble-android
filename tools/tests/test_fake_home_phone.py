"""Tests for tools/fake_home_phone.py: the Mac-side stand-in for the home phone's live feed (docs/live-feed.md)."""

from __future__ import annotations

import json
import queue
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

import pytest

TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))

import fake_home_phone as fhp  # noqa: E402  (needs the sys.path entry above)

BUNDLED_FIXTURE = TOOLS_DIR / "fixtures" / "live-session.jsonl"
READING_KEYS = [
    "voltageV",
    "currentA",
    "powerW",
    "charging",
    "minutesRemaining",
    "remainingAh",
    "capacityAh",
    "socPercent",
    "chargeKwh",
    "dischargeKwh",
]

FULL_A_CHARGING = ":A=1331,5200,1,319,152340,1800,"
FULL_A_DISCHARGING = ":A=1320,8400,0,1090,152600,1800,"
SHORT_A_CHARGING = ":A=1333,5100,1,323,1,"
SHORT_A_DISCHARGING = ":A=1305,21000,0,435,1,"
LINE_B = ":B=1,0,0,0,"
LINE_C = ":C=41200,38900,"


def frame_entry(line: str) -> fhp.FixtureEntry:
    return fhp.FixtureEntry("frame", {"line": line})


def link_entry(phase: str, error: str | None = None, next_reconnect_in_ms: int | None = None) -> fhp.FixtureEntry:
    return fhp.FixtureEntry(
        "link", {"phase": phase, "error": error, "nextReconnectInMs": next_reconnect_in_ms}
    )


def complete_session_entries() -> list[fhp.FixtureEntry]:
    return [frame_entry(FULL_A_CHARGING), frame_entry(LINE_B), frame_entry(LINE_C)]


def drain(subscription: "queue.Queue[fhp.Message]") -> list[fhp.Message]:
    messages = []
    while True:
        try:
            messages.append(subscription.get_nowait())
        except queue.Empty:
            return messages


def read_events(response, count: int) -> list[tuple[str, dict]]:
    """Reads SSE events the way docs/live-feed.md does, skipping comment lines, until [count] arrived."""
    events: list[tuple[str, dict]] = []
    name = "message"
    for raw in response:
        line = raw.decode("utf-8").rstrip("\r\n")
        if line.startswith("event: "):
            name = line[len("event: "):]
        elif line.startswith("data: "):
            events.append((name, json.loads(line[len("data: "):])))
            if len(events) == count:
                break
    return events


def start_phone(entries: list[fhp.FixtureEntry], **options) -> fhp.FakeHomePhone:
    options.setdefault("interval", 60.0)  # nothing plays on its own unless a test asks for it
    options.setdefault("log", lambda _message: None)
    phone = fhp.FakeHomePhone(entries, port=0, **options)
    phone.start()
    return phone


@pytest.fixture
def phone():
    running = start_phone(complete_session_entries() + [frame_entry(SHORT_A_CHARGING)])
    yield running
    running.stop()


def url(phone: fhp.FakeHomePhone, path: str) -> str:
    return f"http://127.0.0.1:{phone.port}{path}"


def http_status(request: urllib.request.Request) -> tuple[int, dict[str, str], bytes]:
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        with error:
            return error.code, dict(error.headers), error.read()


# --- Decoding: the rules of docs/meter-protocol-notes.md "Frame decoding reference" -----------------------------


def test_full_a_frame_decodes_signed_current_power_and_soc():
    session = fhp.Session()
    session.record(FULL_A_DISCHARGING, ms=1)

    reading = session.reading
    assert reading.voltage_v == pytest.approx(13.20)
    assert reading.current_a == pytest.approx(-8.4)
    assert reading.power_w == pytest.approx(-110.88)
    assert reading.charging is False
    assert reading.minutes_remaining == 1090
    assert reading.remaining_ah == pytest.approx(152.6)
    assert reading.capacity_ah == pytest.approx(180.0)
    assert reading.soc_percent == pytest.approx(152.6 / 180.0 * 100.0)


def test_short_a_while_charging_is_flipped_against_the_known_capacity():
    session = fhp.Session()
    session.record(FULL_A_CHARGING, ms=1)
    session.record(SHORT_A_CHARGING, ms=2)

    # Derived 5.1 A * 323 min / 60 = 27.455 Ah is the charge still missing, not the stored charge.
    assert session.reading.remaining_ah == pytest.approx(180.0 - 27.455)
    assert session.reading.soc_percent == pytest.approx((180.0 - 27.455) / 180.0 * 100.0)
    assert session.reading.capacity_ah == pytest.approx(180.0)
    assert session.reading.current_a == pytest.approx(5.1)


def test_short_a_while_discharging_uses_the_derived_charge_left():
    session = fhp.Session()
    session.record(FULL_A_DISCHARGING, ms=1)
    session.record(SHORT_A_DISCHARGING, ms=2)

    assert session.reading.remaining_ah == pytest.approx(21.0 * 435 / 60.0)
    assert session.reading.soc_percent == pytest.approx(21.0 * 435 / 60.0 / 180.0 * 100.0)
    assert session.reading.current_a == pytest.approx(-21.0)


def test_short_a_without_known_capacity_keeps_remaining_and_reports_zero_soc():
    session = fhp.Session()
    session.record(SHORT_A_CHARGING, ms=1)

    assert session.reading.remaining_ah == 0.0
    assert session.reading.soc_percent == 0.0
    assert session.reading.capacity_ah == 0.0
    assert session.reading_or_none() is None


def test_c_frame_sets_energy_totals_and_b_changes_nothing():
    session = fhp.Session()
    session.record(FULL_A_CHARGING, ms=1)
    before = session.reading
    session.record(LINE_B, ms=2)
    assert session.reading == before

    session.record(LINE_C, ms=3)
    assert session.reading.charge_kwh == pytest.approx(41.2)
    assert session.reading.discharge_kwh == pytest.approx(38.9)


def test_reading_is_none_until_a_b_and_c_were_all_seen():
    session = fhp.Session()
    assert session.reading_or_none() is None
    session.record(FULL_A_CHARGING, ms=1)
    assert session.reading_or_none() is None
    session.record(LINE_B, ms=2)
    assert session.reading_or_none() is None
    session.record(LINE_C, ms=3)
    assert session.reading_or_none() is not None


@pytest.mark.parametrize("line", [":Z=1,2,3,", ":A=1,x,3,4,", ":A=1,2,3,", "hello", ""])
def test_lines_the_parser_ignores_change_nothing(line):
    session = fhp.Session()
    session.record(FULL_A_CHARGING, ms=1)
    before = session.reading

    assert session.record(line, ms=2) is False
    assert session.reading == before
    assert session.last_lines()["A"] == FULL_A_CHARGING


def test_wire_reading_has_the_documented_fields_in_order():
    session = fhp.Session()
    for line in complete_session_entries():
        session.record(line.data["line"], ms=1)

    wire = session.reading_or_none().to_wire()
    assert list(wire) == READING_KEYS
    assert wire["voltageV"] == 13.31
    assert wire["currentA"] == 5.2
    assert wire["powerW"] == 69.212
    assert wire["charging"] is True
    assert wire["minutesRemaining"] == 319
    assert wire["remainingAh"] == 152.34
    assert wire["capacityAh"] == 180.0
    assert wire["chargeKwh"] == 41.2
    assert wire["dischargeKwh"] == 38.9


def test_replay_lines_are_last_full_a_then_a_newer_short_a_then_b_then_c():
    session = fhp.Session()
    assert session.replay_lines() == []
    for ms, line in enumerate([FULL_A_CHARGING, LINE_B, LINE_C], start=1):
        session.record(line, ms=ms)
    assert [replayed.text for replayed in session.replay_lines()] == [FULL_A_CHARGING, LINE_B, LINE_C]

    session.record(SHORT_A_CHARGING, ms=4)
    assert [replayed.text for replayed in session.replay_lines()] == [
        FULL_A_CHARGING,
        SHORT_A_CHARGING,
        LINE_B,
        LINE_C,
    ]
    assert session.replay_lines()[1].ms == 4


# --- The hub: what each subscriber receives ----------------------------------------------------------------------


def test_subscribe_sends_hello_then_link_then_nothing_for_an_empty_session():
    feed = fhp.LiveFeed(meter_name="KMF-BT-TEST", clock=lambda: 1_000)

    messages = drain(feed.subscribe())

    assert [message.name for message in messages] == ["hello", "link"]
    assert messages[0].data == {"v": 1, "ms": 1_000, "meter": {"name": "KMF-BT-TEST"}, "phase": "COLLECTING"}
    assert messages[1].data == {"ms": 1_000, "phase": "COLLECTING", "error": None, "nextReconnectInMs": None}


def test_subscribe_replays_the_session_with_the_current_reading_on_each_line():
    feed = fhp.LiveFeed(clock=lambda: 5_000)
    assert feed.prime(complete_session_entries() + [frame_entry(SHORT_A_CHARGING)]) == 3

    messages = drain(feed.subscribe())

    assert [message.name for message in messages] == ["hello", "link", "frame", "frame", "frame"]
    replay = [message.data for message in messages[2:]]
    assert [frame["line"] for frame in replay] == [FULL_A_CHARGING, LINE_B, LINE_C]
    assert all(frame["replay"] is True for frame in replay)
    assert all(frame["ms"] == 5_000 for frame in replay)
    assert replay[0]["reading"] == replay[1]["reading"] == replay[2]["reading"]
    assert list(replay[0]["reading"]) == READING_KEYS


def test_prime_without_a_complete_session_leaves_the_feed_empty():
    feed = fhp.LiveFeed()

    assert feed.prime([frame_entry(FULL_A_CHARGING), frame_entry(LINE_B)]) == 0

    assert [message.name for message in drain(feed.subscribe())] == ["hello", "link"]
    assert feed.now()["lines"] == {"A": None, "B": None, "C": None}


def test_live_frames_have_no_replay_key_and_a_null_reading_until_the_session_is_complete():
    feed = fhp.LiveFeed(clock=lambda: 7)
    subscription = feed.subscribe()
    drain(subscription)

    feed.play(frame_entry(FULL_A_CHARGING))
    feed.play(frame_entry(LINE_B))
    feed.play(frame_entry(LINE_C))
    frames = [message.data for message in drain(subscription)]

    assert [frame["line"] for frame in frames] == [FULL_A_CHARGING, LINE_B, LINE_C]
    assert all("replay" not in frame for frame in frames)
    assert [frame["reading"] is None for frame in frames] == [True, True, False]
    assert list(frames[2]["reading"]) == READING_KEYS


def test_ignored_lines_are_relayed_without_touching_the_session():
    feed = fhp.LiveFeed()
    subscription = feed.subscribe()
    drain(subscription)

    feed.play(frame_entry(":Z=9,9,"))

    [message] = drain(subscription)
    assert message.data["line"] == ":Z=9,9,"
    assert message.data["reading"] is None
    assert feed.now()["lines"] == {"A": None, "B": None, "C": None}


def test_link_event_is_sent_on_change_only_and_connecting_resets_the_session():
    feed = fhp.LiveFeed(clock=lambda: 9)
    feed.prime(complete_session_entries())
    subscription = feed.subscribe()
    drain(subscription)

    feed.play(link_entry("COLLECTING"))
    assert drain(subscription) == []

    feed.play(link_entry("CONNECTING"))
    [link] = drain(subscription)
    assert link.name == "link"
    assert link.data == {"ms": 9, "phase": "CONNECTING", "error": None, "nextReconnectInMs": None}

    # Like the real hub on a Connecting event: replay is empty and no reading until A, B and C are seen again.
    late = drain(feed.subscribe())
    assert [message.name for message in late] == ["hello", "link"]
    assert late[0].data["phase"] == "CONNECTING"
    feed.play(frame_entry(FULL_A_CHARGING))
    [frame] = drain(subscription)
    assert frame.data["reading"] is None

    feed.play(link_entry("COLLECTING"))
    [back] = drain(subscription)
    assert back.data["phase"] == "COLLECTING"


def test_link_next_reconnect_is_relative_to_the_viewer_clock_and_never_negative():
    now = [1_000]
    feed = fhp.LiveFeed(clock=lambda: now[0])
    feed.play(link_entry("ERROR", error="GATT 8", next_reconnect_in_ms=5_000))

    now[0] = 3_000
    link = drain(feed.subscribe())[1]
    assert link.data == {"ms": 3_000, "phase": "ERROR", "error": "GATT 8", "nextReconnectInMs": 3_000}

    now[0] = 9_000
    assert drain(feed.subscribe())[1].data["nextReconnectInMs"] == 0


def test_now_snapshot_has_the_last_line_of_each_type():
    feed = fhp.LiveFeed(meter_name="KMF-BT-TEST", clock=lambda: 42)
    feed.prime(complete_session_entries())
    feed.play(frame_entry(SHORT_A_CHARGING))

    snapshot = feed.now()

    assert list(snapshot) == ["v", "meter", "phase", "ms", "reading", "lines"]
    assert snapshot["v"] == 1
    assert snapshot["meter"] == {"name": "KMF-BT-TEST"}
    assert snapshot["phase"] == "COLLECTING"
    assert snapshot["ms"] == 42
    assert snapshot["lines"] == {"A": SHORT_A_CHARGING, "B": LINE_B, "C": LINE_C}
    assert list(snapshot["reading"]) == READING_KEYS


def test_encode_event_writes_one_line_json_in_sse_framing():
    text = fhp.encode_event("link", {"ms": 1, "phase": "COLLECTING", "error": None, "nextReconnectInMs": None})

    assert text == (
        'event: link\n'
        'data: {"ms":1,"phase":"COLLECTING","error":null,"nextReconnectInMs":null}\n'
        '\n'
    )


# --- HTTP ---------------------------------------------------------------------------------------------------------


def test_live_stream_starts_with_hello_then_link_then_replay(phone):
    with urllib.request.urlopen(url(phone, "/v1/live"), timeout=5) as response:
        assert response.status == 200
        assert response.headers["Content-Type"] == "text/event-stream"
        events = read_events(response, 5)

    names = [name for name, _ in events]
    assert names == ["hello", "link", "frame", "frame", "frame"]
    hello = events[0][1]
    assert hello["v"] == 1
    assert hello["phase"] == "COLLECTING"
    assert isinstance(hello["ms"], int)
    assert isinstance(hello["meter"]["name"], str) and hello["meter"]["name"]
    assert events[1][1]["error"] is None
    replay = [data for _, data in events[2:]]
    assert [frame["line"] for frame in replay] == [FULL_A_CHARGING, LINE_B, LINE_C]
    assert all(frame["replay"] is True for frame in replay)
    assert replay[2]["reading"] is not None


def test_live_stream_follows_the_fixture_after_the_replay(phone):
    with urllib.request.urlopen(url(phone, "/v1/live"), timeout=5) as response:
        read_events(response, 5)
        assert phone.step().data["line"] == SHORT_A_CHARGING  # the fixture entry after the primed session
        assert phone.step().data["line"] == FULL_A_CHARGING  # and then it loops
        live = read_events(response, 2)

    assert [data["line"] for _, data in live] == [SHORT_A_CHARGING, FULL_A_CHARGING]
    assert all("replay" not in data for _, data in live)
    assert live[0][1]["reading"]["remainingAh"] == pytest.approx(180.0 - 27.455)


def test_player_thread_plays_the_fixture_in_a_loop_at_the_interval():
    lines = [f":A=1331,5200,1,319,{152000 + step},1800," for step in range(3)]
    entries = [
        frame_entry(lines[0]),
        frame_entry(LINE_B),
        frame_entry(LINE_C),
        frame_entry(lines[1]),
        frame_entry(lines[2]),
    ]
    phone = start_phone(entries, interval=0.02)
    try:
        with urllib.request.urlopen(url(phone, "/v1/live"), timeout=5) as response:
            events = read_events(response, 2 + 3 + 12)
    finally:
        phone.stop()

    live = [data["line"] for name, data in events[2:] if name == "frame" and "replay" not in data]
    assert len(live) >= 8
    cycle = [entry.data["line"] for entry in entries]
    for before, after in zip(live, live[1:]):
        assert after == cycle[(cycle.index(before) + 1) % len(cycle)]


def test_now_returns_json(phone):
    status, headers, body = http_status(urllib.request.Request(url(phone, "/v1/now")))

    assert status == 200
    assert headers["Content-Type"].startswith("application/json")
    snapshot = json.loads(body)
    assert snapshot["v"] == 1
    assert snapshot["phase"] == "COLLECTING"
    assert isinstance(snapshot["ms"], int)
    assert snapshot["meter"]["name"]
    assert snapshot["lines"] == {"A": FULL_A_CHARGING, "B": LINE_B, "C": LINE_C}
    assert list(snapshot["reading"]) == READING_KEYS
    assert snapshot["reading"]["capacityAh"] == 180.0
    assert b" " not in body  # compact JSON like the home phone's


def test_now_reading_is_null_before_the_session_is_complete():
    phone = start_phone([frame_entry(SHORT_A_CHARGING)])
    try:
        status, _, body = http_status(urllib.request.Request(url(phone, "/v1/now")))
    finally:
        phone.stop()

    snapshot = json.loads(body)
    assert status == 200
    assert snapshot["reading"] is None
    assert snapshot["lines"] == {"A": None, "B": None, "C": None}


def test_query_strings_are_ignored(phone):
    status, _, body = http_status(urllib.request.Request(url(phone, "/v1/now?pretty=1#top")))

    assert status == 200
    assert json.loads(body)["v"] == 1


def test_unknown_path_is_404(phone):
    for path in ("/", "/v1", "/v2/live", "/v1/live/extra"):
        status, _, _ = http_status(urllib.request.Request(url(phone, path)))
        assert status == 404, path


@pytest.mark.parametrize("method", ["POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "PROPFIND"])
def test_non_get_is_405_with_allow_get(phone, method):
    body = b"x" if method in ("POST", "PUT", "PATCH") else None
    request = urllib.request.Request(url(phone, "/v1/now"), method=method, data=body)

    status, headers, _ = http_status(request)

    assert status == 405
    assert headers["Allow"] == "GET"


def test_drop_after_closes_the_stream_after_that_many_seconds():
    phone = start_phone(complete_session_entries(), drop_after=0.3)
    try:
        started = time.monotonic()
        with urllib.request.urlopen(url(phone, "/v1/live"), timeout=5) as response:
            read_events(response, 5)
            rest = response.read()  # blocks until the server closes the stream
        elapsed = time.monotonic() - started
    finally:
        phone.stop()

    assert rest.strip() == b""  # only the blank line that ended the last event
    assert 0.25 <= elapsed < 3.0


def test_ping_comment_is_sent_on_the_ping_interval():
    phone = start_phone(complete_session_entries(), ping_interval=0.05)
    try:
        with urllib.request.urlopen(url(phone, "/v1/live"), timeout=5) as response:
            lines = []
            for raw in response:
                lines.append(raw.decode("utf-8").rstrip("\r\n"))
                if lines[-1] == ": ping" or len(lines) > 60:
                    break
    finally:
        phone.stop()

    assert ": ping" in lines
    assert lines.index(": ping") > lines.index("event: hello")


def test_connect_and_disconnect_of_a_live_client_are_logged():
    messages: list[str] = []
    lock = threading.Lock()

    def log(message: str) -> None:
        with lock:
            messages.append(message)

    phone = start_phone(complete_session_entries(), ping_interval=0.05, log=log)
    try:
        with urllib.request.urlopen(url(phone, "/v1/live"), timeout=5) as response:
            read_events(response, 5)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            with lock:
                if any("disconnected" in message for message in messages):
                    break
            time.sleep(0.02)
    finally:
        phone.stop()

    with lock:
        connected = [message for message in messages if "connected" in message and "disconnected" not in message]
        disconnected = [message for message in messages if "disconnected" in message]
    assert len(connected) == 1
    assert len(disconnected) == 1
    assert "127.0.0.1" in connected[0]


# --- Fixture file and command line --------------------------------------------------------------------------------


def test_bundled_fixture_covers_the_documented_scenarios():
    entries = fhp.load_fixture(BUNDLED_FIXTURE)

    frames = [fhp.parse_line(entry.data["line"]) for entry in entries if entry.event == "frame"]
    a_frames = [frame for frame in frames if isinstance(frame, fhp.FrameA)]
    assert any(frame.capacity_ah > 0.0 for frame in a_frames), "no full A"
    assert any(frame.capacity_ah == 0.0 for frame in a_frames), "no short A resync"
    assert any(isinstance(frame, fhp.FrameB) for frame in frames)
    assert any(isinstance(frame, fhp.FrameC) for frame in frames)
    assert None not in frames, "every fixture line must be understood by the parser"
    assert any(frame.charging for frame in a_frames)
    assert sum(1 for frame in a_frames if not frame.charging and frame.capacity_ah > 0.0) >= 3, "no discharge stretch"
    phases = [entry.data["phase"] for entry in entries if entry.event == "link"]
    assert phases[:2] == ["CONNECTING", "COLLECTING"]


def test_bundled_fixture_primes_a_complete_session_with_a_reading_consistent_with_its_lines():
    entries = fhp.load_fixture(BUNDLED_FIXTURE)
    feed = fhp.LiveFeed(clock=lambda: 1)

    primed = feed.prime(entries)

    assert primed >= 3
    replay = [message.data for message in drain(feed.subscribe())[2:]]
    assert len(replay) == 3
    reading = replay[0]["reading"]
    assert reading is not None
    session = fhp.Session()
    for frame in replay:
        session.record(frame["line"], ms=0)
    assert reading == session.reading_or_none().to_wire()
    assert 0.0 < reading["socPercent"] <= 100.0


def test_load_fixture_reports_the_line_of_a_bad_entry(tmp_path):
    path = tmp_path / "bad.jsonl"
    path.write_text(
        '{"event": "frame", "data": {"line": ":B=1,"}}\n'
        '\n'
        '{"event": "bogus", "data": {}}\n',
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match=r"bad\.jsonl:3"):
        fhp.load_fixture(path)


@pytest.mark.parametrize(
    "text",
    [
        "not json",
        '{"event": "frame", "data": {}}',
        '{"event": "link", "data": {"phase": "NOT_A_PHASE"}}',
        '{"event": "frame"}',
        "[1, 2]",
    ],
)
def test_load_fixture_rejects_malformed_entries(tmp_path, text):
    path = tmp_path / "bad.jsonl"
    path.write_text(text + "\n", encoding="utf-8")

    with pytest.raises(ValueError):
        fhp.load_fixture(path)


def test_load_fixture_rejects_an_empty_file(tmp_path):
    path = tmp_path / "empty.jsonl"
    path.write_text("\n", encoding="utf-8")

    with pytest.raises(ValueError, match="no entries"):
        fhp.load_fixture(path)


def test_defaults_bind_to_loopback_only():
    args = fhp.parse_args([])

    assert args.bind == "127.0.0.1"
    assert args.port == 8765
    assert args.interval == 1.0
    assert args.drop_after is None
    assert Path(args.fixture) == BUNDLED_FIXTURE


def test_options_are_parsed():
    args = fhp.parse_args(
        ["--bind", "100.64.0.9", "--port", "9000", "--fixture", "x.jsonl", "--interval", "0.5", "--drop-after", "20"]
    )

    assert (args.bind, args.port, args.fixture, args.interval, args.drop_after) == (
        "100.64.0.9",
        9000,
        "x.jsonl",
        0.5,
        20.0,
    )


@pytest.mark.parametrize(
    "option", [["--interval", "0"], ["--interval", "-1"], ["--drop-after", "0"], ["--port", "70000"]]
)
def test_invalid_options_are_rejected(option, capsys):
    with pytest.raises(SystemExit) as excinfo:
        fhp.parse_args(option)

    assert excinfo.value.code == 2
    assert "error" in capsys.readouterr().err
