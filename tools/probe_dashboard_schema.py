#!/usr/bin/env python3
"""
Safe schema & endpoint discovery for https://idlearc.com/api/dashboard.
Strictly redacts all credentials, cookies, tokens, emails, user/character names, and account IDs.
"""
from __future__ import annotations

import html.parser
import json
import os
import re
import sys
import urllib.parse
import http.cookiejar
import urllib.request


BASE = "https://idlearc.com"

SENSITIVE_EXACT_KEYS = {
    "email", "e_mail", "mail", "password", "passwd", "pwd",
    "token", "access_token", "refresh_token", "id_token", "csrf_token", "_csrf_token",
    "session", "session_id", "sid", "secret", "api_key", "auth_token",
    "ip", "ip_address", "last_ip", "stripe_customer_id", "subscription_id",
    "google_id", "apple_id", "discord_id", "user_id", "account_id", "owner_id",
    "username", "display_name", "character_name", "hero_name", "player_name", "guild_name",
    "referral_code", "invite_code",
}


class LoginPageParser(html.parser.HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.forms: list[dict] = []
        self._cur_form: dict | None = None
        self.meta: dict[str, str] = {}
        self.scripts: list[str] = []
        self._in_script = False
        self._script_buf: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        ad = {k.lower(): (v or "") for k, v in attrs}
        t = tag.lower()
        if t == "form":
            self._cur_form = {"action": ad.get("action", ""), "method": ad.get("method", "get").upper(), "inputs": []}
            self.forms.append(self._cur_form)
        elif t == "input":
            inp = {
                "name": ad.get("name", ""),
                "type": ad.get("type", "text"),
                "value": ad.get("value", ""),
                "id": ad.get("id", ""),
            }
            if self._cur_form is not None:
                self._cur_form["inputs"].append(inp)
        elif t == "meta":
            n = ad.get("name", "") or ad.get("property", "")
            if n:
                self.meta[n] = ad.get("content", "")
        elif t == "script":
            src = ad.get("src", "")
            if src:
                self.scripts.append(f"SRC:{src}")
            else:
                self._in_script = True
                self._script_buf = []

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "script" and self._in_script:
            self._in_script = False
            self.scripts.append("INLINE:" + "".join(self._script_buf))

    def handle_data(self, data: str) -> None:
        if self._in_script:
            self._script_buf.append(data)


def scrub(obj, parent_key: str = "", depth: int = 0):
    if depth > 14:
        return "<MAX_DEPTH>"
    if obj is None or isinstance(obj, (bool, int, float)):
        if isinstance(obj, int) and parent_key.lower() in ("id", "user_id", "account_id", "character_id", "player_id", "guild_id"):
            return 0
        return obj
    if isinstance(obj, str):
        kl = parent_key.lower()
        if kl in SENSITIVE_EXACT_KEYS or "token" in kl or "secret" in kl or "password" in kl or "email" in kl or "cookie" in kl:
            return "<REDACTED>"
        if "@" in obj or re.match(r"^[A-Za-z0-9_-]{24,}\.[A-Za-z0-9_-]{10,}", obj):
            return "<REDACTED_STR>"
        if kl == "name" and depth <= 3:
            return "<REDACTED_NAME>"
        if len(obj) > 160:
            return obj[:160] + "...<TRUNC>"
        return obj
    if isinstance(obj, list):
        total = len(obj)
        sample = [scrub(x, parent_key, depth + 1) for x in obj[:3]]
        if total > 3:
            sample.append(f"<... {total} items total>")
        return sample
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            ks = str(k)
            if ks.lower() in SENSITIVE_EXACT_KEYS:
                out[ks] = "<REDACTED>"
            else:
                out[ks] = scrub(v, ks, depth + 1)
        return out
    return str(type(obj))


def main() -> int:
    email = os.environ.get("IDLEARC_EMAIL", "").strip()
    password = os.environ.get("IDLEARC_PASSWORD", "")
    if not email or not password:
        print("ERROR: IDLEARC_EMAIL or IDLEARC_PASSWORD secret is empty")
        return 1

    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    def req(url: str, data: bytes | None = None, headers: dict | None = None, method: str | None = None):
        hdrs = {"User-Agent": ua, "Accept": "text/html,application/json,*/*;q=0.9"}
        if headers:
            hdrs.update(headers)
        r = urllib.request.Request(url, data=data, headers=hdrs, method=method)
        return opener.open(r, timeout=25)

    # 1. Inspect /login HTML
    resp = req(f"{BASE}/login")
    login_html = resp.read().decode("utf-8", errors="replace")
    parser = LoginPageParser()
    parser.feed(login_html)

    print("=== LOGIN PAGE STRUCTURE ===")
    print("Cookie names after GET /login:", [c.name for c in cj])
    print("Meta keys:", list(parser.meta.keys()))
    for idx, f in enumerate(parser.forms):
        safe_inputs = [{"name": i["name"], "type": i["type"], "has_val": bool(i["value"])} for i in f["inputs"]]
        print(f"Form #{idx}: method={f['method']} action={f['action']} inputs={safe_inputs}")
    for sc in parser.scripts:
        if sc.startswith("SRC:"):
            print("Script:", sc)
        else:
            api_refs = re.findall(r"['\"](/api/[^'\"]+|/login[^'\"]*)['\"]", sc)
            if api_refs:
                print("Inline script API refs:", api_refs)

    # 2. Build login payload from form
    form_action = "/login"
    payload_dict: dict[str, str] = {}
    csrf_val = ""
    if parser.forms:
        f0 = parser.forms[0]
        if f0["action"]:
            form_action = f0["action"]
        for inp in f0["inputs"]:
            nm = inp["name"]
            if not nm:
                continue
            tp = inp["type"].lower()
            if "csrf" in nm.lower():
                csrf_val = inp["value"]
                payload_dict[nm] = inp["value"]
            elif tp in ("email", "text") or "email" in nm.lower() or "user" in nm.lower():
                payload_dict[nm] = email
            elif tp == "password" or "pass" in nm.lower():
                payload_dict[nm] = password
            elif tp == "checkbox":
                payload_dict[nm] = inp["value"] or "on"
            elif tp != "submit":
                payload_dict[nm] = inp["value"]
    else:
        payload_dict = {"email": email, "password": password}

    if not csrf_val:
        for mk, mv in parser.meta.items():
            if "csrf" in mk.lower():
                csrf_val = mv

    post_url = urllib.parse.urljoin(f"{BASE}/login", form_action)
    encoded = urllib.parse.urlencode(payload_dict).encode("utf-8")
    post_headers = {
        "Content-Type": "application/x-www-form-urlencoded",
        "Origin": BASE,
        "Referer": f"{BASE}/login",
    }
    if csrf_val:
        post_headers["X-CSRFToken"] = csrf_val
        post_headers["X-CSRF-Token"] = csrf_val

    try:
        post_resp = req(post_url, data=encoded, headers=post_headers, method="POST")
        print("POST login final URL:", post_resp.geturl(), "status:", post_resp.status)
    except urllib.error.HTTPError as e:
        print("POST form login HTTPError:", e.code, e.reason)

    print("Cookie names after POST /login:", [c.name for c in cj])

    # 3. Fetch /api/dashboard
    print("\n=== FETCHING /api/dashboard ===")
    try:
        dash_resp = req(
            f"{BASE}/api/dashboard",
            headers={"Accept": "application/json", "Referer": f"{BASE}/app/"},
        )
        final_url = dash_resp.geturl()
        raw_bytes = dash_resp.read()
        ct = dash_resp.headers.get("Content-Type", "")
        print(f"GET /api/dashboard -> status={dash_resp.status} final_url={final_url} content_type={ct} bytes={len(raw_bytes)}")
        text = raw_bytes.decode("utf-8", errors="replace")
        try:
            data = json.loads(text)
            print("Top-level keys:", list(data.keys()) if isinstance(data, dict) else type(data).__name__)
            scrubbed = scrub(data)
            formatted = json.dumps(scrubbed, ensure_ascii=False, indent=2)
            # Print in clean chunks up to 45KB
            print("=== SCRUBBED /api/dashboard STRUCTURE ===")
            print(formatted[:45000])
            if len(formatted) > 45000:
                print(f"\n... <truncated from {len(formatted)} chars> ...")
        except Exception as je:
            print("Response was not JSON:", je)
            print("First 400 chars (redacted):", re.sub(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+", "<EMAIL>", text[:400]))
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")[:300]
        print("GET /api/dashboard HTTPError:", e.code, err_body)

    # 4. Also scan main.dart.js for /api/ routes so we know related read-only endpoints
    try:
        js_resp = req(f"{BASE}/app/main.dart.js")
        js_text = js_resp.read().decode("utf-8", errors="replace")
        routes = sorted(set(re.findall(r"/api/[a-zA-Z0-9_/-]{2,45}", js_text)))
        print("\n=== DISCOVERED /api/* ROUTES IN WEB CLIENT ===")
        for rt in routes[:120]:
            print(" ", rt)
    except Exception as e:
        print("main.dart.js scan skipped:", e)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
