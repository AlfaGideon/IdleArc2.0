#!/usr/bin/env python3
import json
import os
import re
import subprocess
import urllib.request

BASE = "https://idlearc.com"

def raw_api(path, token):
    req = urllib.request.Request(BASE + path, headers={
        "Accept": "application/json",
        "Authorization": f"Bearer {token}",
        "X-Requested-With": "XMLHttpRequest",
        "User-Agent": "Dart/3.3 (dart:io)",
    })
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode("utf-8"))

def main():
    login_req = urllib.request.Request(
        BASE + "/api/login",
        data=json.dumps({
            "email": os.environ["IDLEARC_EMAIL"],
            "password": os.environ["IDLEARC_PASSWORD"],
            "device_name": "IdleArcProbe",
        }).encode("utf-8"),
        headers={"Accept": "application/json", "Content-Type": "application/json", "User-Agent": "Dart/3.3 (dart:io)"},
        method="POST",
    )
    with urllib.request.urlopen(login_req, timeout=20) as r:
        tok = json.loads(r.read().decode("utf-8"))["token"]

    p1 = raw_api("/api/inventory", tok)
    id0 = p1["characterItemsPaginated"]["data"][0]["id"]

    # 1. Test query params & subroutes with Dart User-Agent + X-Requested-With
    sub_tests = {}
    for p in [
        "/api/inventory?page=2",
        "/api/inventory?per_page=100",
        "/api/inventory?perPage=100",
        "/api/inventory?limit=100",
        "/api/inventory?all=1",
        "/api/inventory/all",
        "/api/inventory/list",
        "/api/inventory/filter?page=2",
        "/api/inventory/page/2",
        "/api/character/inventory?page=2",
        "/api/equipment/inventory",
        "/api/bag",
        "/api/loot",
    ]:
        try:
            res = raw_api(p, tok)
            cp = res.get("characterItemsPaginated", res) if isinstance(res, dict) else {}
            d = cp.get("data", []) if isinstance(cp, dict) else (res if isinstance(res, list) else [])
            sub_tests[p] = {
                "cur": cp.get("current_page") if isinstance(cp, dict) else None,
                "len": len(d),
                "diff": (d[0].get("id") != id0) if d and isinstance(d[0], dict) else False,
            }
        except Exception as e:
            sub_tests[p] = str(e)[:32]
    print("::notice::API_SUB_TESTS=" + json.dumps(sub_tests, ensure_ascii=False)[:1800])

    # 2. Dump /login DOM via preinstalled google-chrome to get Vite JS assets!
    dom = subprocess.check_output(
        ["google-chrome", "--headless", "--no-sandbox", "--disable-gpu", "--dump-dom", "https://idlearc.com/login"],
        text=True, stderr=subprocess.DEVNULL, timeout=25
    )
    js_paths = sorted(set(re.findall(r'/build/assets/[^"\'>\s]+\.js', dom)))
    js_hits = {"login_scripts": js_paths[:10]}
    for jp in js_paths[:6]:
        js_body = subprocess.check_output(
            ["curl", "-sL", "-A", "Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0 Safari/537.36", BASE + jp],
            text=True, timeout=20
        )
        inv_chunks = sorted(set(re.findall(r'[A-Za-z0-9_\-]*[Ii]nventory[A-Za-z0-9_\-\.]*\.js', js_body)))
        api_mentions = sorted(set(re.findall(r'/api/[A-Za-z0-9_/\-]+', js_body)))
        if inv_chunks or api_mentions:
            js_hits[jp] = {"inv_chunks": inv_chunks[:10], "apis": api_mentions[:30]}
        for ic in inv_chunks[:4]:
            ic_body = subprocess.check_output(
                ["curl", "-sL", "-A", "Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0 Safari/537.36", BASE + "/build/assets/" + ic],
                text=True, timeout=20
            )
            ctx = re.findall(r'.{0,50}(?:characterItemsPaginated|inventory|per_page|page).{0,70}', ic_body)
            ic_apis = sorted(set(re.findall(r'/[a-zA-Z0-9_/\-]+', ic_body)))
            js_hits[ic] = {"ctx": ctx[:10], "routes": [x for x in ic_apis if "inv" in x.lower() or "item" in x.lower()][:20]}

    print("::notice::CHROME_JS_HITS=" + json.dumps(js_hits, ensure_ascii=False)[:1850])

if __name__ == "__main__":
    main()
