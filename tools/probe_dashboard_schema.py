#!/usr/bin/env python3
"""
Safe schema discovery for https://idlearc.com/api/dashboard (Bearer token + web session).
Strictly redacts all credentials, cookies, tokens, emails, character/guild names, and IDs.
"""
from __future__ import annotations

import json
import os
import re
import traceback
import urllib.parse


BASE = "https://idlearc.com"

SENSITIVE_EXACT_KEYS = {
    "email", "e_mail", "mail", "password", "passwd", "pwd", "password_hash",
    "token", "access_token", "refresh_token", "id_token", "csrf_token", "_csrf_token",
    "session", "session_id", "sid", "secret", "api_key", "auth_token",
    "ip", "ip_address", "last_ip", "stripe_customer_id", "subscription_id",
    "google_id", "apple_id", "discord_id", "user_id", "account_id", "owner_id",
    "username", "display_name", "character_name", "hero_name", "player_name", "guild_name",
    "referral_code", "invite_code", "friend_code", "uid", "uuid",
}


def scrub(obj, parent_key: str = "", depth: int = 0):
    if depth > 14:
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
        if "@" in obj or re.match(r"^[A-Za-z0-9|_-]{28,}$", obj):
            return "<REDACTED_STR>"
        if kl in ("name", "nickname", "tag") and depth <= 3:
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
        for k in keys[:80]:
            ks = str(k)
            if ks.lower() in SENSITIVE_EXACT_KEYS:
                out[ks] = "<REDACTED>"
            else:
                out[ks] = scrub(obj[k], ks, depth + 1)
        if len(keys) > 80:
            out["__more_keys__"] = f"{len(keys) - 80} more keys"
        return out
    return str(type(obj))


def emit_notice(title: str, body: str) -> None:
    escaped = (
        body.replace("%", "%25")
        .replace("\r", "%0D")
        .replace("\n", "%0A")
    )
    print(f"::notice title={title}::{escaped}")


