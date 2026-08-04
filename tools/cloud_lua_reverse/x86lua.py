from __future__ import annotations

import argparse
import collections
import json
import math
import re
from pathlib import Path


HEADER = b"\x86LUA"


def printable_strings(data: bytes, minimum: int = 4) -> list[str]:
    pattern = re.compile(rb"[\x20-\x7e]{%d,}" % minimum)
    return [match.decode("ascii") for match in pattern.findall(data)]


def entropy(data: bytes) -> float:
    if not data:
        return 0.0
    counts = collections.Counter(data)
    total = len(data)
    return -sum((count / total) * math.log2(count / total) for count in counts.values())


def probe_bytes(data: bytes) -> dict[str, object]:
    strings = printable_strings(data)
    return {
        "size": len(data),
        "isX86Lua": data.startswith(HEADER),
        "version": data[4] if len(data) > 4 and data.startswith(HEADER) else None,
        "headerHex": data[:6].hex(),
        "entropy": round(entropy(data), 6),
        "printableStringCount": len(strings),
        "printableStrings": strings[:200],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    files = [args.input] if args.input.is_file() else sorted(args.input.rglob("*.lua"))
    report = {
        str(path.relative_to(args.input) if args.input.is_dir() else path.name): probe_bytes(path.read_bytes())
        for path in files
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
