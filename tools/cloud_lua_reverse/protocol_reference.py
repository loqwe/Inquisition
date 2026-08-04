from __future__ import annotations

import argparse
import json
from pathlib import Path


TOKENS = (
    "/heartBeat", "/getTask", "/addLog", "/uploadImage",
    "/completeTask", "/failTask", "/sanReport", "/haltComplete",
    "deviceToken", "assignmentId", "clientVersion", "accountId",
    "imageUrl", "taskType",
)


def presence_report(data: bytes) -> dict[str, bool]:
    return {token: token.encode("utf-8") in data for token in TOKENS}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(presence_report(args.input.read_bytes()), indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
