#!/usr/bin/env python3
"""Validate the Karoo release manifest against the karoo-ext contract.

Karoo System deserialises this file into `KarooAppManifest`, a `@Serializable`
Kotlin class. A wrong type there does not fail our build; it fails on the rider's
phone, at install time, with no signal to us. At least one published extension
ships `"tags": "Performance,Health"` — a String where the contract wants a
List<String> — which is exactly the class of mistake this catches.

    python3 scripts/check_manifest.py manifest.template.json
    python3 scripts/check_manifest.py --rendered manifest.json

The template carries `__VERSION__` placeholders, so version fields are only
type-checked with `--rendered`, after the release job has substituted them.

Pure standard library, matching scripts/render_icon.py.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

# Vocabulary documented on KarooAppManifest.tags. Values outside this set are not
# rejected by the companion app but do nothing useful for sorting, so treating
# them as an error keeps invented tags from creeping in unnoticed.
ALLOWED_TAGS = {"weather", "performance", "health", "entertainment"}

# Fields the companion app needs in order to list and install the extension.
REQUIRED_STRINGS = (
    "label",
    "packageName",
    "iconUrl",
    "latestApkUrl",
    "developer",
    "description",
)


def check(path: Path, rendered: bool) -> list[str]:
    """Return a list of human-readable problems; empty means the manifest is good."""
    raw = path.read_text()

    if not rendered:
        # The template is deliberately not valid JSON: latestVersionCode is an
        # unquoted __VERSION_CODE__ so the substituted value lands as a number
        # rather than a string. Stand in the same shapes the release job produces.
        raw = raw.replace("__VERSION_CODE__", "1").replace("__VERSION__", "0.0.0")

    try:
        manifest = json.loads(raw)
    except json.JSONDecodeError as exc:
        return [f"{path} is not valid JSON: {exc}"]

    problems: list[str] = []

    for key in REQUIRED_STRINGS:
        value = manifest.get(key)
        if not isinstance(value, str) or not value.strip():
            problems.append(f"{key} must be a non-empty string, got {value!r}")

    # The contract makes tags nullable, so omitting it is legal. We require it
    # anyway: having decided to be discoverable, silently dropping the field
    # again should be a build failure rather than something nobody notices.
    tags = manifest.get("tags")
    if not isinstance(tags, list) or not all(isinstance(t, str) for t in tags):
        problems.append(f"tags must be a JSON array of strings, got {tags!r}")
    else:
        unknown = sorted(set(tags) - ALLOWED_TAGS)
        if unknown:
            problems.append(
                f"tags contains values outside the documented vocabulary "
                f"{sorted(ALLOWED_TAGS)}: {unknown}",
            )

    screenshots = manifest.get("screenshotUrls")
    if screenshots is not None and (
        not isinstance(screenshots, list)
        or not all(isinstance(s, str) for s in screenshots)
    ):
        problems.append(
            f"screenshotUrls, when present, must be an array of strings, "
            f"got {screenshots!r}",
        )

    if rendered:
        version = manifest.get("latestVersion")
        if not isinstance(version, str) or "__" in version:
            problems.append(f"latestVersion was not substituted: {version!r}")
        code = manifest.get("latestVersionCode")
        if not isinstance(code, int) or isinstance(code, bool):
            problems.append(f"latestVersionCode must be an integer, got {code!r}")
    else:
        # The template must still contain the placeholders the release job
        # substitutes, or the rendered manifest silently keeps a stale version.
        source = path.read_text()
        for placeholder in ("__VERSION__", "__VERSION_CODE__"):
            if placeholder not in source:
                problems.append(f"{path} no longer contains {placeholder}")

    return problems


def main(argv: list[str]) -> int:
    args = [a for a in argv[1:] if a != "--rendered"]
    rendered = "--rendered" in argv[1:]
    path = Path(args[0]) if args else Path("manifest.template.json")

    problems = check(path, rendered)
    if problems:
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        return 1
    print(f"{path} is a valid Karoo manifest")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
