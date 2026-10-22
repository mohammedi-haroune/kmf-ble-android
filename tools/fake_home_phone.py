#!/usr/bin/env python3
"""Fake home phone: serves the Cell Watch live feed (docs/live-feed.md) from a fixture, standard library only.

Lets the "through another phone" mode of a carried phone be tested with one USB phone and this Mac:

    python3 tools/fake_home_phone.py                      # 127.0.0.1:8765, one fixture entry per second
    python3 tools/fake_home_phone.py --bind 100.64.0.9    # reachable from another device on that network
    curl -N http://127.0.0.1:8765/v1/live
    curl http://127.0.0.1:8765/v1/now

The fixture (tools/fixtures/live-session.jsonl) holds one JSON object per line, `{"event": "frame", "data":
{"line": ":A=..."}}` or `{"event": "link", "data": {"phase": ..., "error": ..., "nextReconnectInMs": ...}}`. Only
what a real meter or phone would produce is stored. Timestamps and the merged `reading` of every frame are computed
here from the lines with the decoding rules of docs/meter-protocol-notes.md, the same way the real home phone does
it, so a reading can never disagree with its line. Values in the fixture are illustrative, not meter observations.

Like the real home phone, a `link` to CONNECTING starts a new meter session, and a viewer that joins gets `hello`,
`link`, then the replay lines of the current session. Not mirrored: the cap of 4 live streams with eviction of the
oldest, and the slow-client and write-stall handling.

There is no authentication or encryption: the default bind is loopback; pass --bind to open it to a network.
"""

from __future__ import annotations

import argparse
import ipaddress
import json
import queue
import re
import sys
import threading
import time
from dataclasses import dataclass, replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable, NamedTuple, Optional, Sequence

WIRE_VERSION = 1
DEFAULT_PORT = 8765
DEFAULT_BIND = "127.0.0.1"
DEFAULT_FIXTURE = Path(__file__).resolve().parent / "fixtures" / "live-session.jsonl"
DEFAULT_METER_NAME = "KMF-BT-FAKE"
PING_INTERVAL_S = 15.0
STOP_POLL_S = 0.25
SHUTDOWN_POLL_S = 0.05
PHASES = ("STOPPED", "BLUETOOTH_OFF", "WAITING_FOR_DEVICE", "CONNECTING", "BOOTSTRAPPING", "COLLECTING", "ERROR")
EVENTS = ("frame", "link")
LOG_PREFIX = "fake home phone: "

_INTEGER = re.compile(r"[+-]?[0-9]+")


def _now_ms() -> int:
    return int(time.time() * 1000)


def _log_to_stderr(message: str) -> None:
    print(f"{time.strftime('%H:%M:%S')} {LOG_PREFIX}{message}", file=sys.stderr, flush=True)


# --- Meter line decoding (docs/meter-protocol-notes.md "Frame decoding reference") ------------------------------


@dataclass(frozen=True)
class FrameA:
    voltage_v: float
    current_a: float
    power_w: float
    charging: bool
    minutes_remaining: int
    remaining_ah: float
    capacity_ah: float
    soc_percent: float


@dataclass(frozen=True)
class FrameB:
    fields: Sequence[int]


@dataclass(frozen=True)
class FrameC:
    charge_kwh: float
    discharge_kwh: float


def _integer_fields(payload: str) -> list:
    """Comma separated integers; one non-integer field rejects the whole line (empty result)."""
    parts = [part.strip() for part in payload.split(",")]
    while parts and not parts[-1]:
        parts.pop()
    if not all(_INTEGER.fullmatch(part) for part in parts):
        return []
    return [int(part) for part in parts]


def parse_line(text: str):
    """The frame the home parser would decode from one meter line, or None when it ignores the line."""
    found = [(text.find(f"{kind}="), kind) for kind in "ABC"]
    found = [(index, kind) for index, kind in found if index >= 0]
    if not found:
        return None
    index, kind = min(found)
    fields = _integer_fields(text[index + 2:])
    if kind == "A":
        return _parse_a(fields)
    if kind == "B":
        return FrameB(fields) if len(fields) >= 1 else None
    return FrameC(fields[0] / 1000.0, fields[1] / 1000.0) if len(fields) >= 2 else None


