#!/usr/bin/env python3
"""Веб-страница Live Preview для скачивания собранного IdleArc Druid Helper APK и проверки OTA-манифеста."""
import hashlib
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST_XML = ROOT / "companion" / "overlay" / "app" / "src" / "main" / "AndroidManifest.xml"
MANIFEST_JSON = ROOT / "releases" / "ota-manifest.json"


def read_current_apk_info() -> tuple[int, str, Path]:
    xml = MANIFEST_XML.read_text(encoding="utf-8")
    mc = re.search(r'android:versionCode="(\d+)"', xml)
    mn = re.search(r'android:versionName="([^"]+)"', xml)
    code = int(mc.group(1)) if mc else 211
    name = mn.group(1) if mn else "2.1.1"
    return code, name, ROOT / "releases" / f"IdleArc-DruidHelper-v{name}.apk"


def get_meta():
    code, name, apk_path = read_current_apk_info()
    data = apk_path.read_bytes()
    sha = hashlib.sha256(data).hexdigest()
    kb = len(data) / 1024.0
    return code, name, apk_path.name, data, sha, kb


class ApkDownloadHandler(BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")

    def do_HEAD(self):
        if self.path.startswith("/ota-manifest"):
            if not MANIFEST_JSON.exists():
                self.send_error(404)
                return
            raw = MANIFEST_JSON.read_bytes()
            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            return
        _, _, apk_fname, data, _, _ = get_meta()
        if self.path.startswith("/download") or self.path.endswith(".apk"):
            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", f'attachment; filename="{apk_fname}"')
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            return
        self.send_response(200)
        self._cors()
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()

    def do_GET(self):
        if self.path.startswith("/ota-manifest"):
            if not MANIFEST_JSON.exists():
                self.send_error(404)
                return
            raw = MANIFEST_JSON.read_bytes()
            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
            return

        code, name, apk_fname, data, sha, kb = get_meta()
        if self.path.startswith("/download") or self.path.endswith(".apk"):
            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", f'attachment; filename="{apk_fname}"')
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return

        html = f"""<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>IdleArc Druid Helper v{name} APK</title>
  <style>
    body {{
      margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      background: #12101b; color: #f3f4f6; display: flex; justify-content: center; padding: 24px 16px;
    }}
    .card {{
      max-width: 560px; width: 100%; background: #1b1829; border: 1.5px solid #34d399;
      border-radius: 18px; padding: 22px; box-shadow: 0 12px 32px rgba(0,0,0,0.45);
    }}
    h1 {{ margin: 0 0 6px; font-size: 22px; color: #34d399; }}
    .sub {{ color: #9ca3af; font-size: 13px; margin-bottom: 18px; line-height: 1.45; }}
    .btn {{
      display: block; text-align: center; background: #059669; color: #fff; text-decoration: none;
      font-weight: 700; font-size: 16px; padding: 15px 18px; border-radius: 12px;
      margin: 14px 0; transition: background 0.15s;
    }}
    .btn:hover {{ background: #10b981; }}
    .meta {{
      background: #141220; border-radius: 10px; padding: 12px; font-size: 12px;
      color: #d1d5db; word-break: break-all; line-height: 1.55;
    }}
    ul {{ padding-left: 18px; font-size: 13.5px; line-height: 1.55; color: #e5e7eb; }}
  </style>
</head>
<body>
  <div class="card">
    <h1>🌿 IdleArc Druid Helper v{name}</h1>
    <div class="sub">Автообновление по воздуху (OTA через GitHub Releases) + живой анализатор «Гнёзда и Самоцветы»</div>
    <a class="btn" href="download/{apk_fname}" download="{apk_fname}">
      📲 СКАЧАТЬ APK v{name} ({kb:.1f} КБ)
    </a>
    <div class="meta">
      <b>Пакет:</b> com.idlearc.companion · versionCode {code} (minSdk 24 / targetSdk 34)<br>
      <b>SHA-256:</b> {sha}
    </div>
    <ul>
      <li><b>Автообновление по воздуху (OTA):</b> после установки этой версии все следующие обновления устанавливаются прямо из приложения одной кнопкой без ручного скачивания.</li>
      <li><b>Одновременный скан камней и аффиксов:</b> читает гнёзда над аффиксами прямо на карточке предмета, а также окна «Обзор гнёзд», «Гнёзда · Слот» и «Кодекс самоцветов».</li>
    </ul>
  </div>
</body>
</html>""".encode("utf-8")
        self.send_response(200)
        self._cors()
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(html)))
        self.end_headers()
        self.wfile.write(html)

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("0.0.0.0", 8080), ApkDownloadHandler)
    print("APK_DOWNLOAD_SERVER_READY on 0.0.0.0:8080", flush=True)
    srv.serve_forever()
