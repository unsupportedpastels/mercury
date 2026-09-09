#!/usr/bin/env python3
"""Minimal fake `hermes serve` backend for end-to-end app testing.

Stdlib only. Speaks just enough of the Hermes dashboard HTTP + WebSocket
JSON-RPC contract to satisfy both the Android client
(HermesConnectionClient / HermesChatGateway) and the iOS client
(HermesHTTPClient / StatusProbe / PasswordLoginClient / WsTicketClient /
ChatConnection).

Wire shapes were mined from:
  app/src/test/java/.../connection/HermesConnectionClientTest.kt
  app/src/test/java/.../gateway/HermesChatGatewayTest.kt
  app/src/main/java/.../connection/HermesConnectionClient.kt
  app/src/main/java/.../connection/NativeOAuth.kt  (password login)
  app/src/main/java/.../gateway/HermesChatGateway.kt
  ios/Mercury/MercuryKit/{Networking,Auth,Chat}/*.swift

Usage:  python3 fake_hermes.py [PORT]     (default 8787, cleartext HTTP)

Set MERCURY_E2E_VIDEO_FILE to a local synthetic MP4 to exercise authenticated
managed-video download/playback. Only the fixed fixture path is served.

Set FAKE_HERMES_SCENARIO=android-progress for saved todo_list recovery.
FAKE_HERMES_TEST_KEY enables the fake-only /__test__/progress control endpoint:
GET reads counters; POST advances once to revision 2. Both require the matching
X-Fake-Hermes-Test-Key header. App clients must never call this private route.
Use only on an isolated test network. All progress/evidence is synthetic.

Login:  any username, password "e2epass"  -> HttpOnly session cookie.
"""

import base64
import hashlib
import json
import os
import secrets
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs

PASSWORD = "e2epass"
DURABLE_SESSION_ID = "e2e-session-1"
DURABLE_SESSION_TITLE = "E2E Session"
RUNTIME_SESSION_ID = "rt-e2e-1"
SENTINEL_TEXT = "Operation interrupted: waiting for model response (2s elapsed)."
PROMPT_WAIT_SECONDS = 8.0
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
VIDEO_FIXTURE_FILE = os.environ.get("MERCURY_E2E_VIDEO_FILE")
VIDEO_MANAGED_PATH = "/tmp/mercury-test-video.mp4"

# The normal fake deliberately keeps the old interrupt-sentinel behavior. The
# startup scenario is opt-in so existing sentinel/video tests remain unchanged.
FAKE_HERMES_SCENARIO = os.environ.get("FAKE_HERMES_SCENARIO", "default")
STARTUP_SCENARIO_NAMES = {"ios-startup", "startup", "startup-flow", "startup_flow"}
DELAY_FIRST_PROMPT_ACK_ENV = "FAKE_HERMES_DELAY_FIRST_PROMPT_ACK"
DELAY_FIRST_PROMPT_ACK_SECONDS_ENV = "FAKE_HERMES_DELAY_FIRST_PROMPT_ACK_SECONDS"
DELAY_FIRST_PROMPT_ERROR_ENV = "FAKE_HERMES_DELAY_FIRST_PROMPT_ERROR"
STARTUP_DELAYED_ACK_SECONDS = 0.75
SYNTHETIC_FILESYSTEM_ROOT = "/srv/mercury-e2e"
SYNTHETIC_WORKSPACE_PATH = SYNTHETIC_FILESYSTEM_ROOT + "/workspace"
SYNTHETIC_PROFILES = ("default", "work")
WORK_DURABLE_SESSION_ID = "work-e2e-session-1"
WORK_SESSION_TITLE = "Work Session"
STARTUP_RESPONSE_TEXTS = ("First startup response", "Second startup response")


# These are deliberately in-memory objects. The startup contract never maps a
# requested path to the host filesystem and only exposes entries registered here.
_lock = threading.RLock()
SESSION_TOKENS = set()      # cookie/bearer values minted by password-login
WS_TICKETS = {}             # ticket -> expiry epoch seconds (single-use)
SYNTHETIC_DIRECTORIES = {}
SYNTHETIC_FILES = {}
SYNTHETIC_PROJECTS = {}
SYNTHETIC_ACTIVE_PROJECTS = {}
SYNTHETIC_TRANSCRIPTS = {}
PROGRESS_REVISION = 1
PROGRESS_HISTORY_READS = 0
PROGRESS_RPC_COUNTS = {}


PROGRESS_EPOCH = int(time.time()) - 60


def progress_scenario_enabled():
    return os.environ.get("FAKE_HERMES_SCENARIO", FAKE_HERMES_SCENARIO).strip().lower() == "android-progress"


def progress_result(revision):
    return {
        "todos": [
            {"id": "inspect", "content": "Inspect progress contract", "status": "completed"},
            {"id": "recover", "content": "Verify progress recovery", "status": "completed" if revision == 2 else "in_progress"},
            {"id": "install", "content": "Install test build", "status": "pending"},
        ],
        "revision": revision,
        "summary": {"total": 3, "completed": revision, "in_progress": 2 - revision, "pending": 1},
    }


