#!/usr/bin/env python3
import json
import os
import urllib.request

BASE = "https://idlearc.com"

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
    # 1. Exact itemization.drop_bonuses from public /api/config/game
    cfg = req_json("/api/config/game")
    itemiz = cfg.get("itemization", {})
    drop_bonuses = itemiz.get("drop_bonuses", {})
    print("::notice::DROP_BONUSES_FULL=" + json.dumps(drop_bonuses, ensure_ascii=False)[:1800])

    # 2. Login & inspect /api/inventory pagination metadata
    email = os.environ["IDLEARC_EMAIL"]
    password = os.environ["IDLEARC_PASSWORD"]
    tok = req_json("/api/login", method="POST", body={
        "email": email, "password": password, "device_name": "IdleArcSchemaProbe"
    })["token"]

    inv1 = req_json("/api/inventory", token=tok)
    pag = inv1.get("characterItemsPaginated", {})
    pag_meta = {k: v for k, v in pag.items() if k != "data"}
    p1_ids = [it.get("id") for it in pag.get("data", [])]
    print("::notice::INV_PAG_META=" + json.dumps({"meta": pag_meta, "p1_ids": p1_ids, "root_keys": list(inv1.keys())}, ensure_ascii=False)[:1800])

    # Test next_page_url and candidate pagination params
    next_url = pag.get("next_page_url")
    tests = {}
    for label, u in [
        ("next_page_url", next_url),
        ("page_2", "/api/inventory?page=2"),
        ("items_page_2", "/api/inventory?items_page=2"),
        ("character_items_page_2", "/api/inventory?character_items_page=2"),
        ("inv_items_ep", "/api/inventory/items?page=2"),
        ("per_page_200", "/api/inventory?per_page=200"),
    ]:
        if not u:
            continue
        try:
            res = req_json(u, token=tok)
            rp = res.get("characterItemsPaginated", res)
            rdata = rp.get("data", []) if isinstance(rp, dict) else []
            tests[label] = {
                "cur": rp.get("current_page") if isinstance(rp, dict) else None,
                "cnt": len(rdata),
                "ids": [x.get("id") for x in rdata[:5] if isinstance(x, dict)],
            }
        except Exception as ex:
            tests[label] = str(ex)
    print("::notice::PAGINATION_TESTS=" + json.dumps(tests, ensure_ascii=False)[:1800])

    # Collect all unique items if pagination works via whichever method succeeded
    seen = {}
    for it in pag.get("data", []):
        seen[it["id"]] = it

    # Try following next_page_url chain first
    cur_next = next_url
    hops = 0
    while cur_next and hops < 15:
        hops += 1
        r = req_json(cur_next, token=tok)
        rp = r.get("characterItemsPaginated", r)
        for it in rp.get("data", []):
            seen[it["id"]] = it
        nxt = rp.get("next_page_url")
        if not nxt or nxt == cur_next:
            break
        cur_next = nxt

    # Summarize all unique items & all fields of 1 sample item + distribution of drop_bonus_type
    sample_item = next(iter(seen.values())) if seen else {}
    sample_slim = {k: v for k, v in sample_item.items() if k != "affix_positions"}
    dbt_counts = {}
    for it in seen.values():
        k = str(it.get("drop_bonus_type"))
        dbt_counts[k] = dbt_counts.get(k, 0) + 1
    print("::notice::UNIQUE_ITEMS_SUMMARY=" + json.dumps({
        "unique_count": len(seen),
        "drop_bonus_counts": dbt_counts,
        "sample_item_keys_vals": sample_slim,
    }, ensure_ascii=False)[:1800])

if __name__ == "__main__":
    main()