def _parse_a(fields: list) -> Optional[FrameA]:
    full = len(fields) >= 6
    short = len(fields) >= 4 and (len(fields) == 4 or fields[4] in (0, 1))
    if not (full or short):
        return None
    voltage_raw, current_magnitude_raw = fields[0], abs(fields[1])
    charging = fields[2] == 1
    sign = 1.0 if charging else -1.0
    minutes = fields[3]
    if full:
        remaining_ah = fields[4] / 1000.0
        capacity_ah = fields[5] / 10.0
        soc_percent = min(max(remaining_ah / capacity_ah * 100.0, 0.0), 100.0) if capacity_ah > 0.0 else 0.0
    else:
        # A short frame has no stored charge: current x minutes is the time to the end of the current direction.
        remaining_ah = current_magnitude_raw / 1000.0 * minutes / 60.0
        capacity_ah = 0.0
        soc_percent = 0.0
    return FrameA(
        voltage_v=voltage_raw / 100.0,
        current_a=current_magnitude_raw / 1000.0 * sign,
        power_w=voltage_raw * current_magnitude_raw / 100000.0 * sign,
        charging=charging,
        minutes_remaining=minutes,
        remaining_ah=remaining_ah,
        capacity_ah=capacity_ah,
        soc_percent=soc_percent,
    )


@dataclass(frozen=True)
class Reading:
    """The merged reading of one session; every field starts at zero like `KmfReading()`."""

    voltage_v: float = 0.0
    current_a: float = 0.0
    power_w: float = 0.0
    charging: bool = False
    minutes_remaining: int = 0
    remaining_ah: float = 0.0
    capacity_ah: float = 0.0
    soc_percent: float = 0.0
    charge_kwh: float = 0.0
    discharge_kwh: float = 0.0

    def merged_with(self, frame) -> "Reading":
        """`KmfReadingMerger.apply`: A updates the live values, C the energy totals, B nothing."""
        if isinstance(frame, FrameA):
            full = frame.capacity_ah > 0.0
            capacity_ah = frame.capacity_ah if full else self.capacity_ah
            # A short A frame's remaining Ah is charge-to-empty when discharging but charge-to-full when
            # charging, so a charging frame is flipped against the capacity; without a capacity keep the old value.
            if full:
                remaining_ah = frame.remaining_ah
            elif capacity_ah <= 0.0:
                remaining_ah = self.remaining_ah
            elif frame.charging:
                remaining_ah = max(capacity_ah - frame.remaining_ah, 0.0)
            else:
                remaining_ah = frame.remaining_ah
            if full:
                soc_percent = frame.soc_percent
            elif capacity_ah > 0.0:
                soc_percent = min(max(remaining_ah / capacity_ah * 100.0, 0.0), 100.0)
            else:
                soc_percent = 0.0
            return replace(
                self,
                voltage_v=frame.voltage_v,
                current_a=frame.current_a,
                power_w=frame.power_w,
                charging=frame.charging,
                minutes_remaining=frame.minutes_remaining,
                remaining_ah=remaining_ah,
                capacity_ah=capacity_ah,
                soc_percent=soc_percent,
            )
        if isinstance(frame, FrameC):
            return replace(self, charge_kwh=frame.charge_kwh, discharge_kwh=frame.discharge_kwh)
        return self

    def to_wire(self) -> dict:
        """The `reading` object of docs/live-feed.md; floats are rounded so binary noise stays off the wire."""
        return {
            "voltageV": round(self.voltage_v, 6),
            "currentA": round(self.current_a, 6),
            "powerW": round(self.power_w, 6),
            "charging": self.charging,
            "minutesRemaining": self.minutes_remaining,
            "remainingAh": round(self.remaining_ah, 6),
            "capacityAh": round(self.capacity_ah, 6),
            "socPercent": round(self.soc_percent, 6),
            "chargeKwh": round(self.charge_kwh, 6),
            "dischargeKwh": round(self.discharge_kwh, 6),
        }


class ReplayLine(NamedTuple):
    text: str
    ms: int


