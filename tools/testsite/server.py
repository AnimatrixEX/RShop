"""
Local test site for RShop: a fictional homebrew catalogue organised by console.

Exercises everything the app supports without depending on a real site:
console index, pagination, site search, game pages with SHA-256, a download page with a
countdown, HTTP Range/ETag resume, throttled transfers, a corrupted file and a malicious archive.

    python tools/testsite/server.py [--port 8099] [--rate 4]   # rate in MB/s
    adb reverse tcp:8099 tcp:8099                               # then use http://localhost:8099/consoles

All files are generated random data: nothing here is a real game.
"""
import argparse
import hashlib
import html
import io
import os
import re
import tarfile
import tempfile
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

CONSOLES = {
    "nes": "NES",
    "gb": "Game Boy",
    "md": "Mega Drive",
    "ps1": "PlayStation",
}

# slug, title, console, genre, version, kind, size_kb, publish_sha
GAMES = [
    ("neon-drift", "Neon Drift", "nes", "Course", "1.2", "zip", 40, True),
    ("shadow-ninja", "Shadow Ninja", "nes", "Plateforme", "1.0", "zip", 32, True),
    ("tiny-tanks", "Tiny Tanks", "nes", "Action", "1.1", "zip", 24, False),
    ("rocket-rush", "Rocket Rush", "nes", "Shoot'em up", "1.0", "evil", 8, False),
    ("pixel-quest", "Pixel Quest", "gb", "Aventure", "1.1", "targz", 256, True),
    ("block-mania", "Block Mania", "gb", "Puzzle", "2.0", "badsha", 32, True),
    ("sky-pirates", "Sky Pirates", "md", "Shoot'em up", "1.0", "page", 512, True),
    ("star-courier", "Star Courier", "md", "Aventure", "1.0", "zip", 1024, True),
    ("deep-space", "Deep Space Echo", "ps1", "Action", "1.3", "chd", 60 * 1024, True),
    # Opaque button (/dl/<slug>) that redirects to a URL without extension; name in Content-Disposition.
    ("moon-runner", "Moon Runner", "gb", "Course", "1.0", "redirect", 48, False),
    # Two formats offered on the game page (ZIP and TAR.GZ).
    ("twin-formats", "Twin Formats", "md", "Puzzle", "1.0", "multi", 64, False),
    # Game page -> download page -> mirror confirmation page -> redirect -> file.
    ("mirror-maze", "Mirror Maze", "nes", "Aventure", "1.0", "chain", 40, False),
    # Download page full of ad traps: any click opens a pop-under to another site (127.0.0.1),
    # the page tries to send itself to an ad, and the real link opens in a new tab.
    ("popup-trap", "Popup Trap", "md", "Action", "1.0", "popup", 32, False),
]
ALTS = {}  # slug -> (filename, path) of a second format
PER_PAGE = 3
COLORS = [(30, 60, 114), (66, 39, 90), (15, 52, 67), (58, 28, 113), (75, 19, 79), (19, 78, 94), (122, 46, 14), (31, 28, 44), (22, 34, 42)]


def build_files(root):
    """Creates every downloadable file; returns {slug: (filename, bytes path, sha256 published)}."""
    files = {}
    for index, (slug, title, console, _genre, _version, kind, size_kb, publish) in enumerate(GAMES):
        payload = os.urandom(size_kb * 1024)
        rom_ext = {"nes": "nes", "gb": "gb", "md": "md", "ps1": "chd"}[console]
        if kind in ("zip", "badsha", "page", "redirect", "multi", "chain", "popup"):
            name = f"{slug}.zip"
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as z:
                z.writestr(f"{title}.{rom_ext}", payload)
            data = buffer.getvalue()
        elif kind == "targz":
            name = f"{slug}.tar.gz"
            buffer = io.BytesIO()
            with tarfile.open(fileobj=buffer, mode="w:gz") as t:
                info = tarfile.TarInfo(f"{title}/{title}.{rom_ext}")
                info.size = len(payload)
                t.addfile(info, io.BytesIO(payload))
                readme = b"Homebrew test data for RShop."
                info = tarfile.TarInfo(f"{title}/readme.txt")
                info.size = len(readme)
                t.addfile(info, io.BytesIO(readme))
            data = buffer.getvalue()
        elif kind == "evil":
            name = f"{slug}.zip"
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, "w") as z:
                z.writestr(f"{title}.{rom_ext}", payload)
                z.writestr("../../escaped.txt", b"this must never be written")
            data = buffer.getvalue()
        else:  # chd: served as is (raw disc image)
            name = f"{title}.chd"
            data = b"MComprHD" + payload
        path = os.path.join(root, name)
        with open(path, "wb") as f:
            f.write(data)
        if kind == "multi":
            alt_name = f"{slug}.tar.gz"
            buffer = io.BytesIO()
            with tarfile.open(fileobj=buffer, mode="w:gz") as t:
                info = tarfile.TarInfo(f"{title}.{rom_ext}")
                info.size = len(payload)
                t.addfile(info, io.BytesIO(payload))
            alt_path = os.path.join(root, alt_name)
            with open(alt_path, "wb") as f:
                f.write(buffer.getvalue())
            ALTS[slug] = (alt_name, alt_path)
        sha = hashlib.sha256(data).hexdigest()
        if kind == "badsha":
            sha = hashlib.sha256(b"something else").hexdigest()
        files[slug] = (name, path, sha if publish else None)
    return files


