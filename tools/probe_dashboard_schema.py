#!/usr/bin/env python3
from __future__ import annotations
import json, os, re, traceback

BASE = "https://idlearc.com"

def emit_notice(title: str, body: str) -> None:
    escaped = body.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::notice title={title}::{escaped[:3900]}")

def main() -> None:
    import requests
    s = requests.Session()
    cfg = s.get(f"{BASE}/api/config/game", headers={"Accept": "application/json"}, timeout=25).json()

    # 1. Exact pet_affixes & pet_evolve & legendary/mythic pet_bonuses
    pb = cfg.get("pet_bonuses") or {}
    emit_notice("1_PetAffixesFull", json.dumps({
        "pet_affixes": cfg.get("pet_affixes"),
        "pet_evolve": cfg.get("pet_evolve"),
        "pet_bonuses_top": {k: pb.get(k) for k in list(pb.keys())[-3:]},
    }, ensure_ascii=False, indent=2))

    # 2. Itemization keys + drop bonuses + auto_salvage
    itm = cfg.get("itemization") or {}
    emit_notice("2_ItemizationDropSalvage", json.dumps({
        "itemization_keys": list(itm.keys()) if isinstance(itm, dict) else str(type(itm)),
        "drop_related": {k: v for k, v in (itm.items() if isinstance(itm, dict) else []) if "drop" in k.lower() or "bonus" in k.lower() or "implicit" in k.lower()},
        "auto_salvage": cfg.get("auto_salvage"),
        "item_variants": cfg.get("item_variants"),
    }, ensure_ascii=False, indent=2))

    # 3. Class 2 & Class 3 skills
    trees = cfg.get("class_skill_trees") or {}
    c23 = {}
    for cid in ("2", "3"):
        c23[cid] = [
            {"id": sk.get("id"), "b": br.get("name"), "n": sk.get("name"), "max": sk.get("max_rank"), "d": sk.get("description")}
            for br in (trees.get(cid) or []) for sk in (br.get("skills") or [])
        ]
    emit_notice("3_Classes_2_and_3", json.dumps(c23, ensure_ascii=False))

    # 4. Gems rarities 3..6 & secondaries
    gems_cfg = cfg.get("gems") or {}
    emit_notice("4_GemsRaritiesAndSec", json.dumps({
        "rarities": (gems_cfg.get("rarities") or {}),
        "secondaries": gems_cfg.get("secondaries") or gems_cfg.get("secondary_stats") or {k: v for k, v in gems_cfg.items() if k not in ("families", "rarities")},
    }, ensure_ascii=False, indent=2))

    # 5. Find ALL frontend /api/ routes from build/manifest.json!
    routes = set()
    for mf_path in ("/build/manifest.json", "/build/.vite/manifest.json"):
        rm = s.get(BASE + mf_path, timeout=10)
        if rm.status_code == 200:
            try:
                mf = rm.json()
                js_files = [v.get("file") for v in mf.values() if isinstance(v, dict) and str(v.get("file", "")).endswith(".js")]
                for jf in js_files[:25]:
                    jt = s.get(f"{BASE}/build/{jf}", timeout=15).text
                    for m in re.findall(r'/api/[a-zA-Z0-9_/{}-]+', jt):
                        routes.add(m)
            except Exception:
                pass
    emit_notice("5_AllFrontendApiRoutes", json.dumps(sorted(routes), ensure_ascii=False, indent=2))

    # 6. Test authenticated GET on any discovered inventory / gem / loot / stash routes!
    token = s.post(
        f"{BASE}/api/login",
        json={"email": os.environ["IDLEARC_EMAIL"].strip(), "password": os.environ["IDLEARC_PASSWORD"], "device_name": "IdleArcHelperProbe"},
        headers={"Accept": "application/json"}, timeout=15,
    ).json()["token"]
    hdrs = {"Accept": "application/json", "Authorization": f"Bearer {token}"}

    cand_gets = [r for r in sorted(routes) if "{" not in r and any(w in r.lower() for w in ("inv", "item", "gem", "loot", "bag", "stash", "preset", "gear"))]
    for extra in ("/api/inventory", "/api/items", "/api/gems", "/api/character/items", "/api/character/gems"):
        if extra not in cand_gets:
            cand_gets.append(extra)
    probe_res = {}
    for ep in cand_gets[:15]:
        try:
            rr = s.get(BASE + ep, headers=hdrs, timeout=10)
            info = {"status": rr.status_code}
            if rr.status_code == 200 and "json" in rr.headers.get("content-type", ""):
                jj = rr.json()
                if isinstance(jj, dict):
                    info["keys"] = list(jj.keys())[:20]
                    for kk, vv in jj.items():
                        if isinstance(vv, list):
                            info[f"len_{kk}"] = len(vv)
                            if vv and isinstance(vv[0], dict):
                                info[f"sample_{kk}_keys"] = list(vv[0].keys())[:15]
                elif isinstance(jj, list):
                    info["list_len"] = len(jj)
            probe_res[ep] = info
        except Exception as e:
            probe_res[ep] = {"err": str(e)[:80]}
    emit_notice("6_InventoryAndGemEndpoints", json.dumps(probe_res, ensure_ascii=False, indent=2))

    # 7. Also search cfg["items"] or JS for drop_bonus_type values & extra_kill_chance > 100 mechanics!
    items_sample = (cfg.get("items") or [])[:5] if isinstance(cfg.get("items"), list) else list((cfg.get("items") or {}).items())[:3]
    emit_notice("7_ItemsCfgSample", json.dumps({"items_sample": items_sample, "autoSalvageDash": s.get(f"{BASE}/api/dashboard", headers=hdrs, timeout=20).json().get("autoSalvageSettings")}, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        emit_notice("Err", f"{exc}\n{traceback.format_exc()[-800:]}")