class Session:
    """Everything known about the current meter connection; replaced as a whole on every reset."""

    def __init__(self) -> None:
        self.reading = Reading()
        self._seen = set()
        self._last_a: Optional[ReplayLine] = None
        self._last_full_a: Optional[ReplayLine] = None
        self._last_b: Optional[ReplayLine] = None
        self._last_c: Optional[ReplayLine] = None

    def record(self, text: str, ms: int) -> bool:
        """Folds one meter line in. Returns False (and changes nothing) for a line the parser ignores."""
        frame = parse_line(text)
        if frame is None:
            return False
        self.reading = self.reading.merged_with(frame)
        line = ReplayLine(text, ms)
        if isinstance(frame, FrameA):
            self._seen.add("A")
            self._last_a = line
            if frame.capacity_ah > 0.0:
                self._last_full_a = line
        elif isinstance(frame, FrameB):
            self._seen.add("B")
            self._last_b = line
        else:
            self._seen.add("C")
            self._last_c = line
        return True

    @property
    def complete(self) -> bool:
        return self._seen == {"A", "B", "C"}

    def reading_or_none(self) -> Optional[Reading]:
        """None until A, B and C were all seen: a reading built from zeros and partial frames is never sent."""
        return self.reading if self.complete else None

    def replay_lines(self) -> list:
        """What a joining viewer needs to rebuild the reading: last full A, a newer short A, then B and C."""
        newer_a = self._last_a if self._last_a is not self._last_full_a else None
        return [line for line in (self._last_full_a, newer_a, self._last_b, self._last_c) if line is not None]

    def last_lines(self) -> dict:
        return {
            "A": self._last_a.text if self._last_a else None,
            "B": self._last_b.text if self._last_b else None,
            "C": self._last_c.text if self._last_c else None,
        }


# --- Fixture --------------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class FixtureEntry:
    event: str
    data: dict


def _parse_entry(text: str) -> FixtureEntry:
    entry = json.loads(text)
    if not isinstance(entry, dict):
        raise ValueError("an entry must be a JSON object")
    event, data = entry.get("event"), entry.get("data")
    if event not in EVENTS:
        raise ValueError(f"event must be one of {', '.join(EVENTS)}, got {event!r}")
    if not isinstance(data, dict):
        raise ValueError("data must be a JSON object")
    if event == "frame":
        if not isinstance(data.get("line"), str):
            raise ValueError("frame data needs a string 'line'")
    else:
        if data.get("phase") not in PHASES:
            raise ValueError(f"link phase must be one of {', '.join(PHASES)}, got {data.get('phase')!r}")
        if not isinstance(data.get("error"), (str, type(None))):
            raise ValueError("link error must be a string or null")
        delay = data.get("nextReconnectInMs")
        if delay is not None and (isinstance(delay, bool) or not isinstance(delay, int)):
            raise ValueError("link nextReconnectInMs must be an integer or null")
    return FixtureEntry(event, data)


def load_fixture(path) -> list:
    """Reads a JSONL fixture; blank lines are skipped and a bad entry raises ValueError naming `path:line`."""
    entries = []
    with open(path, encoding="utf-8") as handle:
        for number, raw in enumerate(handle, start=1):
            text = raw.strip()
            if not text:
                continue
            try:
                entries.append(_parse_entry(text))
            except ValueError as error:
                raise ValueError(f"{path}:{number}: {error}") from None
    if not entries:
        raise ValueError(f"{path}: no entries")
    return entries


# --- The feed: what each viewer receives ---------------------------------------------------------------------------


class Message(NamedTuple):
    name: str
    data: dict


@dataclass(frozen=True)
class Link:
    phase: str = "COLLECTING"
    error: Optional[str] = None
    next_reconnect_at_ms: Optional[int] = None

    def to_message(self, ms: int) -> Message:
        relative = None if self.next_reconnect_at_ms is None else max(self.next_reconnect_at_ms - ms, 0)
        return Message("link", {"ms": ms, "phase": self.phase, "error": self.error, "nextReconnectInMs": relative})


def encode_event(name: str, data: dict) -> str:
    """One SSE event: the JSON is a single compact line, so the payload can never end the event early."""
    return f"event: {name}\ndata: {json.dumps(data, separators=(',', ':'))}\n\n"


