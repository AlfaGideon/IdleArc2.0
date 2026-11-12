#!/usr/bin/env python3
import json
import os
import re
import urllib.request

BASE = "https://idlearc.com"

def req_text(path_or_url, headers=None):
    url = path_or_url if path_or_url.startswith("http") else (BASE + path_or_url)
    h = {"User-Agent": "Mozilla/5.0"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read().decode("utf-8", errors="ignore")

def req_json(path_or_url, method="GET", token=None, body=None):
    url = path_or_url if path_or_url.startswith("http") else (BASE + path_or_url)
    headers = {"Accept": "application/json", "User-Agent": "IdleArcProbe/1.0"}
    data = None
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.loads(r.read().decode("utf-8"))

def main():
    # 1. Print ALL drop_bonuses compactly (key -> {name, stat, cat, t6})
    cfg = req_json("/api/config/game")
    dbs = cfg.get("itemization", {}).get("drop_bonuses", {})
    compact_dbs = {
        k: {"name": v.get("name"), "stat": v.get("stat"), "cat": v.get("category"), "t6": v.get("values", {}).get("6")}
        for k, v in dbs.items()
    }
    print("::notice::ALL_DROP_BONUSES=" + json.dumps(compact_dbs, ensure_ascii=False))

    # 2. Inspect Vite manifest / build assets from HTML to find exact inventory pagination URL!
    html = req_text("/login")
    asset_urls = re.findall(r'(/build/assets/[^"\'>\s]+\.js)', html)
    manifest_info = {}
    try:
        m_raw = req_text("/build/manifest.json")
        m_json = json.loads(m_raw)
        inv_assets = [v.get("file") for k, v in m_json.items() if "nventory" in k or "tem" in k or "app" in k]
        manifest_info["inv_assets"] = inv_assets[:15]
        for af in inv_assets[:8]:
            js = req_text("/build/" + af)
            apis = sorted(set(re.findall(r'/api/[a-zA-Z0-9_/\-\?\=\&\$]+', js)))
            pags = sorted(set(re.findall(r'.{0,40}characterItemsPaginated.{0,60}', js)))
            if apis or pags:
                manifest_info[af] = {"apis": apis[:25], "pags": pags[:5]}
    except Exception as ex:
        manifest_info["manifest_err"] = str(ex)
        for au in asset_urls[:5]:
            js = req_text(au)
            apis = sorted(set(re.findall(r'/api/[a-zA-Z0-9_/\-\?\=\&]+', js)))
            if apis:
                manifest_info[au] = apis[:30]

    print("::notice::JS_INVENTORY_APIS=" + json.dumps(manifest_info, ensure_ascii=False)[:1800])

    # 3. Also test likely Laravel/API endpoints for paginated character items!
    email = os.environ["IDLEARC_EMAIL"]
    password = os.environ["IDLEARC_PASSWORD"]
    tok = req_json("/api/login", method="POST", body={
        "email": email, "password": password, "device_name": "IdleArcSchemaProbe"
    })["token"]

    candidates = [
        "/api/character/items?page=2",
        "/api/character-items?page=2",
        "/api/items?page=2",
        "/api/inventory/paginated?page=2",
        "/api/inventory?itemPage=2",
        "/api/inventory?inv_page=2",
        "/api/inventory?p=2",
    ]
    # Also add any /api/... discovered from JS that mentions inventory or item!
    for val in manifest_info.values():
        if isinstance(val, dict):
            for a in val.get("apis", []):
                base_ep = a.split("$")[0].rstrip("?&=")
                if "item" in base_ep.lower() or "inv" in base_ep.lower():
                    candidates.append(base_ep + ("&page=2" if "?" in base_ep else "?page=2"))

    ep_results = {}
    for c in dict.fromkeys(candidates):
        try:
            r = req_json(c, token=tok)
            rp = r.get("characterItemsPaginated", r) if isinstance(r, dict) else {}
            data = rp.get("data", r.get("items", [])) if isinstance(rp, dict) else []
            ep_results[c] = {
                "keys": list(r.keys())[:8] if isinstance(r, dict) else "list",
                "cur": rp.get("current_page") if isinstance(rp, dict) else None,
                "cnt": len(data) if isinstance(data, list) else -1,
                "first_id": data[0].get("id") if isinstance(data, list) and data and isinstance(data[0], dict) else None,
            }
        except Exception as e:
            ep_results[c] = str(e)[:60]
    print("::notice::CANDIDATE_EPS=" + json.dumps(ep_results, ensure_ascii=False)[:1800])

if __name__ == "__main__":
    main()