def cover_png(slug):
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        return None
    index = [g[0] for g in GAMES].index(slug) if slug in [g[0] for g in GAMES] else 0
    color = COLORS[index % len(COLORS)]
    image = Image.new("RGB", (300, 400), color)
    draw = ImageDraw.Draw(image)
    for i in range(0, 400, 20):
        draw.line([(0, i), (300, i + 120)], fill=tuple(min(255, c + 40) for c in color), width=6)
    draw.rectangle([20, 300, 280, 380], fill=(0, 0, 0))
    draw.text((30, 330), slug.upper().replace("-", " "), fill=(255, 255, 255))
    out = io.BytesIO()
    image.save(out, "PNG")
    return out.getvalue()


def page(title, body):
    return f"""<!doctype html><html><head><meta charset="utf-8"><title>{html.escape(title)} - Homebrew Hub</title></head>
<body>
<header><nav><ul class="menu"><li><a href="/">Home</a></li>
<li class="dropdown"><a href="#">Consoles</a><ul class="sub-menu">{"".join(f'<li><a href="/console/{s}/">{n}</a></li>' for s, n in CONSOLES.items())}</ul></li>
<li><a href="/about">About</a></li><li><a href="/faq">FAQ</a></li></ul></nav>
<form action="/search" method="get"><input type="hidden" name="type" value="roms"><input type="search" name="q"></form></header>
<main>{body}</main>
<footer><a href="/terms">Terms</a> <a href="/privacy">Privacy</a> <a href="/rss">RSS</a></footer>
</body></html>"""


def downloads(slug):
    """A stable, made-up download counter per game (so popularity has something to sort on)."""
    return int(hashlib.md5(slug.encode()).hexdigest()[:6], 16) % 48000 + 25


def grouped(n):
    return f"{n:,}".replace(",", " ")


def card(game):
    slug, title, console, genre, version, _kind, size_kb, _p = game
    size = f"{size_kb} KB" if size_kb < 1024 else f"{size_kb // 1024} MB"
    return f"""<div class="game-card">
  <a class="thumb" href="/game/{slug}"><img src="/static/placeholder.gif" data-src="/covers/{slug}.png" alt="{html.escape(title)}"></a>
  <h3 class="game-title"><a href="/game/{slug}">{html.escape(title)}</a></h3>
  <span class="genre">{html.escape(genre)}</span> <span class="size">{size}</span>
  <span class="stats">{grouped(downloads(slug))} downloads</span>
</div>"""


