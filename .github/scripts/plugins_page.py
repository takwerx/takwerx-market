#!/usr/bin/env python3
"""Build the public "every plugin in the Market" page.

Reads the live catalog the Market app reads (ATAK's product.inf format) and
community/plugins.json, and writes a static site for GitHub Pages:

    plugins_page.py --out _site [--catalog URL-or-file] [--community FILE]

Writes index.html and fingerprint.txt. The fingerprint is a hash of the page
without its timestamp, so the workflow can skip a deploy when nothing changed.

The catalog has the takwerx plugins, the community plugins and ATAK-CIV; a
plugin counts as community when its repository is in community/plugins.json, or
when it lives outside the takwerx account. A community listing that the catalog
doesn't carry yet (just merged, or its release was refused) is still shown,
marked as not in the Market yet.

Icons are not copied: catalog rows use the catalog's own hashed icon URLs and
unlisted community rows use community/glyphs/ on main. The glyphs are white on
transparent, so each one sits on a dark tile.

Exits non-zero, writing nothing, if the catalog can't be read or has no
takwerx plugin in it, so a bad fetch never replaces a good page with an empty one.
"""

import argparse
import datetime
import hashlib
import html
import json
import os
import re
import sys
import urllib.request

DEFAULT_CATALOG = "https://mapdepot.takwerx.org/depot/product.inf"
OWNER = "takwerx"
REPO_URL = "https://github.com/takwerx/takwerx-market"
GLYPH_BASE = "https://raw.githubusercontent.com/takwerx/takwerx-market/main/community/glyphs/"
LISTING_DOC = REPO_URL + "/blob/main/docs/LIST_YOUR_PLUGIN.md"
LISTING_FORM = REPO_URL + "/issues/new?template=list-my-plugin.yml"

APK_URL = re.compile(
    r"^https://github\.com/([A-Za-z0-9-]+)/([A-Za-z0-9._-]+)/releases/download/([^/?#]+)/[^/?#]+\.apk$")
ICON_PATH = re.compile(r"^icons/[A-Za-z0-9._-]+\.png$")
GLYPH_NAME = re.compile(r"^[a-z0-9._-]+\.png$")
PLUGIN_API = re.compile(r"@(\d+)\.(\d+)\.\d+\.[A-Z]+$")


def read_text(src):
    if re.match(r"^https?://", src):
        req = urllib.request.Request(src, headers={"User-Agent": "takwerx-market-plugins-page"})
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read().decode("utf-8")
    with open(src, encoding="utf-8") as f:
        return f.read()


def unescape(s):
    # Same escapes as the Market's own parser (MarketCatalog.unescape).
    return s.strip().replace("\\u002c", ",").replace("\\n", "\n")


def version_key(v):
    """'1.10' > '1.9': compare the leading dotted number, number by number."""
    m = re.match(r"\d+(?:\.\d+)*", v or "")
    return tuple(int(p) for p in m.group(0).split(".")) if m else ()


def parse_catalog(text):
    rows = []
    for raw in text.split("\n"):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        c = line.split(",")
        if len(c) < 12 or unescape(c[0]).lower() != "android":
            continue
        rows.append({
            "type": unescape(c[1]),
            "package": unescape(c[2]),
            "name": unescape(c[3]),
            # "0.8 () - [5.6.0]" and "1.1 (2f06ef98) - [5.6.0]" -> the version alone
            "version": unescape(c[4]).split(" ")[0],
            "apk": unescape(c[6]),
            "icon": unescape(c[7]),
            "description": unescape(c[8]),
            "plugin_api": unescape(c[11]),
        })
    return rows


def atak_of(plugin_api):
    m = PLUGIN_API.search(plugin_api)
    return "%s.%s" % (m.group(1), m.group(2)) if m else None


