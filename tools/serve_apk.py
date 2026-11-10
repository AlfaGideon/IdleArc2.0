#!/usr/bin/env python3
"""Веб-страница Live Preview для скачивания собранного IdleArc Druid Helper APK в 1 клик."""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APK_PATH = ROOT / "releases" / "IdleArc-DruidHelper-v1.4.1.apk"


def get_meta():
    data = APK_PATH.read_bytes()
    sha = hashlib.sha256(data).hexdigest()
    kb = len(data) / 1024.0
    return data, sha, kb


class ApkDownloadHandler(BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")

    def do_GET(self):
        if self.path.startswith("/download") or self.path.endswith(".apk"):
            if not APK_PATH.exists():
                self.send_error(404)
                return
            data, sha, _ = get_meta()
            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", 'attachment; filename="IdleArc-DruidHelper-v1.4.1.apk"')
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return

        if not APK_PATH.exists():
            body = b"APK not found"
            self.send_response(404)
            self.end_headers()
            self.wfile.write(body)
            return

        _, sha, kb = get_meta()
        html = f"""<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>IdleArc Druid Helper APK</title>
  <style>
    body {{
      margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      background: #12101b; color: #f3f4f6; display: flex; justify-content: center; padding: 24px 16px;
    }}
    .card {{
      max-width: 520px; width: 100%; background: #1b1829; border: 1.5px solid #34d399;
      border-radius: 18px; padding: 22px; box-shadow: 0 12px 32px rgba(0,0,0,0.45);
    }}
    h1 {{ margin: 0 0 6px; font-size: 22px; color: #34d399; }}
    .sub {{ color: #9ca3af; font-size: 13px; margin-bottom: 18px; }}
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
    <h1>🌿 IdleArc Druid Helper v1.4.1</h1>
    <div class="sub">Прямое чтение названия и типа слота сверху открытой карточки предмета (Факел / Перчатки / Сапоги / Шлем / Броня / Пояс / Амулет / Кольцо / Оружие) + подбор 5/5 аффиксов</div>
    <a class="btn" href="download/IdleArc-DruidHelper-v1.4.1.apk" download="IdleArc-DruidHelper-v1.4.1.apk">
      📲 СКАЧАТЬ APK v1.4.1 ({kb:.1f} КБ)
    </a>
    <div class="meta">
      <b>Пакет:</b> com.idlearc.companion (minSdk 24 / targetSdk 34)<br>
      <b>SHA-256:</b> {sha}
    </div>
    <ul>
      <li><b>Плавающая таблетка «🌿 ДРУИД | 🔍 СКАН | ⚡»</b> поверх IdleArc — двигается пальцем, не перехватывает клики игры.</li>
      <li><b>Офлайн RU/EN OCR (проверен на всех 23 твоих скриншотах):</b> считывает шмот, сравнение предметов, выбор из 3 надписей талисмана, все 18 скиллов Друида и «Характеристики персонажа».</li>
      <li><b>Предзагружен твой профиль [UND] AlfaGideon (Ур. 324 · GS 3497):</b> сразу видит оверкап уклонения 131.7% &gt; 95%, считает точную прибавку DPS от Двойного удара vs Крита и отсеивает статы-ловушки (+% Урон атаки).</li>
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