class LiveFeed:
    """The home phone's in-memory view of the meter session, fanned out to every subscribed viewer."""

    def __init__(self, meter_name: str = DEFAULT_METER_NAME, clock: Callable[[], int] = _now_ms) -> None:
        self._meter_name = meter_name
        self._clock = clock
        self._lock = threading.Lock()
        self._subscribers: list = []
        self._link = Link()
        self._session = Session()

    def subscribe(self) -> "queue.Queue[Message]":
        """A queue that starts with hello, link and the replay frames, then receives live messages."""
        subscription: "queue.Queue[Message]" = queue.Queue()
        with self._lock:
            ms = self._clock()
            subscription.put(
                Message(
                    "hello",
                    {"v": WIRE_VERSION, "ms": ms, "meter": {"name": self._meter_name}, "phase": self._link.phase},
                )
            )
            subscription.put(self._link.to_message(ms))
            reading = self._session.reading_or_none()
            for line in self._session.replay_lines():
                subscription.put(self._frame_message(line.ms, line.text, reading, replay=True))
            self._subscribers.append(subscription)
        return subscription

    def unsubscribe(self, subscription: "queue.Queue[Message]") -> None:
        with self._lock:
            if subscription in self._subscribers:
                self._subscribers.remove(subscription)

    def play(self, entry: FixtureEntry) -> None:
        """Applies one fixture entry and sends the resulting message to every viewer."""
        with self._lock:
            message = self._apply(entry)
            if message is not None:
                for subscription in self._subscribers:
                    subscription.put(message)

    def prime(self, entries: Sequence[FixtureEntry]) -> int:
        """Applies leading entries without sending them until the session is complete (A, B and C seen), so the
        first viewer already gets a replay. Returns how many entries were applied; 0, with the feed untouched,
        when the entries never complete a session."""
        with self._lock:
            saved = (self._link, self._session)
            self._session = Session()
            for count, entry in enumerate(entries, start=1):
                self._apply(entry)
                if self._session.complete:
                    return count
            self._link, self._session = saved
            return 0

    def now(self) -> dict:
        """The `GET /v1/now` snapshot."""
        with self._lock:
            reading = self._session.reading_or_none()
            return {
                "v": WIRE_VERSION,
                "meter": {"name": self._meter_name},
                "phase": self._link.phase,
                "ms": self._clock(),
                "reading": reading.to_wire() if reading else None,
                "lines": self._session.last_lines(),
            }

    def _apply(self, entry: FixtureEntry) -> Optional[Message]:
        ms = self._clock()
        if entry.event == "frame":
            line = entry.data["line"]
            self._session.record(line, ms)
            return self._frame_message(ms, line, self._session.reading_or_none())
        delay = entry.data.get("nextReconnectInMs")
        link = Link(entry.data["phase"], entry.data.get("error"), None if delay is None else ms + delay)
        if link.phase == "CONNECTING":
            # The real hub starts a new session on every Connecting event, whether or not the phase changed.
            self._session = Session()
        if link == self._link:
            return None
        self._link = link
        return link.to_message(ms)

    @staticmethod
    def _frame_message(ms: int, line: str, reading: Optional[Reading], replay: bool = False) -> Message:
        data: dict = {"ms": ms}
        if replay:
            data["replay"] = True
        data["line"] = line
        data["reading"] = reading.to_wire() if reading else None
        return Message("frame", data)


# --- HTTP server ----------------------------------------------------------------------------------------------------