def progress_tool_events(revision):
    """A partial merge request is not authoritative; only completion has all todos."""
    tool_id = f"synthetic-todo-{revision}"
    return [
        ("tool.start", {"tool_id": tool_id, "name": "todo_list", "args": {
            "merge": True, "todos": [progress_result(revision)["todos"][1]],
        }}),
        ("tool.complete", {"tool_id": tool_id, "name": "todo_list",
                           "result": json.dumps(progress_result(revision))}),
    ]


def progress_messages():
    with _lock:
        revision = PROGRESS_REVISION
    rows = []
    for number in range(1, revision + 1):
        rows.append({"role": "tool", "tool_name": "todo_list",
                     "tool_call_id": f"synthetic-todo-{number}",
                     "timestamp": PROGRESS_EPOCH + number,
                     "content": json.dumps(progress_result(number))})
    rows.append({"role": "tool", "tool_name": "terminal",
                 "tool_call_id": "synthetic-evidence-1", "timestamp": PROGRESS_EPOCH + 10,
                 "content": "Synthetic checks passed (synthetic fixture; no real checks executed)."})
    return rows


def progress_test_state():
    with _lock:
        return {"scenario": "android-progress", "synthetic": True,
                "revision": PROGRESS_REVISION, "history_reads": PROGRESS_HISTORY_READS,
                "rpc_counts": dict(PROGRESS_RPC_COUNTS)}


def startup_scenario_enabled():
    value = os.environ.get("FAKE_HERMES_SCENARIO", FAKE_HERMES_SCENARIO)
    return value.strip().lower() in STARTUP_SCENARIO_NAMES or os.environ.get("FAKE_HERMES_STARTUP") == "1"


def startup_delayed_ack_enabled():
    """Opt in to the prompt-completion-before-ACK race used by one UI test."""
    return startup_scenario_enabled() and os.environ.get(DELAY_FIRST_PROMPT_ACK_ENV) == "1"


def startup_delayed_ack_seconds():
    """Return a bounded delay so a malformed test env cannot hang the server."""
    raw = os.environ.get(DELAY_FIRST_PROMPT_ACK_SECONDS_ENV)
    try:
        requested = float(raw) if raw is not None else STARTUP_DELAYED_ACK_SECONDS
    except (TypeError, ValueError):
        requested = STARTUP_DELAYED_ACK_SECONDS
    if not 0.05 <= requested <= 2.0:
        return STARTUP_DELAYED_ACK_SECONDS
    return requested


def startup_delayed_ack_is_error():
    """Optionally deliver a fixed late RPC error instead of the ACK."""
    return startup_delayed_ack_enabled() and os.environ.get(DELAY_FIRST_PROMPT_ERROR_ENV) == "1"


def _reset_synthetic_state_locked():
    global PROGRESS_REVISION, PROGRESS_HISTORY_READS, PROGRESS_RPC_COUNTS
    PROGRESS_REVISION = 1
    PROGRESS_HISTORY_READS = 0
    PROGRESS_RPC_COUNTS = {}
    global SYNTHETIC_DIRECTORIES, SYNTHETIC_FILES
    global SYNTHETIC_PROJECTS, SYNTHETIC_ACTIVE_PROJECTS, SYNTHETIC_TRANSCRIPTS
    SYNTHETIC_DIRECTORIES = {
        SYNTHETIC_FILESYSTEM_ROOT: {SYNTHETIC_WORKSPACE_PATH},
        SYNTHETIC_WORKSPACE_PATH: {SYNTHETIC_WORKSPACE_PATH + "/existing"},
        SYNTHETIC_WORKSPACE_PATH + "/existing": set(),
    }
    SYNTHETIC_FILES = {
        SYNTHETIC_WORKSPACE_PATH + "/README.md": {
            "content": b"# Mercury startup fixture\n",
            "mime_type": "text/markdown",
        },
    }
    SYNTHETIC_PROJECTS = {
        "default": {
            "existing-project": {
                "id": "existing-project",
                "label": "Existing Project",
                "path": SYNTHETIC_WORKSPACE_PATH,
                "session_ids": [DURABLE_SESSION_ID],
            },
        },
        "work": {
            "work-project": {
                "id": "work-project",
                "label": "Work Project",
                "path": SYNTHETIC_WORKSPACE_PATH,
                "session_ids": [WORK_DURABLE_SESSION_ID],
            },
        },
    }
    SYNTHETIC_ACTIVE_PROJECTS = {
        profile: next(iter(projects), None)
        for profile, projects in SYNTHETIC_PROJECTS.items()
    }
    SYNTHETIC_TRANSCRIPTS = {
        ("default", DURABLE_SESSION_ID): [],
        ("work", WORK_DURABLE_SESSION_ID): [],
    }


def reset_synthetic_state():
    """Reset only the opt-in scenario's in-memory state for isolated tests."""
    with _lock:
        _reset_synthetic_state_locked()


def _synthetic_profile(value):
    profile = "default" if value is None else str(value).strip()
    return profile if profile in SYNTHETIC_PROFILES else None


def _safe_synthetic_path(path):
    if not isinstance(path, str) or not path or len(path) > 1_024:
        return None
    if path != SYNTHETIC_FILESYSTEM_ROOT and not path.startswith(SYNTHETIC_FILESYSTEM_ROOT + "/"):
        return None
    components = path.split("/")
    if any(not component or component in (".", "..") for component in components[1:]):
        return None
    if any("\\\\" in component or any(ord(char) < 32 for char in component) for component in components):
        return None
    return path


