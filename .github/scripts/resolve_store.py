#!/usr/bin/env python3
"""Resolves docs/store.json into docs/store-resolved.json.

The TV used to ask api.github.com for every GitHub-source app's latest release on each refresh.
Without a token that API allows 60 requests an hour per public IP, shared by every device at
home, so a second Fire TV quickly saw "can't reach GitHub". This script does the asking once,
here, with the workflow's token, and writes the answers to a file GitHub Pages serves (Pages has
no such limit). The TV reads that file and only calls the API for apps that aren't in it.

For each "github" entry: the latest release's tag, and the .apk asset the TV would pick (same
rules as Store.resolve/score in Store.java). For each "url" entry: its link and version, plus the
size from a HEAD request when the server gives one.

Usage: GITHUB_TOKEN=... python .github/scripts/resolve_store.py [store.json] [out.json]
Writes the file only when something other than "generated" changed. Exit 0 either way.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

UA = "MaterialTV-store-resolver"


def score(name):
    """Mirror of Store.score: Fire TV sticks are 32-bit ARM."""
    n = name.lower()
    if any(x in n for x in ("arm64", "aarch64", "x86", "x64")):
        return -1
    if re.fullmatch(r".*(armeabi|armv7|arm32|(^|[^a-z0-9])arm([^a-z0-9]|$)).*", n):
        s = 3
    elif "universal" in n:
        s = 2
    else:
        s = 1
    return 0 if "debug" in n else s


def api(path, token):
    req = urllib.request.Request("https://api.github.com" + path, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": UA,
        "X-GitHub-Api-Version": "2022-11-28",
        **({"Authorization": "Bearer " + token} if token else {}),
    })
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def resolve_github(app, token):
    src = app["source"]
    rel = api("/repos/%s/releases/latest" % src["repo"], token)
    want = re.compile(src["asset_regex"]) if src.get("asset_regex") else None
    best, best_score = None, -1
    for a in rel.get("assets") or []:
        name = a.get("name", "")
        if not name.lower().endswith(".apk"):
            continue
        if want and not want.search(name):
            continue
        s = score(name)
        if s > best_score:
            best, best_score = a, s
    if best is None or best_score < 0:
        raise RuntimeError("no matching .apk asset in %s" % rel.get("tag_name"))
    return {
        "id": app["id"],
        "repo": src["repo"],
        "version": rel.get("tag_name", ""),
        "url": best["browser_download_url"],
        "size": best.get("size", -1),
        "asset": best.get("name", ""),
    }


def head_size(url):
    try:
        req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=20) as r:
            n = r.headers.get("Content-Length")
            return int(n) if n and n.isdigit() else -1
    except Exception:  # size is a nicety; never fail the run for it
        return -1


def resolve_url(app):
    src = app["source"]
    url = src["url"]
    return {
        "id": app["id"],
        "version": src.get("version", ""),
        "url": url,
        "size": head_size(url),
        "asset": url.rsplit("/", 1)[-1],
    }


def main():
    store = sys.argv[1] if len(sys.argv) > 1 else "docs/store.json"
    out = sys.argv[2] if len(sys.argv) > 2 else "docs/store-resolved.json"
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN") or ""
    with open(store, encoding="utf-8") as f:
        catalog = json.load(f)

    old = {}
    if os.path.exists(out):
        try:
            with open(out, encoding="utf-8") as f:
                old = {a["id"]: a for a in json.load(f).get("apps", [])}
        except Exception:
            old = {}

    apps, failed = [], 0
    for app in catalog.get("apps", []):
        src = app.get("source") or {}
        kind = src.get("type")
        try:
            if kind == "github":
                apps.append(resolve_github(app, token))
            elif kind == "url":
                r = resolve_url(app)
                prev = old.get(app["id"])
                if r["size"] < 0 and prev and prev.get("url") == r["url"]:
                    r["size"] = prev.get("size", -1)  # keep the last known size
                apps.append(r)
            else:
                continue
            print("ok   %-12s %s" % (app["id"], apps[-1]["version"]))
        except Exception as e:  # keep the last good answer rather than dropping the app
            failed += 1
            print("FAIL %-12s %s" % (app.get("id"), e), file=sys.stderr)
            if app.get("id") in old:
                apps.append(old[app["id"]])

    if old and sorted(old.values(), key=lambda a: a["id"]) == sorted(apps, key=lambda a: a["id"]):
        print("unchanged")
        return 0
    doc = {
        "version": 1,
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "apps": apps,
    }
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print("wrote %s (%d apps, %d failed)" % (out, len(apps), failed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
