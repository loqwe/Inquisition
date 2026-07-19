from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import zipfile
from pathlib import Path, PurePosixPath, PureWindowsPath


def digest_file(path: Path) -> dict[str, object]:
    md5 = hashlib.md5()
    sha256 = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            size += len(chunk)
            md5.update(chunk)
            sha256.update(chunk)
    return {"size": size, "md5": md5.hexdigest(), "sha256": sha256.hexdigest()}


def _validate_member(name: str) -> PurePosixPath:
    member = PurePosixPath(name.replace("\\", "/"))
    windows_member = PureWindowsPath(name)
    if member.is_absolute() or ".." in member.parts or windows_member.anchor:
        raise ValueError(f"unsafe archive member: {name}")
    return member


def _open_archive(path: Path) -> zipfile.ZipFile:
    return zipfile.ZipFile(path, metadata_encoding="utf-8")


def build_manifest(path: Path) -> dict[str, object]:
    with _open_archive(path) as package:
        entries = []
        for info in sorted(package.infolist(), key=lambda item: item.filename):
            _validate_member(info.filename)
            entries.append({
                "name": info.filename,
                "size": info.file_size,
                "compressedSize": info.compress_size,
                "crc32": f"{info.CRC:08x}",
                "timestamp": list(info.date_time),
            })
    return {"artifact": digest_file(path), "entries": entries}


def extract_member(path: Path, name: str, output_dir: Path) -> Path:
    member = _validate_member(name)
    target = output_dir.joinpath(*member.parts)
    target.parent.mkdir(parents=True, exist_ok=True)
    with _open_archive(path) as package:
        info = package.getinfo(name)
        _validate_member(info.filename)
        with package.open(info) as source, target.open("wb") as destination:
            shutil.copyfileobj(source, destination)
    return target


def main() -> None:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    manifest = commands.add_parser("manifest")
    manifest.add_argument("archive", type=Path)
    manifest.add_argument("--output", required=True, type=Path)
    extract = commands.add_parser("extract")
    extract.add_argument("archive", type=Path)
    extract.add_argument("member")
    extract.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    if args.command == "manifest":
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(build_manifest(args.archive), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    else:
        print(extract_member(args.archive, args.member, args.output_dir))


if __name__ == "__main__":
    main()
