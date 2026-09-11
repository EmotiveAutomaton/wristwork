"""Append complete ntfy message envelopes to one NDJSON file per configured topic."""

import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


BASE_URL = os.environ["ARCHIVE_BASE_URL"].rstrip("/")
TOKEN = os.environ["ARCHIVE_TOKEN"].strip()
TOPICS = [topic.strip() for topic in os.environ["ARCHIVE_TOPICS"].split(",") if topic.strip()]
ARCHIVE_DIR = Path(os.environ.get("ARCHIVE_DIR", "/archive"))


def safe_name(topic: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]", "_", topic)


def load_seen(path: Path) -> set[str]:
    seen: set[str] = set()
    if not path.exists():
        return seen
    with path.open("r", encoding="utf-8", errors="replace") as stream:
        for line in stream:
            try:
                message_id = json.loads(line).get("id")
                if message_id:
                    seen.add(str(message_id))
            except (json.JSONDecodeError, AttributeError):
                continue
    return seen


def archive_topic(topic: str) -> None:
    path = ARCHIVE_DIR / f"{safe_name(topic)}.jsonl"
    seen = load_seen(path)
    quoted_topic = urllib.parse.quote(topic, safe="")
    url = f"{BASE_URL}/{quoted_topic}/json?since=all"
    while True:
        try:
            request = urllib.request.Request(url)
            request.add_header("Authorization", f"Bearer {TOKEN}")
            with urllib.request.urlopen(request, timeout=90) as response:
                for raw_line in response:
                    envelope = json.loads(raw_line)
                    if envelope.get("event") != "message":
                        continue
                    message_id = str(envelope.get("id", ""))
                    if not message_id or message_id in seen:
                        continue
                    encoded = json.dumps(envelope, ensure_ascii=False, separators=(",", ":"))
                    with path.open("a", encoding="utf-8", newline="\n") as output:
                        output.write(encoded + "\n")
                        output.flush()
                        os.fsync(output.fileno())
                    seen.add(message_id)
        except (OSError, urllib.error.URLError, json.JSONDecodeError) as error:
            print(f"archive retry for {safe_name(topic)}: {error}", file=sys.stderr, flush=True)
            time.sleep(5)


def main() -> None:
    if not TOKEN or not TOPICS:
        raise SystemExit("ARCHIVE_TOKEN and at least one ARCHIVE_TOPICS value are required")
    ARCHIVE_DIR.mkdir(parents=True, exist_ok=True)
    threads = [threading.Thread(target=archive_topic, args=(topic,), daemon=True) for topic in TOPICS]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()


if __name__ == "__main__":
    main()
