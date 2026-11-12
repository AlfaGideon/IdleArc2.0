#!/usr/bin/env python3
import json
import os
import subprocess
import urllib.error
import urllib.request

BASE = "https://idlearc.com"

def call_api(path, token, method="GET", body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {token}",
        "User-Agent": "Dart/3.3 (dart:io)",
    }
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=12) as r:
        return json.loads(r.read().decode("utf-8"))

def main():
    login_req = urllib.request.Request(
        BASE + "/api/login",
        data=json.dumps({
            "email": os.environ["IDLEARC_EMAIL"],
            "password": os.environ["IDLEARC_PASSWORD"],
            "device_name": "IdleArcProbe",
        }).encode("utf-8"),
        headers={"Accept": "application/json", "Content-Type": "application/json", "User-Agent": "IdleArcHelper/4.0"},
        method="POST",
    )
    with urllib.request.urlopen(login_req, timeout=15) as r:
        tok = json.loads(r.read().decode("utf-8"))["token"]

    # 1. Inspect ALL boss / cooldown / timer / fight keys in /api/dashboard!
    dash = call_api("/api/dashboard", tok)
    timer_keys = {}
    kws = ("boss", "cool", "time", "expir", "ready", "fight", "dungeon", "guild", "raid", "world", "hunt", "trial", "tower", "arena", "hatch", "egg", "boost")
    for k, v in dash.items():
        kl = k.lower()
        if any(w in kl for w in kws):
            if isinstance(v, (str, int, float, bool)) or v is None:
                timer_keys[k] = v
            elif isinstance(v, dict):
                timer_keys[k] = {sk: sv for sk, sv in list(v.items())[:12] if not isinstance(sv, (list, dict))}
            elif isinstance(v, list):
                timer_keys[k] = f"list({len(v)}):" + json.dumps(v[:2], ensure_ascii=False)[:180]
    print("::notice::DASH_TIMERS_AND_BOSSES=" + json.dumps(timer_keys, ensure_ascii=False)[:1850])

    # 2. Test GET + POST API routes across IdleArc!
    get_routes = [
        "/api/boss", "/api/bosses", "/api/world-boss", "/api/boss-fight",
        "/api/dungeon", "/api/dungeons", "/api/guild", "/api/guild-boss",
        "/api/blacksmith", "/api/smithy", "/api/anvil", "/api/workshop",
        "/api/reforge", "/api/enchant", "/api/enchanting", "/api/upgrade",
        "/api/awaken", "/api/awakening", "/api/salvage", "/api/bulk-salvage",
        "/api/gear-presets", "/api/item-presets", "/api/equipment-presets", "/api/presets",
        "/api/loadouts", "/api/talismans", "/api/pets", "/api/runes", "/api/skills",
        "/api/market", "/api/character", "/api/combat", "/api/zones", "/api/quests",
        "/api/inventory-items?page=2", "/api/inventory/character-items?page=2",
        "/api/bag-items?page=2", "/api/unequipped-items",
        "/api/inventory?characterItemsPage=2", "/api/inventory?bagPage=2",
        "/api/inventory?bag_page=2", "/api/inventory?itemsPage=2",
    ]
    ok_get = {}
    for rt in get_routes:
        try:
            res = call_api(rt, tok, "GET")
            if isinstance(res, dict):
                info = {}
                for k, v in res.items():
                    if isinstance(v, list) and len(v) > 0:
                        info[k] = f"list({len(v)})"
                    elif isinstance(v, dict) and "data" in v:
                        d = v.get("data", [])
                        id0 = d[0].get("id") if d and isinstance(d[0], dict) else None
                        info[k] = f"pag(cur={v.get('current_page')},tot={v.get('total')},n={len(d)},id0={id0})"
                ok_get[rt] = info if info else list(res.keys())[:10]
            elif isinstance(res, list):
                ok_get[rt] = f"root_list({len(res)})"
        except urllib.error.HTTPError as he:
            if he.code != 404:
                ok_get[rt] = f"HTTP_{he.code}"
        except Exception as ex:
            ok_get[rt] = str(ex)[:25]
    print("::notice::OK_GET_ROUTES=" + json.dumps(ok_get, ensure_ascii=False)[:1850])

    # 3. Test POST pagination/filter routes (405 vs 422 vs 200 reveals existing routes!)
    post_routes = [
        ("/api/inventory/items", {"page": 2}),
        ("/api/inventory/filter", {"page": 2}),
        ("/api/inventory/search", {"page": 2}),
        ("/api/inventory/paginated", {"page": 2}),
        ("/api/character-items", {"page": 2}),
    ]
    ok_post = {}
    for rt, b in post_routes:
        try:
            res = call_api(rt, tok, "POST", b)
            ok_post[rt] = list(res.keys())[:8] if isinstance(res, dict) else "ok"
        except urllib.error.HTTPError as he:
            if he.code != 404:
                ok_post[rt] = f"HTTP_{he.code}"
        except Exception as ex:
            ok_post[rt] = str(ex)[:25]
    print("::notice::OK_POST_ROUTES=" + json.dumps(ok_post, ensure_ascii=False)[:1850])

    # 4. Also check ALL remaining top-level keys of /api/dashboard we haven't inspected!
    all_dash_keys = [
        f"{k}:list({len(v)})" if isinstance(v, list)
        else (f"{k}:dict({len(v)})" if isinstance(v, dict) else k)
        for k, v in dash.items()
    ]
    print("::notice::ALL_DASH_KEYS=" + json.dumps(all_dash_keys, ensure_ascii=False)[:1850])

if __name__ == "__main__":
    main()