class _Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, phone: "FakeHomePhone") -> None:
        super().__init__(address, _Handler)
        self.phone = phone

    def handle_error(self, request, client_address) -> None:
        if isinstance(sys.exc_info()[1], ConnectionError):
            return  # a client that hung up mid-request is not worth a traceback
        super().handle_error(request, client_address)


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "FakeHomePhone/1"

    def __getattr__(self, name: str):
        # Every method but GET gets the same answer, including ones BaseHTTPRequestHandler would answer with 501.
        if name.startswith("do_"):
            return self._method_not_allowed
        raise AttributeError(name)

    def do_GET(self) -> None:
        path = self.path.split("?", 1)[0].split("#", 1)[0]
        try:
            if path == "/v1/live":
                self._serve_live()
            elif path == "/v1/now":
                self._serve_now()
            else:
                self._respond_text(404, "Not Found")
        except OSError:
            pass  # the client went away; _serve_live has already logged it

    def log_request(self, code="-", size="-") -> None:
        pass  # connects and disconnects of live clients are logged by _serve_live instead

    def log_message(self, format: str, *args) -> None:
        self.server.phone.log(f"http {self.address_string()}: {format % args}")

    def _method_not_allowed(self) -> None:
        self._respond_text(405, "Method Not Allowed", [("Allow", "GET")])

    def _respond_text(self, status: int, reason: str, headers: Sequence = ()) -> None:
        self._respond(status, "text/plain; charset=utf-8", f"{status} {reason}\n".encode("utf-8"), headers)

    def _respond(self, status: int, content_type: str, body: bytes, headers: Sequence = ()) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for name, value in headers:
            self.send_header(name, value)
        self.send_header("Connection", "close")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _serve_now(self) -> None:
        body = json.dumps(self.server.phone.feed.now(), separators=(",", ":")).encode("utf-8")
        self._respond(200, "application/json; charset=utf-8", body, [("Cache-Control", "no-store")])

    def _serve_live(self) -> None:
        phone = self.server.phone
        remote = f"{self.client_address[0]}:{self.client_address[1]}"
        subscription = phone.feed.subscribe()
        opened = time.monotonic()
        reason = "client closed"
        phone.client_opened(remote)
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            reason = self._pump(subscription, opened)
        except OSError:
            pass
        finally:
            phone.feed.unsubscribe(subscription)
            phone.client_closed(remote, time.monotonic() - opened, reason)

    def _pump(self, subscription: "queue.Queue[Message]", opened: float) -> str:
        """Writes messages and pings until the stream is dropped (--drop-after) or the server stops; returns why."""
        phone = self.server.phone
        next_ping = opened + phone.ping_interval
        while not phone.stopping.is_set():
            now = time.monotonic()
            if phone.drop_after is not None and now - opened >= phone.drop_after:
                return f"dropped after {phone.drop_after:g} s"
            if now >= next_ping:
                self.wfile.write(b": ping\n\n")
                next_ping = max(next_ping + phone.ping_interval, now)
            wait = min(next_ping - now, STOP_POLL_S)
            if phone.drop_after is not None:
                wait = min(wait, opened + phone.drop_after - now)
            try:
                message = subscription.get(timeout=max(wait, 0.0))
            except queue.Empty:
                continue
            self.wfile.write(encode_event(message.name, message.data).encode("utf-8"))
        return "server stopping"


class FakeHomePhone:
    """Feed, HTTP server and the thread that plays the fixture entries in a loop, one per `interval` seconds."""

    def __init__(
        self,
        entries: Sequence[FixtureEntry],
        bind: str = DEFAULT_BIND,
        port: int = DEFAULT_PORT,
        interval: float = 1.0,
        drop_after: Optional[float] = None,
        ping_interval: float = PING_INTERVAL_S,
        meter_name: str = DEFAULT_METER_NAME,
        clock: Callable[[], int] = _now_ms,
        log: Callable[[str], None] = _log_to_stderr,
    ) -> None:
        if not entries:
            raise ValueError("a fake home phone needs at least one fixture entry")
        self.entries = list(entries)
        self.bind = bind
        self.interval = interval
        self.drop_after = drop_after
        self.ping_interval = ping_interval
        self.log = log
        self.feed = LiveFeed(meter_name, clock)
        self.stopping = threading.Event()
        self._requested_port = port
        self._cursor = self.feed.prime(self.entries) % len(self.entries)
        self._step_lock = threading.Lock()
        self._open_streams = 0
        self._streams_lock = threading.Lock()
        self._server: Optional[_Server] = None
        self._threads: list = []

    @property
    def port(self) -> int:
        """The port actually listening (so port 0 works), valid after `start()`."""
        assert self._server is not None, "start() has not been called"
        return self._server.server_address[1]

    def start(self) -> None:
        """Binds the port (raises OSError when it is taken) and starts serving and playing in daemon threads."""
        self._server = _Server((self.bind, self._requested_port), self)
        self._threads = [
            threading.Thread(
                target=self._server.serve_forever,
                kwargs={"poll_interval": SHUTDOWN_POLL_S},
                name="fake-home-phone-http",
                daemon=True,
            ),
            threading.Thread(target=self._play_loop, name="fake-home-phone-player", daemon=True),
        ]
        for thread in self._threads:
            thread.start()

    def wait(self) -> None:
        """Blocks until `stop()`; the short timeout keeps Ctrl-C responsive."""
        while not self.stopping.wait(0.5):
            pass

    def stop(self) -> None:
        self.stopping.set()
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
        for thread in self._threads:
            thread.join(timeout=2.0)

    def step(self) -> FixtureEntry:
        """Plays the next fixture entry now (the player thread calls this once per interval)."""
        with self._step_lock:
            entry = self.entries[self._cursor]
            self._cursor = (self._cursor + 1) % len(self.entries)
        self.feed.play(entry)
        return entry

    def client_opened(self, remote: str) -> None:
        with self._streams_lock:
            self._open_streams += 1
            open_streams = self._open_streams
        self.log(f"live client connected {remote} ({open_streams} open)")

    def client_closed(self, remote: str, seconds: float, reason: str) -> None:
        with self._streams_lock:
            self._open_streams -= 1
            open_streams = self._open_streams
        self.log(f"live client disconnected {remote} after {seconds:.1f} s: {reason} ({open_streams} open)")

    def _play_loop(self) -> None:
        while not self.stopping.wait(self.interval):
            self.step()


