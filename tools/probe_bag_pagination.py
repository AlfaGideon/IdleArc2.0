#!/usr/bin/env python3
import json
import os
import urllib.request

BASE = "https://idlearc.com"

def raw_req(path, method="GET", token=None, body=None, content_type="application/json"):
    url = BASE + path
    headers = {"Accept": "application/json", "User-Agent": "IdleArcHelper/4.0"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None
    if body is not None:
        headers["Content-Type"] = content_type
        data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode("utf-8"))

def main():
    tok = raw_req("/api/login", method="POST", body={
        "email": os.environ["IDLEARC_EMAIL"],
        "password": os.environ["IDLEARC_PASSWORD"],
        "device_name": "IdleArcSchemaProbe"
    })["token"]

    p1 = raw_req("/api/inventory", token=tok)
    p1_id = p1["characterItemsPaginated"]["data"][0]["id"]

    results = {"p1_first_id": p1_id}
    trials = [
        ("GET_json_body_page2", "/api/inventory?page=2", "GET", {"page": 2}, "application/json"),
        ("GET_form_body_page2", "/api/inventory?page=2", "GET", b"page=2", "application/x-www-form-urlencoded"),
        ("POST_page2", "/api/inventory", "POST", {"page": 2}, "application/json"),
        ("GET_slot_gloves", "/api/inventory?slot=gloves&type=gloves", "GET", None, "application/json"),
        ("GET_json_slot_gloves", "/api/inventory", "GET", {"slot": "gloves", "type": "gloves", "per_page": 100}, "application/json"),
        ("ep_character_items", "/api/character-items?page=2", "GET", None, "application/json"),
        ("ep_items", "/api/items?page=2", "GET", None, "application/json"),
        ("ep_forge", "/api/forge", "GET", None, "application/json"),
        ("ep_crafting", "/api/crafting", "GET", None, "application/json"),
        ("ep_presets", "/api/presets", "GET", None, "application/json"),
        ("ep_equipment", "/api/equipment", "GET", None, "application/json"),
    ]

    for name, path, meth, body, ctype in trials:
        try:
            r = raw_req(path, method=meth, token=tok, body=body, content_type=ctype)
            if isinstance(r, dict) and "characterItemsPaginated" in r:
                cp = r["characterItemsPaginated"]
                d = cp.get("data", [])
                results[name] = {"cur": cp.get("current_page"), "cnt": len(d), "id0": d[0]["id"] if d else None}
            elif isinstance(r, dict):
                # check if any key holds a list of items > 10!
                summary = {}
                for k, v in r.items():
                    if isinstance(v, list):
                        summary[k] = f"list({len(v)})"
                    elif isinstance(v, dict) and "data" in v:
                        summary[k] = f"pag(cur={v.get('current_page')},len={len(v.get('data',[]))})"
                results[name] = summary or list(r.keys())[:8]
        except Exception as e:
            results[name] = str(e)[:45]

    print("::notice::TRIAL_RESULTS=" + json.dumps(results, ensure_ascii=False)[:1850])

if __name__ == "__main__":
    main()
