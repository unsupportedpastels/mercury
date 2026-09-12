#!/usr/bin/env python3
"""Synthetic disposable SessionDB contract probe; no host session/network access.

Exercise installed Mercury read adapter against real official SessionDB APIs.
This does NOT execute session.resume or a mounted client; it compares that
RPC's documented source reader with the adapter's actual dispatched payload.
Run with Hermes' venv and explicit source locations (see navigation report).
Only roles, fixture IDs, counts and source hashes are printed.
"""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--hermes-source", type=Path, required=True)
    parser.add_argument("--relay-source", type=Path, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="first-response-contract-") as directory:
        os.environ["HERMES_HOME"] = directory
        sys.path[:0] = [str(args.hermes_source), str(args.relay_source / "src")]
        from hermes_state import SessionDB
        from mercury_relay_plugin.session_reads import SessionReads

        class UnusedFolders:
            def list_folders(self, *args):
                raise AssertionError("not a folder probe")

            def create_folder(self, *args):
                raise AssertionError("not a folder probe")

        path = Path(directory) / "state.db"
        db = SessionDB(path)
        db.create_session("synthetic-root", "cli")
        for role, content in [("user", "synthetic first ask"),
                              ("assistant", "synthetic first answer")]:
            db.append_message("synthetic-root", role, content)
        db.end_session("synthetic-root", "compression")
        db.create_session("synthetic-tip", "cli", parent_session_id="synthetic-root")
        db.append_message("synthetic-tip", "user", "synthetic second ask")
        db.append_message("synthetic-tip", "assistant", "synthetic second answer")
        db.close()
        reader = SessionDB(path, read_only=True)
        resolved = reader.resolve_resume_session_id(reader.resolve_session_id("synthetic-root"))
        lineage = reader.get_messages_as_conversation(resolved, include_ancestors=True, include_row_ids=True)
        official_page = reader.get_messages(resolved, limit=100, offset=0, latest=True)
        reader.close()
        adapter = SessionReads(db_opener=lambda _: SessionDB(path, read_only=True),
                               folder_service=UnusedFolders())
        relay = asyncio.run(adapter.dispatch("relay.session.transcript", {
            "profile": "default", "session_id": "synthetic-root",
            "limit": 100, "offset": 0, "order": "latest"}))
        assert relay["messages"] == official_page
        summary = lambda rows: {"count": len(rows), "roles": [r["role"] for r in rows],
                                "ids": [r.get("id", r.get("_row_id")) for r in rows]}
        print(json.dumps({
            "synthetic": True, "real_host_data_read": False, "resolved_id": resolved,
            "resume_source_reader_not_rpc": summary(lineage),
            "official_latest_page_reader": summary(official_page),
            "installed_relay_dispatch": summary(relay["messages"]),
            "pagination": relay["pagination"],
            "sources": {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in [
                args.hermes_source / "hermes_state.py",
                args.relay_source / "src/mercury_relay_plugin/session_reads.py"]},
        }, indent=2))


if __name__ == "__main__":
    main()