# --- Command line ---------------------------------------------------------------------------------------------------


def _positive_float(text: str) -> float:
    try:
        value = float(text)
    except ValueError:
        raise argparse.ArgumentTypeError(f"not a number: {text!r}") from None
    if not value > 0.0:
        raise argparse.ArgumentTypeError(f"must be greater than 0, got {text}")
    return value


def _port(text: str) -> int:
    try:
        value = int(text)
    except ValueError:
        raise argparse.ArgumentTypeError(f"not a port number: {text!r}") from None
    if not 0 <= value <= 65535:
        raise argparse.ArgumentTypeError(f"must be between 0 and 65535, got {text}")
    return value


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="fake_home_phone.py",
        description="Serve the Cell Watch live feed (/v1/live, /v1/now) from a fixture, like a home phone would.",
    )
    parser.add_argument(
        "--bind", default=DEFAULT_BIND, help=f"address to listen on (default {DEFAULT_BIND}, this Mac only)"
    )
    parser.add_argument("--port", type=_port, default=DEFAULT_PORT, help=f"port to listen on (default {DEFAULT_PORT})")
    parser.add_argument(
        "--fixture", default=str(DEFAULT_FIXTURE), help="JSONL fixture to play in a loop (default: %(default)s)"
    )
    parser.add_argument(
        "--interval", type=_positive_float, default=1.0, help="seconds between fixture entries (default 1.0)"
    )
    parser.add_argument(
        "--drop-after",
        type=_positive_float,
        default=None,
        metavar="SECONDS",
        help="close every live stream this long after it opened, to exercise reconnects",
    )
    return parser.parse_args(argv)


def _is_loopback(bind: str) -> bool:
    if bind == "localhost":
        return True
    try:
        return ipaddress.ip_address(bind).is_loopback
    except ValueError:
        return False


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    try:
        entries = load_fixture(args.fixture)
    except (OSError, ValueError) as error:
        print(f"{LOG_PREFIX}cannot load the fixture: {error}", file=sys.stderr)
        return 2
    phone = FakeHomePhone(entries, args.bind, args.port, args.interval, args.drop_after)
    try:
        phone.start()
    except OSError as error:
        print(f"{LOG_PREFIX}cannot listen on {args.bind}:{args.port}: {error}", file=sys.stderr)
        return 1
    phone.log(
        f"listening on http://{args.bind}:{phone.port} "
        f"(fixture {Path(args.fixture).name}, {len(entries)} entries, {args.interval:g} s per entry)"
    )
    if not _is_loopback(args.bind):
        phone.log(f"no authentication: anything that can reach {args.bind}:{phone.port} can read this feed")
    try:
        phone.wait()
    except KeyboardInterrupt:
        pass
    finally:
        phone.stop()
        phone.log("stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
