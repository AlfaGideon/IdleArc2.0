#!/usr/bin/env python3
import concurrent.futures
import json
import os
import re
import urllib.error
import urllib.request

from curl_cffi import requests as creq

BASE = "https://idlearc.com"

def main():
    email = os.environ["IDLEARC_EMAIL"]
    password = os.environ["IDLEARC_PASSWORD"]

    # 1. Login via API for Bearer token
    s = creq.Session(impersonate="chrome124")
    r_login = s.post(
        BASE + "/api/login",
        json={"email": email, "password": password, "device_name": "IdleArcProbe"},
        headers={"Accept": "application/json"},
        timeout=20,
    )
    tok = r_login.json()["token"]
    auth_hdrs = {"Accept": "application/json", "Authorization": f"Bearer {tok}"}

    # 2. Fetch web /login with Chrome TLS impersonation to grab Vite JS bundles!
    js_routes = set()
    inv_snippets = []
    try:
        r_html = s.get(BASE + "/login", timeout=20)
        html = r_html.text
        assets = sorted(set(re.findall(r'/build/assets/[A-Za-z0-9_\-\.]+\.js', html)))
        # Check manifest too
        r_man = s.get(BASE + "/build/manifest.json", timeout=15)
        if r_man.status_code == 200 and r_man.text.strip().startswith("{"):
            mj = r_man.json()
            for k, v in mj.items():
                f = v.get("file", "")
                if f.endswith(".js"):
                    assets.append("/build/" + f)
        assets = list(dict.fromkeys(assets))

        # Scan app entry JS for lazy-loaded chunk filenames!
        extra_chunks = []
        for a in assets[:8]:
            jr = s.get(BASE + a, timeout=20)
            if jr.status_code == 200:
                txt = jr.text
                for m in re.findall(r'assets/[A-Za-z0-9_\-\.]+\.js', txt):
                    extra_chunks.append("/build/" + m)
                for ap in re.findall(r'/api/[A-Za-z0-9_/\-\?\=\&]+', txt):
                    js_routes.add(ap)

        all_js = list(dict.fromkeys(assets + extra_chunks))
        # Prioritize chunks mentioning Inventory, Bag, Item, Forge, Gear, Blacksmith
        prio_js = [x for x in all_js if any(w in x.lower() for w in ["inv", "item", "bag", "gear", "equip", "forge", "smith", "salvage", "app", "index"])]
        for pj in (prio_js + all_js)[:35]:
            jr = s.get(BASE + pj, timeout=15)
            if jr.status_code != 200:
                continue
            txt = jr.text
            for ap in re.findall(r'/api/[A-Za-z0-9_/\-]+', txt):
                js_routes.add(ap)
            for snip in re.findall(r'.{0,55}(?:characterItemsPaginated|/api/inventory|bag_cap|inventory_cap|inventoryCap).{0,85}', txt):
                if len(inv_snippets) < 12:
                    inv_snippets.append(f"{pj.split('/')[-1]}: {snip}")

        print("::notice::VITE_JS_ROUTES=" + json.dumps({
            "html_status": r_html.status_code,
            "js_cnt": len(all_js),
            "api_routes": sorted(js_routes)[:80],
        }, ensure_ascii=False)[:1850])
        print("::notice::VITE_INV_SNIPPETS=" + json.dumps(inv_snippets, ensure_ascii=False)[:1850])
    except Exception as ex:
        print("::notice::VITE_ERR=" + str(ex)[:500])

    # 3. Also login on Web Inertia session or test all discovered + candidate API routes!
    candidate_paths = sorted(js_routes) + [
        "/api/gear", "/api/backpack", "/api/armory", "/api/crafting", "/api/forge/items",
        "/api/blacksmith/items", "/api/inventory/bag", "/api/inventory/equip",
        "/api/character/equipment", "/api/character/gear", "/api/character/bag",
        "/api/items/bag", "/api/items/inventory", "/api/v1/inventory", "/api/v2/inventory",
        "/api/mobile/inventory", "/api/app/inventory", "/api/game/inventory",
        "/api/salvage/items", "/api/presets/gear", "/api/gear-presets/items",
        "/api/enchanting/items", "/api/reforge/items", "/api/upgrade/items",
    ]
    hits = {}
    for cp in dict.fromkeys(candidate_paths):
        if cp in ("/api/login", "/api/logout", "/api/register", "/api/dashboard", "/api/config/game", "/api/gems"):
            continue
        try:
            rr = s.get(BASE + cp, headers=auth_hdrs, timeout=10)
            if rr.status_code == 200 and rr.text.strip().startswith(("{", "[")):
                dj = rr.json()
                if isinstance(dj, dict):
                    summary = {}
                    for k, v in dj.items():
                        if isinstance(v, list):
                            summary[k] = f"list({len(v)})"
                        elif isinstance(v, (int, float)) and v in (61, 62, 96, 100):
                            summary[k] = v
                        elif isinstance(v, dict) and "data" in v:
                            summary[k] = f"pag(cur={v.get('current_page')},tot={v.get('total')},n={len(v.get('data',[]))})"
                    hits[cp] = summary if summary else list(dj.keys())[:10]
                elif isinstance(dj, list):
                    hits[cp] = f"list({len(dj)})"
        except Exception:
            pass
    print("::notice::LIVE_API_HITS=" + json.dumps(hits, ensure_ascii=False)[:1850])

if __name__ == "__main__":
    main()