def run_probe() -> None:
    import requests

    email = os.environ.get("IDLEARC_EMAIL", "").strip()
    password = os.environ.get("IDLEARC_PASSWORD", "")
    lines: list[str] = []

    s = requests.Session()
    s.headers.update({
        "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
    })

    # A) Try direct mobile/API token endpoints first!
    bearer_token = ""
    for ep in ["/api/login", "/api/auth/login", "/api/v1/login", "/api/sanctum/token"]:
        try:
            ra = s.post(
                f"{BASE}{ep}",
                json={"email": email, "password": password, "device_name": "DruidHelper"},
                headers={"Accept": "application/json"},
                timeout=15,
            )
            lines.append(f"POST {ep} -> {ra.status_code} ct={ra.headers.get('Content-Type', '')}")
            if ra.status_code in (200, 201):
                aj = ra.json()
                lines.append(f"  {ep} keys={list(aj.keys()) if isinstance(aj, dict) else type(aj).__name__}")
                if isinstance(aj, dict):
                    for tk in ("token", "access_token", "bearer_token", "api_token"):
                        if isinstance(aj.get(tk), str) and aj[tk]:
                            bearer_token = aj[tk]
                            break
                    if not bearer_token and isinstance(aj.get("data"), dict):
                        for tk in ("token", "access_token"):
                            if isinstance(aj["data"].get(tk), str):
                                bearer_token = aj["data"][tk]
                                break
                if bearer_token:
                    break
        except Exception as e:
            lines.append(f"POST {ep} err={e}")

    # B) Also do web login and inspect /app/ + main.dart.js if needed
    if not bearer_token:
        r0 = s.get(f"{BASE}/login", timeout=15)
        xsrf = urllib.parse.unquote(s.cookies.get("XSRF-TOKEN", ""))
        s.post(
            f"{BASE}/login",
            json={"email": email, "password": password, "remember": True},
            headers={
                "Accept": "application/json, text/plain, */*",
                "X-Requested-With": "XMLHttpRequest",
                "X-XSRF-TOKEN": xsrf,
                "Origin": BASE,
                "Referer": f"{BASE}/login",
            },
            allow_redirects=True,
            timeout=15,
        )
        # Check logged-in homepage / profile / app HTML for token exchange routes
        for page_ep in ["/", "/dashboard", "/profile", "/app/"]:
            rp = s.get(f"{BASE}{page_ep}", allow_redirects=True, timeout=15)
            api_mentions = sorted(set(re.findall(r"['\"](/[a-zA-Z0-9_/-]*api[a-zA-Z0-9_/-]*|/web[a-zA-Z0-9_/-]*|/token[a-zA-Z0-9_/-]*)['\"]", rp.text)))
            lines.append(f"GET {page_ep} -> {rp.status_code} url={rp.url} mentions={api_mentions[:15]}")

        # Search main.dart.js for 'dashboard' and 'login' context
        rjs = s.get(f"{BASE}/app/main.dart.js", timeout=25)
        dash_hits = re.findall(r'.{0,55}dashboard.{0,55}', rjs.text)
        auth_hits = re.findall(r'["\'](?:api/)?(?:auth|login|session|web-session|token)[a-zA-Z0-9_/-]*["\']', rjs.text)
        lines.append(f"JS auth_hits={sorted(set(auth_hits))[:30]}")
        lines.append(f"JS dash_hits={dash_hits[:12]}")

    # C) Call /api/dashboard (with Bearer if found!)
    hdrs = {"Accept": "application/json", "X-Requested-With": "XMLHttpRequest"}
    if bearer_token:
        hdrs["Authorization"] = f"Bearer {bearer_token}"
        lines.append(f"Got Bearer token! (len={len(bearer_token)})")

    r_dash = s.get(f"{BASE}/api/dashboard", headers=hdrs, timeout=25)
    lines.append(f"GET /api/dashboard -> {r_dash.status_code} bytes={len(r_dash.content)}")

    if r_dash.status_code != 200:
        lines.append(f"Dash_body={r_dash.text[:200]}")
        emit_notice("Overview", "\n".join(lines)[:3800])
        return

    data = r_dash.json()
    if isinstance(data, dict):
        lines.append(f"TopKeys={list(data.keys())}")
        for k, v in data.items():
            if isinstance(v, dict):
                lines.append(f"  dict[{k}] ({len(v)} keys): {list(v.keys())[:55]}")
                for sk, sv in list(v.items())[:25]:
                    if isinstance(sv, dict):
                        lines.append(f"    dict[{k}.{sk}] ({len(sv)} keys): {list(sv.keys())[:35]}")
                    elif isinstance(sv, list):
                        ek = list(sv[0].keys())[:30] if (sv and isinstance(sv[0], dict)) else (type(sv[0]).__name__ if sv else "empty")
                        lines.append(f"    list[{k}.{sk}] (len={len(sv)}): elem={ek}")
            elif isinstance(v, list):
                ek = list(v[0].keys())[:35] if (v and isinstance(v[0], dict)) else (type(v[0]).__name__ if v else "empty")
                lines.append(f"  list[{k}] (len={len(v)}): elem={ek}")
            else:
                lines.append(f"  scalar[{k}]: {type(v).__name__}")

    emit_notice("Overview", "\n".join(lines)[:3800])

    if isinstance(data, dict):
        idx = 1
        for k, v in data.items():
            if idx > 8:
                break
            sub_str = json.dumps({k: scrub(v, k, 1)}, ensure_ascii=False, indent=2)
            emit_notice(f"Key_{idx}_{k}", sub_str[:3500])
            idx += 1


if __name__ == "__main__":
    try:
        run_probe()
    except Exception as exc:
        emit_notice("ProbeException", f"{type(exc).__name__}: {exc}\n{traceback.format_exc()[-800:]}")
