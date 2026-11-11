#!/usr/bin/env python3
"""
Safe schema discovery for https://idlearc.com/api/dashboard (Laravel Sanctum / Inertia flow).
Strictly redacts all credentials, cookies, tokens, emails, character/guild names, and IDs.
"""
from __future__ import annotations

import http.cookiejar
import json
import os
import re
import traceback
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
        if "@" in obj or re.match(r"^[A-Za-z0-9_-]{28,}$", obj):
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
    import requests  # preinstalled on ubuntu-latest

    email = os.environ.get("IDLEARC_EMAIL", "").strip()
    password = os.environ.get("IDLEARC_PASSWORD", "")
    lines: list[str] = [f"email_len={len(email)} pass_len={len(password)}"]

    s = requests.Session()
    s.headers.update({
        "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
    })

    r0 = s.get(f"{BASE}/login", timeout=20)
    xsrf = urllib.parse.unquote(s.cookies.get("XSRF-TOKEN", ""))
    lines.append(f"GET_login={r0.status_code} cookies={list(s.cookies.keys())} xsrf_len={len(xsrf)}")

    # Inertia / Laravel login POST (allow_redirects=False so we see exact status & Set-Cookie!)
    r_login = s.post(
        f"{BASE}/login",
        json={"email": email, "password": password, "remember": True},
        headers={
            "Accept": "application/json, text/plain, */*",
            "X-Requested-With": "XMLHttpRequest",
            "X-XSRF-TOKEN": xsrf,
            "Origin": BASE,
            "Referer": f"{BASE}/login",
        },
        allow_redirects=False,
        timeout=20,
    )
    loc = r_login.headers.get("Location", "") or r_login.headers.get("X-Inertia-Location", "")
    lines.append(f"POST_login={r_login.status_code} loc={loc} cookies_now={list(s.cookies.keys())}")
    if r_login.status_code >= 400:
        # Print safe error keys only (no personal info)
        try:
            ej = r_login.json()
            lines.append(f"POST_login_err={scrub(ej)}")
        except Exception:
            lines.append(f"POST_login_body_head={r_login.text[:160]}")

    xsrf2 = urllib.parse.unquote(s.cookies.get("XSRF-TOKEN", ""))
    r_dash = s.get(
        f"{BASE}/api/dashboard",
        headers={
            "Accept": "application/json",
            "X-Requested-With": "XMLHttpRequest",
            "X-XSRF-TOKEN": xsrf2,
            "Referer": f"{BASE}/app/",
        },
        timeout=25,
    )
    lines.append(f"GET_dashboard={r_dash.status_code} ct={r_dash.headers.get('Content-Type', '')} bytes={len(r_dash.content)}")

    if r_dash.status_code != 200:
        lines.append(f"Dash_non200={r_dash.text[:250]}")
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