def _synthetic_session_row(profile, session_id):
    if profile == "work":
        title = WORK_SESSION_TITLE
    else:
        title = DURABLE_SESSION_TITLE
    with _lock:
        transcript = list(SYNTHETIC_TRANSCRIPTS.get((profile, session_id), []))
    preview = next((row.get("content", "") for row in reversed(transcript) if row.get("role") == "assistant"), "")
    return {
        "id": session_id,
        "session_key": session_id,
        "title": title,
        "preview": preview,
        "last_active": time.time(),
        "message_count": len(transcript),
        "model": "fake-model",
        "billing_provider": "fake",
        "profile": profile,
        "cwd": SYNTHETIC_WORKSPACE_PATH,
        "pinned": False,
        "archived": False,
    }


def _synthetic_project_row(profile, project):
    session_ids = project["session_ids"]
    sessions = [_synthetic_session_row(profile, session_id) for session_id in session_ids]
    return {
        "id": project["id"],
        "label": project["label"],
        "path": project["path"],
        "primary_path": project["path"],
        "is_auto": False,
        "is_no_project": False,
        "session_count": len(sessions),
        "preview_sessions": sessions[:3],
    }


def _synthetic_listing(path=None):
    requested = SYNTHETIC_FILESYSTEM_ROOT if path is None else _safe_synthetic_path(path)
    with _lock:
        if requested not in SYNTHETIC_DIRECTORIES:
            raise KeyError(requested)
        children = sorted(SYNTHETIC_DIRECTORIES[requested] | {
            file_path for file_path in SYNTHETIC_FILES if file_path.rsplit("/", 1)[0] == requested
        })
        entries = []
        for child in children:
            if child in SYNTHETIC_DIRECTORIES:
                entries.append({
                    "name": child.rsplit("/", 1)[-1],
                    "path": child,
                    "is_directory": True,
                })
            else:
                file_info = SYNTHETIC_FILES[child]
                entries.append({
                    "name": child.rsplit("/", 1)[-1],
                    "path": child,
                    "is_directory": False,
                    "size": len(file_info["content"]),
                    "mime_type": file_info["mime_type"],
                })
        parent = None if requested == SYNTHETIC_FILESYSTEM_ROOT else requested.rsplit("/", 1)[0]
        return {
            "path": requested,
            "parent": parent,
            "entries": entries,
            "root": SYNTHETIC_FILESYSTEM_ROOT,
            "locked_root": SYNTHETIC_FILESYSTEM_ROOT,
            "can_change_path": True,
        }


def _record_synthetic_turn(profile, session_id, prompt, response):
    with _lock:
        transcript = SYNTHETIC_TRANSCRIPTS.setdefault((profile, session_id), [])
        transcript.extend([
            {"role": "user", "content": prompt},
            {"role": "assistant", "content": response, "status": "completed"},
        ])


def transcript_messages(profile="default", session_id=DURABLE_SESSION_ID):
    if progress_scenario_enabled():
        return progress_messages() if profile == "default" and session_id == DURABLE_SESSION_ID else []
    messages = []
    if VIDEO_FIXTURE_FILE:
        messages.append({"role": "assistant", "content": "Video playback fixture\nMEDIA: " + VIDEO_MANAGED_PATH})
    if startup_scenario_enabled():
        with _lock:
            messages.extend(dict(row) for row in SYNTHETIC_TRANSCRIPTS.get((profile, session_id), []))
    return messages


def _synthetic_project_tree(profile):
    with _lock:
        projects = list(SYNTHETIC_PROJECTS.get(profile, {}).values())
        active_id = SYNTHETIC_ACTIVE_PROJECTS.get(profile)
        scoped_ids = [session_id for project in projects for session_id in project["session_ids"]]
    return {
        "projects": [_synthetic_project_row(profile, project) for project in projects],
        "active_id": active_id,
        "scoped_session_ids": scoped_ids,
    }


def _synthetic_create_project(params):
    profile = _synthetic_profile(params.get("profile"))
    name = params.get("name")
    folders = params.get("folders")
    primary_path = params.get("primary_path")
    if profile is None:
        return None, "profile not found"
    if not isinstance(name, str) or not name.strip() or len(name.strip()) > 160:
        return None, "project name is invalid"
    if any(ord(char) < 32 for char in name):
        return None, "project name is invalid"
    if not isinstance(folders, list) or not folders:
        return None, "project requires a folder"
    canonical_folders = []
    with _lock:
        for folder in folders:
            canonical = _safe_synthetic_path(folder)
            if canonical is None or canonical not in SYNTHETIC_DIRECTORIES:
                return None, "folder not found"
            if canonical not in canonical_folders:
                canonical_folders.append(canonical)
        primary = _safe_synthetic_path(primary_path)
        if primary is None or primary not in canonical_folders:
            return None, "primary folder is invalid"
        projects = SYNTHETIC_PROJECTS.setdefault(profile, {})
        base_id = "".join(char.lower() if char.isalnum() else "-" for char in name.strip()).strip("-") or "project"
        project_id = base_id[:128]
        suffix = 2
        while project_id in projects:
            project_id = f"{base_id[:120]}-{suffix}"
            suffix += 1
        project = {
            "id": project_id,
            "label": name.strip(),
            "path": primary,
            "session_ids": [],
        }
        projects[project_id] = project
        if params.get("use") is True:
            SYNTHETIC_ACTIVE_PROJECTS[profile] = project_id
    return _synthetic_project_row(profile, project), None


