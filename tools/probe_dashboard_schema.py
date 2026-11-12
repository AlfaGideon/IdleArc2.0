#!/usr/bin/env python3
from __future__ import annotations
import json, os, traceback

BASE = "https://idlearc.com"

def emit_notice(title: str, body: str) -> None:
    escaped = body.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::notice title={title}::{escaped[:3900]}")

def main() -> None:
    import requests
    s = requests.Session()
    cfg = s.get(f"{BASE}/api/config/game", headers={"Accept": "application/json"}, timeout=25).json()

    # 1. ALL drop_bonuses compactly (key -> name, stat, category, min_tier, T6/T7 values)
    db_all = {}
    for k, v in ((cfg.get("itemization") or {}).get("drop_bonuses") or {}).items():
        vals = v.get("values") or {}
        db_all[k] = {
            "name": v.get("name"),
            "stat": v.get("stat"),
            "cat": v.get("category"),
            "min_t": v.get("min_tier"),
            "t6": vals.get("6"),
            "t7": vals.get("7"),
        }
    emit_notice("1_AllDropBonuses", json.dumps({
        "drop_bonuses": db_all,
        "drop_bonus_retype": (cfg.get("itemization") or {}).get("drop_bonus_retype"),
    }, ensure_ascii=False, indent=2))

    # 2. Full Class 3 (Mage) skills + Class 5 (Druid) skills + all class_skill_caps
    trees = cfg.get("class_skill_trees") or {}
    c3 = [
        {"id": sk.get("id"), "b": br.get("name"), "n": sk.get("name"), "max": sk.get("max_rank"), "d": sk.get("description")}
        for br in (trees.get("3") or []) for sk in (br.get("skills") or [])
    ]
    emit_notice("2_Class3_Mage_Full", json.dumps(c3, ensure_ascii=False, indent=2))
    emit_notice("3_AllSkillCaps", json.dumps(cfg.get("class_skill_caps"), ensure_ascii=False))

    # 4. Exact /api/inventory characterItemsPaginated structure & pagination params!
    token = s.post(
        f"{BASE}/api/login",
        json={"email": os.environ["IDLEARC_EMAIL"].strip(), "password": os.environ["IDLEARC_PASSWORD"], "device_name": "IdleArcHelperProbe"},
        headers={"Accept": "application/json"}, timeout=15,
    ).json()["token"]
    hdrs = {"Accept": "application/json", "Authorization": f"Bearer {token}"}
    inv = s.get(f"{BASE}/api/inventory?per_page=100", headers=hdrs, timeout=20).json()
    pag = inv.get("characterItemsPaginated") or {}
    data_items = pag.get("data") if isinstance(pag, dict) else []
    emit_notice("4_InventoryPagination", json.dumps({
        "pag_meta": {k: v for k, v in (pag.items() if isinstance(pag, dict) else []) if k != "data"},
        "data_len": len(data_items or []),
        "sample_items": [
            {
                "name": it.get("name"),
                "slot": it.get("slot"),
                "type": it.get("type"),
                "tier": it.get("tier"),
                "tier_level": it.get("tier_level"),
                "awaken_level": it.get("awaken_level"),
                "level": it.get("level"),
                "locked": it.get("is_locked") or it.get("locked"),
                "drop_bonus_type": it.get("drop_bonus_type"),
                "drop_bonus_value": it.get("drop_bonus_value"),
                "allowed_classes": it.get("allowed_classes"),
                "implicit_bonus": it.get("implicit_bonus"),
                "secondary_implicit_bonus": it.get("secondary_implicit_bonus"),
            }
            for it in (data_items or [])[:8]
        ],
    }, ensure_ascii=False, indent=2))

    # 5. Pet bonuses (epic, legendary, mythic) + extra_kill_chance in config
    pb = cfg.get("pet_bonuses") or {}
    emit_notice("5_PetBonusesEpicLegMyth", json.dumps({
        "rarity_keys": list(pb.keys()),
        "legendary": pb.get("legendary"),
        "mythic": pb.get("mythic"),
    }, ensure_ascii=False, indent=2))

    gems_resp = s.get(f"{BASE}/api/gems", headers=hdrs, timeout=20).json()
    emit_notice("6_GemsBagSample", json.dumps({
        "bag_count": gems_resp.get("bag_count"),
        "bag_cap": gems_resp.get("bag_cap"),
        "counts": gems_resp.get("counts"),
        "sample3": [
            {
                "family": g.get("family"),
                "rarity": g.get("rarity"),
                "rarity_slug": g.get("rarity_slug"),
                "quality": g.get("quality"),
                "pristine": g.get("pristine"),
                "locked": g.get("locked"),
                "secondaries": g.get("secondaries"),
                "primaries_preview": g.get("primaries_preview"),
            }
            for g in (gems_resp.get("bag") or [])[:3]
        ],
    }, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        emit_notice("Err", f"{exc}\n{traceback.format_exc()[-800:]}")
