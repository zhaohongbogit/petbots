#!/usr/bin/env python3
"""Fetch/verify pinned reference sources only. Never builds, installs or flashes."""
import argparse
import io
import json
from pathlib import Path
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parent
PINS = json.loads((ROOT / "sources.lock.json").read_text())


def git(directory, *arguments):
    return subprocess.check_output(
        ["git", "-C", str(directory), *arguments], text=True
    ).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["fetch", "verify"])
    args = parser.parse_args()
    sources = ROOT / "sources"
    sources.mkdir(exist_ok=True)
    for entry in PINS["mirrors"]:
        directory = sources / entry["directory"]
        if not directory.exists() and args.mode == "fetch":
            subprocess.run(
                ["git", "clone", "--depth", "1", "--branch", entry["tag"],
                 entry["url"], str(directory)], check=True
            )
        if not (directory / ".git").is_dir():
            raise RuntimeError(f"Missing source checkout: {directory}; run fetch")
        if git(directory, "rev-parse", "HEAD") != entry["commit"]:
            raise RuntimeError(f"Unexpected revision in {directory}; will not reset")
        if git(directory, "status", "--porcelain"):
            raise RuntimeError(f"Modified reference checkout: {directory}; will not overwrite")
        print(f"Verified {entry['directory']}: {entry['commit']}")
    for entry in PINS["snapshots"]:
        directory = sources / entry["directory"]
        mirror = sources / entry["mirror"]
        if not directory.exists() and args.mode == "fetch":
            subprocess.run(
                ["git", "-C", str(mirror), "fetch", "--depth", "1", "origin",
                 f"refs/tags/{entry['tag']}:refs/tags/{entry['tag']}"], check=True
            )
            if git(mirror, "rev-parse", f"{entry['tag']}^{{}}") != entry["commit"]:
                raise RuntimeError("Upstream revision differs from pin")
            archive = subprocess.check_output(
                ["git", "-C", str(mirror), "archive", entry["commit"]]
            )
            directory.mkdir()
            with tarfile.open(fileobj=io.BytesIO(archive)) as bundle:
                bundle.extractall(directory, filter="data")
            (directory / ".source-revision").write_text(entry["commit"] + "\n")
        marker = directory / ".source-revision"
        if not marker.is_file() or marker.read_text().strip() != entry["commit"]:
            raise RuntimeError(f"Missing or mismatched snapshot marker: {directory}")
        # Compare every tracked snapshot file to the pinned Git tree, not just its marker.
        archive = subprocess.check_output(
            ["git", "-C", str(mirror), "archive", entry["commit"]]
        )
        with tarfile.open(fileobj=io.BytesIO(archive)) as bundle:
            for member in bundle:
                path = directory / member.name
                if member.issym():
                    matches = path.is_symlink() and str(path.readlink()) == member.linkname
                elif member.isfile():
                    matches = path.read_bytes() == bundle.extractfile(member).read()
                else:
                    continue
                if not matches:
                    raise RuntimeError(f"Modified snapshot file: {path}")
        print(f"Verified {entry['directory']}: {entry['commit']}")


if __name__ == "__main__":
    main()