def _synthetic_project_sessions(profile, project_id):
    with _lock:
        project = SYNTHETIC_PROJECTS.get(profile, {}).get(project_id)
    if project is None:
        return None
    row = _synthetic_project_row(profile, project)
    return {"project": row, "sessions": row["preview_sessions"]}


_reset_synthetic_state_locked()

log_lock = threading.Lock()


def log(line):
    with log_lock:
        print(line, flush=True)


def http_log_summary(method, raw_path):
    """Log only the route; query values can contain single-use tickets."""
    return f"HTTP {method} {urlsplit(raw_path).path}"


def rpc_log_summary(direction, text):
    """Summarize JSON-RPC metadata without prompts, transcripts, or arguments."""
    try:
        message = json.loads(text)
    except (TypeError, ValueError):
        return f"WS {direction}: invalid JSON"
    if not isinstance(message, dict):
        return f"WS {direction}: invalid frame"
    request_id = message.get("id")
    method = message.get("method")
    if method == "event":
        params = message.get("params")
        event_type = params.get("type") if isinstance(params, dict) else None
        return f"WS {direction}: event {event_type or 'unknown'}"
    if isinstance(method, str):
        return f"WS {direction}: request {method} id={request_id}"
    if "error" in message:
        return f"WS {direction}: error response id={request_id}"
    if "result" in message:
        return f"WS {direction}: response id={request_id}"
    return f"WS {direction}: unclassified frame"


def mint_session_token():
    token = secrets.token_urlsafe(24)
    with _lock:
        SESSION_TOKENS.add(token)
    return token


def mint_ws_ticket():
    ticket = secrets.token_urlsafe(24)
    with _lock:
        WS_TICKETS[ticket] = time.time() + 60
    return ticket


def consume_ws_ticket(ticket):
    with _lock:
        expiry = WS_TICKETS.pop(ticket, None)
    return expiry is not None and expiry >= time.time()