def build(rows, community, catalog_base):
    """Group catalog rows into one entry per plugin package."""
    listed = {p["repo"].lower(): p for p in community}
    by_pkg = {}
    ataks = []
    for r in rows:
        if r["type"] == "app":
            ataks.append(r["version"])
            continue
        if r["type"] != "plugin":
            continue
        m = APK_URL.match(r["apk"])
        if not m:
            continue
        r["owner"], r["repo"], r["tag"] = m.group(1), m.group(2), m.group(3)
        by_pkg.setdefault(r["package"], []).append(r)

    plugins = []
    for pkg, rs in by_pkg.items():
        newest = max(rs, key=lambda r: version_key(r["version"]))
        repo = "%s/%s" % (newest["owner"], newest["repo"])
        entry = listed.get(repo.lower())
        targets = {}
        for r in rs:
            a = atak_of(r["plugin_api"])
            if a and (a not in targets or version_key(r["version"]) > version_key(targets[a])):
                targets[a] = r["version"]
        icon = None
        if ICON_PATH.match(newest["icon"]):
            icon = catalog_base + "/" + newest["icon"]
        elif entry and GLYPH_NAME.match(entry.get("glyph", "")):
            icon = GLYPH_BASE + entry["glyph"]
        plugins.append({
            "name": newest["name"],
            "description": newest["description"] or (entry or {}).get("description", ""),
            "version": newest["version"],
            "targets": targets,
            "icon": icon,
            "repo": repo,
            "developer": newest["owner"],
            "release": "https://github.com/%s/releases/tag/%s" % (repo, newest["tag"]),
            "community": bool(entry) or newest["owner"].lower() != OWNER,
        })

    seen = {p["repo"].lower() for p in plugins}
    for e in community:
        repo = e.get("repo", "")
        if repo.lower() in seen or not re.match(r"^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$", repo):
            continue
        glyph = e.get("glyph", "")
        plugins.append({
            "name": repo.split("/")[1],
            "description": e.get("description", ""),
            "version": None,
            "targets": {},
            "icon": GLYPH_BASE + glyph if GLYPH_NAME.match(glyph) else None,
            "repo": repo,
            "developer": repo.split("/")[0],
            "release": "https://github.com/%s/releases" % repo,
            "community": True,
        })

    ataks.sort(key=version_key, reverse=True)
    return plugins, ataks


def esc(s):
    return html.escape(s or "", quote=True)


def row(p):
    icon = ('<img src="%s" alt="" width="40" height="40" loading="lazy">' % esc(p["icon"])
            if p["icon"] else "")
    if p["version"]:
        version = '<a href="%s">%s</a>' % (esc(p["release"]), esc(p["version"]))
        targets = []
        for a in sorted(p["targets"], key=version_key):
            v = p["targets"][a]
            targets.append(esc(a) if v == p["version"] else "%s&nbsp;(%s)" % (esc(a), esc(v)))
        atak = ", ".join(targets)
    else:
        version = '<span class="muted">Not in the Market yet</span>'
        atak = ""
    dev = ('<td><a href="https://github.com/%s">%s</a></td>' % (esc(p["developer"]), esc(p["developer"]))
           if p["community"] else "")
    return (
        '<tr><td class="icon"><span class="tile">%s</span></td>'
        '<td><a class="name" href="https://github.com/%s">%s</a><div class="desc">%s</div></td>'
        '%s<td class="nowrap">%s</td><td class="nowrap">%s</td></tr>'
        % (icon, esc(p["repo"]), esc(p["name"]), esc(p["description"]), dev, version, atak))


def table(plugins, community):
    head = "<th></th><th>Plugin</th>%s<th>Version</th><th>ATAK</th>" % (
        "<th>Developer</th>" if community else "")
    body = "\n".join(row(p) for p in sorted(plugins, key=lambda p: p["name"].lower()))
    return "<table>\n<thead><tr>%s</tr></thead>\n<tbody>\n%s\n</tbody>\n</table>" % (head, body)


