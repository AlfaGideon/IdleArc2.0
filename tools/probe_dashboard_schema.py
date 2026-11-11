#!/usr/bin/env python3
"""
Safe schema & endpoint discovery for https://idlearc.com/api/dashboard.
Strictly redacts all credentials, cookies, tokens, emails, user/character names, and account IDs,
and emits sanitized schema chunks via GitHub Actions ::notice:: annotations.
"""
from __future__ import annotations

import html.parser
import http.cookiejar
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request


BASE = "https://idlearc.com"

SENSITIVE_EXACT_KEYS = {
    "email", "e_mail", "mail", "password", "passwd", "pwd", "password_hash",
    "token", "access_token", "refresh_token", "id_token", "csrf_token", "_csrf_token",
    "session", "session_id", "sid", "secret", "api_key", "auth_token",
    "ip", "ip_address", "last_ip", "stripe_customer_id", "subscription_id",
    "google_id", "apple_id", "discord_id", "user_id", "account_id", "owner_id",
    "username", "display_name", "character_name", "hero_name", "player_name", "guild_name",
    "referral_code", "invite_code", "friend_code", "code", "uid", "uuid",
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
    if depth > 12:
        return "<MAX_DEPTH>"
    if obj is None or isinstance(obj, bool):
        return obj
    if isinstance(obj, (int, float)):
        if parent_key.lower() in ("id", "user_id", "account_id", "character_id", "player_id", "guild_id"):
            return 0
        return obj
    if isinstance(obj, str):
        kl = parent_key.lower()
        if kl in SENSITIVE_EXACT_KEYS or any(w in kl for w in ("token", "secret", "password", "email", "cookie")):
            return "<REDACTED>"
        if "@" in obj or re.match(r"^[A-Za-z0-9_-]{24,}$", obj):
            return "<REDACTED_STR>"
        if kl in ("name", "title", "tag", "nickname") and depth <= 4:
            return "<REDACTED_NAME>"
        if len(obj) > 100:
            return obj[:100] + "...<TRUNC>"
        return obj
    if isinstance(obj, list):
        total = len(obj)
        sample = [scrub(x, parent_key, depth + 1) for x in obj[:2]]
        if total > 2:
            sample.append(f"<... {total} items total>")
        return sample
    if isinstance(obj, dict):
        out = {}
        keys = list(obj.keys())
        for k in keys[:60]:
            ks = str(k)
            if ks.lower() in SENSITIVE_EXACT_KEYS:
                out[ks] = "<REDACTED>"
            else:
                out[ks] = scrub(obj[k], ks, depth + 1)
        if len(keys) > 60:
            out["__more_keys__"] = f"{len(keys) - 60} more keys"
        return out
    return str(type(obj))


def emit_notice(title: str, body: str) -> None:
    escaped = (
        body.replace("%", "%25")
        .replace("\r", "%0D")
        .replace("\n", "%0A")
    )
    print(f"::notice title={title}::{escaped}")


def main() -> int:
    email = os.environ.get("IDLEARC_EMAIL", "").strip()
    password = os.environ.get("IDLEARC_PASSWORD", "")
    if not email or not password:
        emit_notice("ProbeError", "IDLEARC_EMAIL or IDLEARC_PASSWORD is empty")
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

    report_lines: list[str] = []

    resp = req(f"{BASE}/login")
    login_html = resp.read().decode("utf-8", errors="replace")
    parser = LoginPageParser()
    parser.feed(login_html)

    report_lines.append(f"Cookies_GET_login={[c.name for c in cj]}")
    report_lines.append(f"Meta={list(parser.meta.keys())}")
    for idx, f in enumerate(parser.forms):
        safe_inputs = [{"name": i["name"], "type": i["type"], "has_val": bool(i["value"])} for i in f["inputs"]]
        report_lines.append(f"Form#{idx}: method={f['method']} action={f['action']} inputs={safe_inputs}")
    for sc in parser.scripts:
        if sc.startswith("SRC:"):
            report_lines.append(f"Script={sc}")
        else:
            api_refs = re.findall(r"['\"](/api/[^'\"]+|/login[^'\"]*)['\"]", sc)
            if api_refs:
                report_lines.append(f"InlineRefs={api_refs}")

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
        report_lines.append(f"POST_login_url={post_resp.geturl()} status={post_resp.status}")
    except urllib.error.HTTPError as e:
        report_lines.append(f"POST_login_HTTPError={e.code}")

    report_lines.append(f"Cookies_after_login={[c.name for c in cj]}")

    # Fetch /api/dashboard
    dash_json_str = ""
    try:
        dash_resp = req(
            f"{BASE}/api/dashboard",
            headers={"Accept": "application/json", "Referer": f"{BASE}/app/"},
        )
        raw_bytes = dash_resp.read()
        ct = dash_resp.headers.get("Content-Type", "")
        report_lines.append(
            f"GET_dashboard: status={dash_resp.status} url={dash_resp.geturl()} ct={ct} bytes={len(raw_bytes)}"
        )
        text = raw_bytes.decode("utf-8", errors="replace")
        try:
            data = json.loads(text)
            if isinstance(data, dict):
                report_lines.append(f"TopKeys={list(data.keys())}")
                for k, v in data.items():
                    if isinstance(v, dict):
                        report_lines.append(f"  dict[{k}] keys={list(v.keys())[:40]}")
                    elif isinstance(v, list):
                        first_keys = list(v[0].keys())[:35] if (v and isinstance(v[0], dict)) else type(v[0]).__name__ if v else "empty"
                        report_lines.append(f"  list[{k}] len={len(v)} elem={first_keys}")
                    else:
                        report_lines.append(f"  scalar[{k}] type={type(v).__name__}")
            scrubbed = scrub(data)
            dash_json_str = json.dumps(scrubbed, ensure_ascii=False, indent=2)
        except Exception as je:
            report_lines.append(f"NotJSON={je} head={text[:220]}")
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")[:220]
        report_lines.append(f"GET_dashboard_HTTPError={e.code} body={err_body}")

    # Scan main.dart.js for /api/* routes
    try:
        js_resp = req(f"{BASE}/app/main.dart.js")
        js_text = js_resp.read().decode("utf-8", errors="replace")
        routes = sorted(set(re.findall(r"/api/[a-zA-Z0-9_/-]{2,50}", js_text)))
        report_lines.append(f"WebClientRoutes({len(routes)})={routes[:80]}")
    except Exception as e:
        report_lines.append(f"JS_scan_err={e}")

    emit_notice("Overview", "\n".join(report_lines))

    if dash_json_str:
        chunk_sz = 3200
        for idx in range(0, min(len(dash_json_str), chunk_sz * 8), chunk_sz):
            part = dash_json_str[idx : idx + chunk_sz]
            emit_notice(f"DashboardSchema_{idx // chunk_sz + 1}", part)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