# ---------------------------------------------------------------------------
# HTTP handler
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "fake-hermes/1.0"

    # -- plumbing -----------------------------------------------------------

    def log_message(self, fmt, *args):  # route default logging through ours
        pass

    def send_json(self, obj, status=200, extra_headers=None):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for name, value in (extra_headers or []):
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def read_body_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        if not raw:
            return {}
        try:
            return json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return {}

    def authed(self):
        auth = self.headers.get("Authorization") or ""
        if auth.startswith("Bearer "):
            token = auth[len("Bearer "):].strip()
            with _lock:
                if token in SESSION_TOKENS:
                    return True
        cookie = self.headers.get("Cookie") or ""
        for part in cookie.split(";"):
            name, _, value = part.strip().partition("=")
            if name == "hermes_session":
                with _lock:
                    if value in SESSION_TOKENS:
                        return True
        return False

    def require_auth(self):
        if self.authed():
            return True
        self.send_json({"error": "invalid session"}, status=401)
        return False

    # -- routing ------------------------------------------------------------

    def handle_progress_control(self, advance=False):
        global PROGRESS_REVISION
        if not progress_scenario_enabled():
            self.send_json({"error": "not found"}, status=404)
            return
        key = os.environ.get("FAKE_HERMES_TEST_KEY", "")
        supplied = self.headers.get("X-Fake-Hermes-Test-Key", "")
        if not key or not secrets.compare_digest(key.encode(), supplied.encode()):
            self.send_json({"error": "test control denied"}, status=403)
            return
        if advance:
            with _lock:
                PROGRESS_REVISION = 2
        self.send_json(progress_test_state())

    def do_GET(self):
        parts = urlsplit(self.path)
        path, query = parts.path, parse_qs(parts.query)
        log(http_log_summary("GET", self.path))

        if path == "/__test__/progress":
            self.handle_progress_control()
        elif path == "/api/status":
            self.send_json({
                "version": "0.20.0",
                "auth_required": True,
                "active_sessions": 0,
            })
        elif path == "/api/auth/providers":
            self.send_json({"providers": [{
                "name": "basic",
                "display_name": "Password",
                "supports_password": True,
            }]})
        elif path == "/api/auth/me":
            if self.require_auth():
                self.send_json({"user_id": "e2e-user", "provider": "basic"})
        elif path == "/api/profiles":
            if self.require_auth():
                names = SYNTHETIC_PROFILES if startup_scenario_enabled() else ("default",)
                self.send_json({"profiles": [{"name": name} for name in names]})
        elif path == "/api/profiles/sessions":
            if self.require_auth():
                requested_profile = query.get("profile", [None])[0]
                if startup_scenario_enabled():
                    profile = _synthetic_profile(requested_profile)
                    if profile is None:
                        self.send_json({"error": "profile not found"}, status=404)
                        return
                    session_id = WORK_DURABLE_SESSION_ID if profile == "work" else DURABLE_SESSION_ID
                    rows = [_synthetic_session_row(profile, session_id)]
                    self.send_json({
                        "sessions": rows,
                        "total": len(rows),
                        "limit": int(query.get("limit", ["20"])[0]),
                        "offset": int(query.get("offset", ["0"])[0]),
                    })
                else:
                    self.send_json({
                        "sessions": [{
                            "id": DURABLE_SESSION_ID,
                            "session_key": DURABLE_SESSION_ID,
                            "title": DURABLE_SESSION_TITLE,
                            "preview": "",
                            "last_active": time.time(),
                            "message_count": len(progress_messages()) if progress_scenario_enabled() else 0,
                            "model": "fake-model",
                            "billing_provider": "fake",
                            "profile": "default",
                            "cwd": "/tmp/e2e",
                            "pinned": False,
                            "archived": False,
                        }],
                        "total": 1,
                        "limit": int(query.get("limit", ["20"])[0]),
                        "offset": int(query.get("offset", ["0"])[0]),
                    })
        elif path.startswith("/api/sessions/") and path.endswith("/messages"):
            if self.require_auth():
                session_id = path.split("/")[3]
                profile = _synthetic_profile(query.get("profile", [None])[0]) or "default"
                if progress_scenario_enabled() and profile == "default" and session_id == DURABLE_SESSION_ID:
                    global PROGRESS_HISTORY_READS
                    with _lock:
                        PROGRESS_HISTORY_READS += 1
                messages = transcript_messages(profile, session_id)
                self.send_json({
                    "session_id": session_id,
                    "messages": messages,
                    "pagination": {"limit": 100, "offset": 0, "returned": len(messages)},
                })
        elif path == "/api/sessions/search":
            if not startup_scenario_enabled():
                self.send_json({"error": "not found"}, status=404)
            elif self.require_auth():
                profile = _synthetic_profile(query.get("profile", [None])[0])
                needle = (query.get("q", [""])[0] or "").strip().lower()
                if profile is None:
                    self.send_json({"error": "profile not found"}, status=404)
                    return
                session_id = WORK_DURABLE_SESSION_ID if profile == "work" else DURABLE_SESSION_ID
                row = _synthetic_session_row(profile, session_id)
                if needle and any(needle in str(row[field]).lower() for field in ("id", "title", "preview")):
                    results = [{
                        "session_id": row["id"],
                        "title": row["title"],
                        "snippet": row["preview"],
                        "role": "session",
                    }]
                else:
                    results = []
                self.send_json({"results": results})
        elif path == "/api/files":
            if not startup_scenario_enabled():
                self.send_json({"error": "not found"}, status=404)
            elif self.require_auth():
                try:
                    self.send_json(_synthetic_listing((query.get("path") or [None])[0]))
                except (KeyError, TypeError):
                    self.send_json({"error": "folder not found"}, status=404)
        elif path == "/api/files/read":
            if not startup_scenario_enabled():
                self.send_json({"error": "not found"}, status=404)
            elif self.require_auth():
                requested = _safe_synthetic_path((query.get("path") or [None])[0])
                with _lock:
                    file_info = SYNTHETIC_FILES.get(requested)
                if file_info is None:
                    self.send_json({"error": "file not found"}, status=404)
                    return
                encoded = base64.b64encode(file_info["content"]).decode("ascii")
                self.send_json({
                    "name": requested.rsplit("/", 1)[-1],
                    "path": requested,
                    "mime_type": file_info["mime_type"],
                    "size": len(file_info["content"]),
                    "data_url": f"data:{file_info['mime_type']};base64,{encoded}",
                })
        elif path == "/api/files/download":
            if not self.require_auth():
                return
            if not VIDEO_FIXTURE_FILE or query.get("path") != [VIDEO_MANAGED_PATH]:
                self.send_json({"error": "fixture not found"}, status=404)
                return
            try:
                fixture = open(VIDEO_FIXTURE_FILE, "rb")
            except OSError:
                self.send_json({"error": "fixture unavailable"}, status=404)
                return
            with fixture:
                self.send_response(200)
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Content-Length", str(os.fstat(fixture.fileno()).st_size))
                self.end_headers()
                while chunk := fixture.read(64 * 1024):
                    self.wfile.write(chunk)
        elif path == "/api/model/options":
            if self.require_auth():
                self.send_json({
                    "provider": "fake",
                    "model": "fake-model",
                    "profile": query.get("profile", ["default"])[0],
                    "providers": [{
                        "slug": "fake",
                        "name": "Fake Provider",
                        "authenticated": True,
                        "models": ["fake-model"],
                        "capabilities": {
                            "fake-model": {"fast": False, "reasoning": False},
                        },
                    }],
                })
        elif path == "/api/model/info":
            if self.require_auth():
                self.send_json({
                    "profile": query.get("profile", ["default"])[0],
                    "provider": "fake",
                    "model": "fake-model",
                    "effective_context_length": 131072,
                    "capabilities": {"fast": False, "reasoning": False},
                })
        elif path == "/api/config":
            if self.require_auth():
                self.send_json({
                    "agent": {"reasoning_effort": "medium", "reasoning_overrides": {}},
                })
        elif path == "/api/ws":
            self.handle_websocket(query)
        else:
            self.send_json({"error": "not found"}, status=404)

    def do_POST(self):
        parts = urlsplit(self.path)
        path = parts.path
        log(http_log_summary("POST", self.path))

        if path == "/__test__/progress":
            # No caller-supplied paths/content/state: this is one bounded advance.
            self.read_body_json()
            self.handle_progress_control(advance=True)
        elif path == "/auth/password-login":
            body = self.read_body_json()
            if body.get("password") == PASSWORD and str(body.get("username", "")).strip():
                token = mint_session_token()
                self.send_json(
                    {"ok": True},
                    extra_headers=[(
                        "Set-Cookie",
                        f"hermes_session={token}; Path=/; HttpOnly; SameSite=Lax",
                    )],
                )
            else:
                self.send_json({"error": "invalid credentials"}, status=401)
        elif path == "/api/auth/ws-ticket":
            if self.require_auth():
                self.send_json({"ticket": mint_ws_ticket(), "ttl_seconds": 60})
        elif path == "/api/files/mkdir":
            if not startup_scenario_enabled():
                self.send_json({"error": "not found"}, status=404)
            elif self.require_auth():
                body = self.read_body_json()
                requested = _safe_synthetic_path(body.get("path"))
                if requested is None or requested == SYNTHETIC_FILESYSTEM_ROOT:
                    self.send_json({"error": "invalid folder path"}, status=400)
                    return
                parent = requested.rsplit("/", 1)[0]
                with _lock:
                    if parent not in SYNTHETIC_DIRECTORIES:
                        self.send_json({"error": "parent folder not found"}, status=404)
                        return
                    if requested in SYNTHETIC_DIRECTORIES or requested in SYNTHETIC_FILES:
                        self.send_json({"error": "folder already exists"}, status=409)
                        return
                    SYNTHETIC_DIRECTORIES[parent].add(requested)
                    SYNTHETIC_DIRECTORIES[requested] = set()
                self.send_json({"ok": True, "path": requested})
        else:
            self.send_json({"error": "not found"}, status=404)

    def do_PUT(self):
        path = urlsplit(self.path).path
        log(http_log_summary("PUT", self.path))
        if path == "/api/config":
            if self.require_auth():
                self.read_body_json()
                self.send_json({})
        else:
            self.send_json({"error": "not found"}, status=404)

    # ------------------------------------------------------------------
    # WebSocket (RFC 6455, server side, text frames only, no extensions)
    # ------------------------------------------------------------------

    def handle_websocket(self, query):
        ticket = (query.get("ticket") or [""])[0]
        if not consume_ws_ticket(ticket):
            log("WS reject: bad/expired/reused ticket")
            self.send_json({"error": "invalid ticket"}, status=403)
            return
        key = self.headers.get("Sec-WebSocket-Key")
        upgrade = (self.headers.get("Upgrade") or "").lower()
        if upgrade != "websocket" or not key:
            self.send_json({"error": "expected websocket upgrade"}, status=400)
            return
        accept = base64.b64encode(
            hashlib.sha1((key + WS_GUID).encode("ascii")).digest()
        ).decode("ascii")
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.close_connection = True
        log("WS open")
        try:
            WsSession(self.connection, self.rfile).run()
        except (ConnectionError, OSError) as error:
            log(f"WS transport error: {error}")
        log("WS closed")

    def do_DELETE(self):
        log(http_log_summary("DELETE", self.path))
        if self.require_auth():
            self.send_json({"ok": True})

    def do_PATCH(self):
        log(http_log_summary("PATCH", self.path))
        if self.require_auth():
            body = self.read_body_json()
            self.send_json({"ok": True, "title": body.get("title"),
                            "pinned": bool(body.get("pinned"))})