CSS = """
:root { color-scheme: light dark; --fg:#1f2328; --muted:#59636e; --bg:#ffffff; --line:#d1d9e0; --link:#0969da; --tile:#1f2328; }
@media (prefers-color-scheme: dark) { :root { --fg:#e6edf3; --muted:#9198a1; --bg:#0d1117; --line:#3d444d; --link:#4493f8; --tile:#2a313c; } }
body { margin:0; background:var(--bg); color:var(--fg); font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif; }
main { max-width:980px; margin:0 auto; padding:32px 20px 48px; }
h1 { font-size:28px; margin:0 0 8px; } h2 { font-size:20px; margin:36px 0 8px; padding-bottom:6px; border-bottom:1px solid var(--line); }
a { color:var(--link); text-decoration:none; } a:hover { text-decoration:underline; }
p { margin:8px 0; } .muted, .desc { color:var(--muted); }
table { width:100%; border-collapse:collapse; } th { text-align:left; font-size:13px; color:var(--muted); font-weight:600; padding:6px 10px; }
td { padding:10px; border-top:1px solid var(--line); vertical-align:middle; } td.icon { width:56px; padding-right:0; } td.nowrap { white-space:nowrap; }
.tile { display:flex; align-items:center; justify-content:center; width:52px; height:52px; border-radius:10px; background:var(--tile); }
.name { font-weight:600; } .desc { font-size:14px; } footer { margin-top:36px; font-size:13px; color:var(--muted); }
"""


def page(plugins, ataks, stamp):
    ours = [p for p in plugins if not p["community"]]
    theirs = [p for p in plugins if p["community"]]
    atak = ""
    if ataks:
        atak = ("<p>The Market also offers ATAK-CIV itself, from the TAK Product Center's own release: %s.</p>"
                % esc(", ".join(ataks)))
    return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TAKwerx Market plugins</title>
<style>%s</style>
</head>
<body>
<main>
<h1>TAKwerx Market plugins</h1>
<p>Every ATAK plugin the <a href="%s">TAKwerx Market</a> offers, built from the Market's live catalog. Each plugin links to its own GitHub repository; the version links to the release the Market installs from.</p>
<p>Built a plugin? <a href="%s">Here's how to get it listed</a>, then <a href="%s">open a listing request</a>.</p>
<h2>TAKwerx plugins</h2>
%s
<h2>Community plugins</h2>
<p class="muted">From other developers, installed from their own tak.gov-signed releases. A listing isn't a review of their code by takwerx; support for these plugins is on each developer's repository.</p>
%s
%s
<footer>%d plugins. The list is rebuilt from the Market's catalog and <a href="%s/blob/main/community/plugins.json">community/plugins.json</a>; last changed %s.</footer>
</main>
</body>
</html>
""" % (CSS, REPO_URL, LISTING_DOC, LISTING_FORM, table(ours, False), table(theirs, True),
       atak, len(plugins), REPO_URL, stamp)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", required=True)
    ap.add_argument("--catalog", default=DEFAULT_CATALOG)
    ap.add_argument("--catalog-base", default=None,
                    help="where the catalog's relative icon paths live (default: next to --catalog)")
    ap.add_argument("--community",
                    default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..",
                                         "community", "plugins.json"))
    a = ap.parse_args()

    base = a.catalog_base or (a.catalog.rsplit("/", 1)[0] if re.match(r"^https?://", a.catalog)
                              else DEFAULT_CATALOG.rsplit("/", 1)[0])
    rows = parse_catalog(read_text(a.catalog))
    with open(a.community, encoding="utf-8") as f:
        community = json.load(f).get("plugins", [])
    plugins, ataks = build(rows, community, base)

    if not any(not p["community"] for p in plugins):
        sys.exit("plugins_page: no takwerx plugin in the catalog, refusing to build an empty page")

    fingerprint = hashlib.sha256(page(plugins, ataks, "").encode("utf-8")).hexdigest()
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "index.html"), "w", encoding="utf-8") as f:
        f.write(page(plugins, ataks, stamp))
    with open(os.path.join(a.out, "fingerprint.txt"), "w", encoding="utf-8") as f:
        f.write(fingerprint + "\n")
    print("plugins_page: %d takwerx, %d community, ATAK-CIV %s, fingerprint %s"
          % (sum(not p["community"] for p in plugins), sum(p["community"] for p in plugins),
             ", ".join(ataks) or "none", fingerprint[:12]))


if __name__ == "__main__":
    main()