class Handler(BaseHTTPRequestHandler):
    files = {}
    rate = 4 * 1024 * 1024
    chunk = 64 * 1024

    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args))

    def send_html(self, body, code=200, cookie=None):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urlparse(self.path)
        path = url.path
        if path == "/robots.txt":
            body = b"User-agent: *\nDisallow: /private/\nCrawl-delay: 1\n"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/":
            latest = "".join(card(g) for g in GAMES[-4:])
            return self.send_html(page("Home", f'<h2>Latest games</h2><div class="grid">{latest}</div>'))
        if path == "/consoles":
            tiles = "".join(
                f'<div class="console-tile"><a href="/console/{slug}/"><img src="/covers/{slug}.png"><span class="console-name">{name}</span></a></div>'
                for slug, name in CONSOLES.items()
            )
            return self.send_html(page("Consoles", f'<div class="consoles">{tiles}</div>'))
        m = re.fullmatch(r"/console/([a-z0-9]+)/(?:page/(\d+)/)?", path)
        if m and m.group(1) in CONSOLES:
            games = [g for g in GAMES if g[2] == m.group(1)]
            number = int(m.group(2) or 1)
            chunk = games[(number - 1) * PER_PAGE: number * PER_PAGE]
            if not chunk:
                return self.send_html(page("Not found", "<p>No page</p>"), 404)
            nxt = f'<a class="next" href="/console/{m.group(1)}/page/{number + 1}/">Next</a>' if number * PER_PAGE < len(games) else ""
            return self.send_html(page(CONSOLES[m.group(1)], f'<div class="grid">{"".join(card(g) for g in chunk)}</div><div class="pagination">{nxt}</div>'))
        if path == "/search":
            q = parse_qs(url.query).get("q", [""])[0].lower()
            found = [g for g in GAMES if q and q in g[1].lower()]
            return self.send_html(page("Search", f'<div class="grid">{"".join(card(g) for g in found)}</div>'))
        m = re.fullmatch(r"/game/([a-z0-9-]+)", path)
        if m:
            game = next((g for g in GAMES if g[0] == m.group(1)), None)
            if not game:
                return self.send_html(page("Not found", ""), 404)
            slug, title, console, genre, version, kind, size_kb, _p = game
            name, file_path, sha = self.files[slug]
            size = os.path.getsize(file_path)
            size_text = f"{size / 1024:.0f} KB" if size < 1024 * 1024 else f"{size / 1024 / 1024:.1f} MB"
            if kind in ("page", "chain", "popup"):
                link = f'<a class="btn" href="/download/{slug}">Download</a>'
            elif kind == "redirect":
                link = f'<div class="dl"><a class="download-btn" href="/dl/{slug}"><span>&#128190;</span><span>Download {html.escape(title)}</span></a></div>'
            elif kind == "multi":
                link = f'<a class="btn" href="/files/{name}">Download (ZIP)</a> <a class="btn" href="/files/{ALTS[slug][0]}">Download (TAR.GZ)</a>'
            else:
                link = f'<a class="btn" href="/files/{name}">Download</a>'
            sha_line = f"<p>SHA-256: {sha}</p>" if sha else ""
            body = f"""<article>
<h1>{html.escape(title)}</h1>
<dl class="meta"><dt>Platform</dt><dd>{CONSOLES[console]}</dd><dt>Genre</dt><dd>{html.escape(genre)}</dd></dl>
<ul class="facts"><li>Version: {version}</li><li>Size: {size_text}</li><li>Téléchargements : {grouped(downloads(slug))}</li></ul>
<div class="description"><p>{html.escape(title)} is a free homebrew test entry for RShop ({CONSOLES[console]}). Generated data, not a real game.</p></div>
<div class="screenshots"><img src="/covers/{slug}.png"><img src="/covers/{slug}.png"></div>
{sha_line}
{link}
</article>"""
            return self.send_html(page(title, body))
        if path == "/ad":
            return self.send_html("<html><head><title>YOU WON!</title></head><body><h1>Fake ad page (another site)</h1></body></html>")
        m = re.fullmatch(r"/download/([a-z0-9-]+)", path)
        if m and m.group(1) in self.files and next(g for g in GAMES if g[0] == m.group(1))[5] == "popup":
            name = self.files[m.group(1)][0]
            trap = """<script>
var ad = location.protocol + '//127.0.0.1:' + location.port + '/ad';
document.addEventListener('click', function () {
  window.open(ad, '_blank');
  setTimeout(function () { location.href = ad; }, 50);
}, true);
</script>"""
            return self.send_html(page("Download", f'{trap}<p>Click the link below. Ads will try to get in the way.</p><a class="file" target="_blank" href="/files/{name}">Download {name}</a>'))
        if m and m.group(1) in self.files and next(g for g in GAMES if g[0] == m.group(1))[5] == "chain":
            return self.send_html(page("Mirrors", f'<p>Choose a mirror</p><a href="../confirm/{m.group(1)}?mirror=1">Download from mirror 1</a>'))
        m2 = re.fullmatch(r"/confirm/([a-z0-9-]+)", path)
        if m2 and m2.group(1) in self.files:
            # Opened in a new tab; sets the session cookie that "Download now" requires.
            return self.send_html(page("Confirm", f'<p>Thanks!</p><a href="/dl/{m2.group(1)}">Download now</a>'),
                                  cookie=f"dlsession={m2.group(1)}; Path=/")
        m2 = re.fullmatch(r"/dl/([a-z0-9-]+)", path)
        if m2 and m2.group(1) in self.files and next(g for g in GAMES if g[0] == m2.group(1))[5] == "chain":
            # Like many hosts: without the session cookie and the referring page, a web page comes back.
            if f"dlsession={m2.group(1)}" not in (self.headers.get("Cookie") or "") or "/confirm/" not in (self.headers.get("Referer") or ""):
                return self.send_html(page("Expired", "<p>Session expired. Go back to the game page.</p>"))
        if m2 and m2.group(1) in self.files:
            self.send_response(302)
            self.send_header("Location", f"/get?id={m2.group(1)}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if path == "/get":
            slug = parse_qs(url.query).get("id", [""])[0]
            if slug in self.files:
                return self.send_file(self.files[slug][1], self.files[slug][0])
        if m and m.group(1) in self.files:
            name = self.files[m.group(1)][0]
            body = f'<p>Your download will be available in 5 seconds.</p><div id="timer" data-countdown="5"></div><a class="file" href="/files/{name}">{name}</a>'
            return self.send_html(page("Download", body))
        m = re.fullmatch(r"/covers/([a-z0-9-]+)\.png", path)
        if m:
            data = cover_png(m.group(1))
            if data is None:
                return self.send_html("", 404)
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "max-age=3600")
            self.end_headers()
            self.wfile.write(data)
            return
        m = re.fullmatch(r"/files/(.+)", path)
        if m:
            entry = next((v for v in list(self.files.values()) + list(ALTS.values()) if v[0] == m.group(1).replace("%20", " ")), None)
            if entry:
                return self.send_file(entry[1])
        self.send_html(page("Not found", "<p>Not found</p>"), 404)

    def send_file(self, file_path, attachment_name=None):
        size = os.path.getsize(file_path)
        etag = '"%s"' % hashlib.md5(f"{file_path}{size}".encode()).hexdigest()
        start = 0
        range_header = self.headers.get("Range")
        if range_header and self.headers.get("If-Range", etag) == etag:
            m = re.fullmatch(r"bytes=(\d+)-", range_header)
            if m:
                start = int(m.group(1))
        if start >= size and size > 0:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.end_headers()
            return
        self.send_response(206 if start else 200)
        self.send_header("Content-Type", "application/octet-stream")
        if attachment_name:
            self.send_header("Content-Disposition", f'attachment; filename="{attachment_name}"')
        self.send_header("ETag", etag)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(size - start))
        if start:
            self.send_header("Content-Range", f"bytes {start}-{size - 1}/{size}")
        self.end_headers()
        chunk = self.chunk
        with open(file_path, "rb") as f:
            f.seek(start)
            sent_at = time.time()
            while True:
                data = f.read(chunk)
                if not data:
                    break
                try:
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    return
                # Throttle so progress, pause and resume can be observed.
                expected = len(data) / self.rate
                elapsed = time.time() - sent_at
                if expected > elapsed:
                    time.sleep(expected - elapsed)
                sent_at = time.time()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--rate", type=float, default=4, help="transfer rate in MB/s")
    parser.add_argument("--chunk", type=int, default=64, help="write size in KB (small + low rate = slow small files)")
    args = parser.parse_args()
    root = tempfile.mkdtemp(prefix="rshop-testsite-")
    Handler.files = build_files(root)
    Handler.rate = args.rate * 1024 * 1024
    Handler.chunk = max(1, args.chunk) * 1024
    print(f"Test site on http://localhost:{args.port}/consoles (files in {root})")
    for slug, (name, path, sha) in Handler.files.items():
        print(f"  {slug:14} {name:22} {os.path.getsize(path):>10} bytes  sha={'published' if sha else 'none'}")
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