class WsSession:
    """One RFC 6455 connection speaking Hermes gateway JSON-RPC."""

    def __init__(self, sock, rfile):
        self.sock = sock
        self.rfile = rfile
        self.send_lock = threading.Lock()
        self.interrupt_event = threading.Event()
        self.prompt_thread = None
        self.closed = False
        self.profile = "default"
        self.durable_session_id = DURABLE_SESSION_ID
        self.startup_turn_count = 0
        self.follow_up_received = threading.Event()
        self.first_response_settled = threading.Event()

    # -- framing ------------------------------------------------------------

    def _read_exact(self, n):
        data = self.rfile.read(n)
        if data is None or len(data) < n:
            raise ConnectionError("peer closed")
        return data

    def read_frame(self):
        head = self._read_exact(2)
        opcode = head[0] & 0x0F
        masked = bool(head[1] & 0x80)
        length = head[1] & 0x7F
        if length == 126:
            (length,) = struct.unpack(">H", self._read_exact(2))
        elif length == 127:
            (length,) = struct.unpack(">Q", self._read_exact(8))
        mask = self._read_exact(4) if masked else b""
        payload = self._read_exact(length) if length else b""
        if masked:
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        return opcode, payload

    def send_frame(self, opcode, payload=b""):
        header = bytes([0x80 | opcode])
        n = len(payload)
        if n < 126:
            header += bytes([n])
        elif n < 1 << 16:
            header += bytes([126]) + struct.pack(">H", n)
        else:
            header += bytes([127]) + struct.pack(">Q", n)
        with self.send_lock:
            if self.closed:
                return
            self.sock.sendall(header + payload)

    def send_json(self, obj):
        text = json.dumps(obj)
        log(rpc_log_summary("send", text))
        self.send_frame(0x1, text.encode("utf-8"))

    # -- main loop ----------------------------------------------------------

    def run(self):
        try:
            while True:
                opcode, payload = self.read_frame()
                if opcode == 0x8:  # close
                    log("WS recv: close")
                    try:
                        self.send_frame(0x8, payload[:2])
                    except (ConnectionError, OSError):
                        pass
                    break
                if opcode == 0x9:  # ping
                    log("WS recv: ping")
                    self.send_frame(0xA, payload)
                    continue
                if opcode == 0xA:  # pong
                    continue
                if opcode != 0x1:
                    continue
                text = payload.decode("utf-8", errors="replace")
                log(rpc_log_summary("recv", text))
                self.handle_rpc(text)
        finally:
            self.closed = True
            self.interrupt_event.set()

    # -- JSON-RPC -----------------------------------------------------------

    def respond(self, request_id, result):
        self.send_json({"jsonrpc": "2.0", "id": request_id, "result": result})

    def respond_error(self, request_id, code, message):
        self.send_json({
            "jsonrpc": "2.0", "id": request_id,
            "error": {"code": code, "message": message},
        })

    def push_event(self, event_type, payload):
        self.send_json({
            "jsonrpc": "2.0",
            "method": "event",
            "params": {
                "type": event_type,
                "session_id": RUNTIME_SESSION_ID,
                "payload": payload,
            },
        })

    def _handle_startup_rpc(self, request_id, method, params):
        if method == "profiles.list":
            self.respond(request_id, {"profiles": [{"name": name} for name in SYNTHETIC_PROFILES]})
            return True
        if method == "session.active_list":
            self.respond(request_id, {"sessions": []})
            return True
        if method == "projects.tree":
            profile = _synthetic_profile(params.get("profile"))
            if profile is None:
                self.respond_error(request_id, -32602, "profile not found")
            else:
                self.respond(request_id, _synthetic_project_tree(profile))
            return True
        if method == "projects.for_cwd":
            profile = _synthetic_profile(params.get("profile"))
            requested = _safe_synthetic_path(params.get("cwd"))
            if profile is None or requested not in SYNTHETIC_DIRECTORIES:
                self.respond_error(request_id, -32602, "host folder not found")
            else:
                self.respond(request_id, {"cwd": requested})
            return True
        if method == "projects.create":
            project, error = _synthetic_create_project(params)
            if project is None:
                self.respond_error(request_id, -32602, error or "project creation failed")
            else:
                self.respond(request_id, {"project": project})
            return True
        if method == "projects.project_sessions":
            profile = _synthetic_profile(params.get("profile"))
            project_id = params.get("project_id")
            result = _synthetic_project_sessions(profile, project_id) if profile and isinstance(project_id, str) else None
            if result is None:
                self.respond_error(request_id, -32602, "project not found")
            else:
                self.respond(request_id, result)
            return True
        if method == "projects.set_active":
            profile = _synthetic_profile(params.get("profile"))
            project_id = params.get("id")
            with _lock:
                known = profile is not None and project_id in SYNTHETIC_PROJECTS.get(profile, {})
                if known:
                    SYNTHETIC_ACTIVE_PROJECTS[profile] = project_id
            if not known:
                self.respond_error(request_id, -32602, "project not found")
            else:
                self.respond(request_id, {"active_id": project_id})
            return True
        if method == "projects.delete":
            profile = _synthetic_profile(params.get("profile"))
            project_id = params.get("id")
            with _lock:
                known = profile is not None and project_id in SYNTHETIC_PROJECTS.get(profile, {})
                if known:
                    del SYNTHETIC_PROJECTS[profile][project_id]
                    if SYNTHETIC_ACTIVE_PROJECTS.get(profile) == project_id:
                        SYNTHETIC_ACTIVE_PROJECTS[profile] = next(iter(SYNTHETIC_PROJECTS[profile]), None)
            if not known:
                self.respond_error(request_id, -32602, "project not found")
            else:
                self.respond(request_id, {})
            return True
        return False

    def handle_rpc(self, text):
        try:
            message = json.loads(text)
        except ValueError:
            return
        if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
            return
        request_id = message.get("id")
        method = message.get("method")
        params = message.get("params") or {}
        if request_id is None or not isinstance(method, str):
            return

        if progress_scenario_enabled():
            with _lock:
                PROGRESS_RPC_COUNTS[method] = PROGRESS_RPC_COUNTS.get(method, 0) + 1

        if startup_scenario_enabled() and self._handle_startup_rpc(request_id, method, params):
            return

        if method == "session.create":
            requested_profile = _synthetic_profile(params.get("profile")) if startup_scenario_enabled() else "default"
            self.profile = requested_profile or "default"
            self.durable_session_id = params.get("session_id") or (
                WORK_DURABLE_SESSION_ID if self.profile == "work" else DURABLE_SESSION_ID
            )
            self.respond(request_id, {
                "session_id": RUNTIME_SESSION_ID,
                "stored_session_id": self.durable_session_id,
            })
        elif method == "session.resume":
            requested = params.get("session_id") or DURABLE_SESSION_ID
            if startup_scenario_enabled():
                requested_profile = _synthetic_profile(params.get("profile"))
                if requested_profile is None:
                    self.respond_error(request_id, -32602, "profile not found")
                    return
                self.profile = requested_profile
                self.durable_session_id = requested
            self.respond(request_id, {
                "session_id": RUNTIME_SESSION_ID,
                "session_key": requested,
                "resumed": True,
                "messages": transcript_messages(self.profile, self.durable_session_id),
                "running": False,
                "info": {
                    "model": "fake-model",
                    "provider": "fake",
                    "reasoning_effort": "medium",
                },
            })
        elif method == "prompt.submit":
            if progress_scenario_enabled() and params.get("text") == "synthetic-reject-send":
                self.respond_error(request_id, -32001, "Synthetic prompt rejected before acceptance")
                return
            self.interrupt_event.clear()
            if startup_scenario_enabled():
                self.startup_turn_count += 1
                if self.startup_turn_count == 2:
                    self.follow_up_received.set()
                prompt = params.get("text") if isinstance(params.get("text"), str) else ""
                self.prompt_thread = threading.Thread(
                    target=self.stream_startup_prompt_events,
                    args=(self.startup_turn_count, prompt, self.profile, self.durable_session_id),
                    daemon=True,
                )
                if self.startup_turn_count == 1 and startup_delayed_ack_enabled():
                    # Deliberately exercise the real race: completion events are
                    # written before the first prompt.submit response, while the
                    # reader loop remains free to accept the next prompt.
                    self.prompt_thread.start()
                    threading.Thread(
                        target=self.send_delayed_startup_ack,
                        args=(request_id,),
                        daemon=True,
                    ).start()
                else:
                    self.respond(request_id, {"status": "streaming"})
                    self.prompt_thread.start()
            else:
                self.respond(request_id, {"status": "streaming"})
                self.prompt_thread = threading.Thread(
                    target=self.stream_prompt_events, daemon=True,
                )
                self.prompt_thread.start()
        elif method == "session.interrupt":
            self.respond(request_id, {"status": "ok"})
            self.interrupt_event.set()
        elif method == "config.set":
            self.respond(request_id, {
                "key": params.get("key"),
                "value": params.get("value"),
                "scope": "session",
                "deferred": False,
                "confirm_required": False,
            })
        elif method == "model.options":
            self.respond(request_id, {
                "provider": "fake",
                "model": "fake-model",
                "providers": [{
                    "slug": "fake",
                    "name": "Fake Provider",
                    "authenticated": True,
                    "models": ["fake-model"],
                    "capabilities": {
                        "fake-model": {"fast": False, "reasoning": False},
                    },
                }],
            })
        elif method == "complete.slash":
            self.respond(request_id, {"items": [], "replace_from": 0})
        elif method == "delegation.status":
            self.respond(request_id, {"active": [], "paused": False})
        elif method == "process.list":
            self.respond(request_id, {"processes": []})
        else:
            self.respond_error(request_id, -32601, f"Method not found: {method}")

    def send_delayed_startup_ack(self, request_id):
        try:
            # Hold until the next request, rather than assuming a UI driver
            # can type within a subsecond window. A broken client times out.
            self.follow_up_received.wait(timeout=30)
            time.sleep(startup_delayed_ack_seconds())
            if self.closed:
                return
            if startup_delayed_ack_is_error():
                self.respond_error(request_id, -32001, "synthetic delayed prompt rejection")
            else:
                self.respond(request_id, {"status": "streaming"})
            self.first_response_settled.set()
        except (ConnectionError, OSError) as error:
            log(f"WS delayed prompt response aborted: {error}")

    def stream_startup_prompt_events(self, turn_number, prompt, profile, session_id):
        try:
            if turn_number == 2 and startup_delayed_ack_enabled():
                self.first_response_settled.wait(timeout=5)
            response = STARTUP_RESPONSE_TEXTS[min(turn_number - 1, len(STARTUP_RESPONSE_TEXTS) - 1)] + ": " + prompt
            self.push_event("message.start", {})
            time.sleep(0.02)
            self.push_event("message.delta", {"text": response})
            time.sleep(0.02)
            if self.closed:
                return
            self.push_event("message.complete", {
                "text": response,
                "status": "completed",
            })
            _record_synthetic_turn(profile, session_id, prompt, response)
        except (ConnectionError, OSError) as error:
            log(f"WS prompt stream aborted: {error}")

    def stream_prompt_events(self):
        try:
            if progress_scenario_enabled():
                with _lock:
                    revision = PROGRESS_REVISION
                for event_type, payload in progress_tool_events(revision):
                    self.push_event(event_type, payload)
            self.push_event("message.start", {})
            time.sleep(0.05)
            self.push_event("message.delta", {"text": "The answer "})
            time.sleep(0.05)
            self.push_event("message.delta", {"text": "is 42"})
            interrupted = self.interrupt_event.wait(PROMPT_WAIT_SECONDS)
            if self.closed:
                return
            log("WS prompt: " + ("interrupt received" if interrupted
                                 else "wait elapsed, sending sentinel anyway"))
            self.push_event("message.complete", {
                "text": SENTINEL_TEXT,
                "status": "interrupted",
            })
        except (ConnectionError, OSError) as error:
            log(f"WS prompt stream aborted: {error}")


class SafeThreadingHTTPServer(ThreadingHTTPServer):
    """Suppress request addresses and tracebacks from expected test disconnects."""

    def handle_error(self, request, client_address):
        log("HTTP client disconnected")


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8787
    server = SafeThreadingHTTPServer(("0.0.0.0", port), Handler)
    server.daemon_threads = True
    log(f"fake-hermes listening on port {port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
