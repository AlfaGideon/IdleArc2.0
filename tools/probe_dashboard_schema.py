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

    token = s.post(
        f"{BASE}/api/login",
        json={"email": os.environ["IDLEARC_EMAIL"].strip(), "password": os.environ["IDLEARC_PASSWORD"], "device_name": "IdleArcHelperProbe"},
        headers={"Accept": "application/json"}, timeout=15,
    ).json()["token"]
    d = s.get(f"{BASE}/api/dashboard", headers={"Accept": "application/json", "Authorization": f"Bearer {token}"}, timeout=25).json()

    # 1. Exact 5 classes + compact class_skill_trees summary + class_skill_caps
    trees_summary = {}
    for cid, branches in (cfg.get("class_skill_trees") or {}).items():
        skills_flat = []
        for br in branches:
            for sk in br.get("skills") or []:
                skills_flat.append({
                    "id": sk.get("id"),
                    "branch": br.get("name"),
                    "name": sk.get("name"),
                    "max": sk.get("max_rank"),
                    "desc": sk.get("description"),
                })
        trees_summary[cid] = skills_flat
    emit_notice("1_ClassesAndStances", json.dumps({
        "characterClasses": d.get("characterClasses"),
        "combat_stances": cfg.get("combat_stances"),
        "druid_class": cfg.get("druid_class"),
    }, ensure_ascii=False, indent=2))

    emit_notice("2_ClassTrees_1_2_3", json.dumps({k: trees_summary.get(k) for k in ("1", "2", "3")}, ensure_ascii=False))
    emit_notice("3_ClassTrees_4_5_Caps", json.dumps({
        "4": trees_summary.get("4"),
        "5": trees_summary.get("5"),
        "caps": cfg.get("class_skill_caps"),
    }, ensure_ascii=False))

    # 4. Itemization + Drop bonuses + Auto-salvage
    emit_notice("4_ItemizationAndDrops", json.dumps({
        "itemization": cfg.get("itemization"),
        "auto_salvage": cfg.get("auto_salvage"),
        "gear_set_bonuses": cfg.get("gear_set_bonuses"),
    }, ensure_ascii=False, indent=2))

    # 5. Pet bonuses + Pet affixes + Pet evolve + Gems config
    emit_notice("5_PetBonusesAndAffixes", json.dumps({
        "pet_bonuses": cfg.get("pet_bonuses"),
        "pet_affixes": cfg.get("pet_affixes"),
        "pet_evolve": cfg.get("pet_evolve"),
        "pet_compound": cfg.get("pet_compound"),
    }, ensure_ascii=False, indent=2))

    emit_notice("6_GemsConfig", json.dumps(cfg.get("gems"), ensure_ascii=False, indent=2))

    # 7. Bag / Inventory endpoints & live counts
    app_html = s.get(f"{BASE}/login", timeout=15).text
    api_routes = set()
    for ju in re.findall(r'/build/assets/[^"\']+\.js', app_html)[:5]:
        js_txt = s.get(BASE + ju, timeout=15).text
        for m in re.findall(r'/api/[a-zA-Z0-9_/-]+', js_txt):
            if any(w in m.lower() for w in ("item", "inv", "loot", "bag", "stash", "gem", "salvage", "equip", "pet", "rune")):
                api_routes.add(m)

    # Also test GET on likely inventory endpoints if any exist
    new_loot = d.get("newLoot") or []
    new_gems = d.get("newGems") or []
    eq_drops = [(it.get("slot"), it.get("type"), it.get("drop_bonus_type"), it.get("drop_bonus_value")) for it in (d.get("characterEquippedItems") or [])]
    pets_brief = [
        {
            "name": p.get("name"), "rar": p.get("rarity"), "eq": p.get("is_equipped"),
            "spec": p.get("special_bonus_type"), "spec_val": p.get("special_bonus_active_value"),
            "affs": [(a.get("name"), a.get("level"), a.get("value"), a.get("max_level")) for a in (p.get("affixes") or [])],
        }
        for p in (d.get("characterPets") or [])[:10]
    ]
    emit_notice("7_BagPetsAndRoutes", json.dumps({
        "newLoot_len": len(new_loot),
        "newLoot_first3": [
            {"slot": x.get("slot"), "type": x.get("type"), "tier": x.get("tier"), "drop_bonus_type": x.get("drop_bonus_type"), "drop_bonus_value": x.get("drop_bonus_value")}
            for x in new_loot[:3]
        ],
        "newGems_len": len(new_gems),
        "newGems_first3": [
            {"family": g.get("family"), "rarity": g.get("rarity"), "quality": g.get("quality"), "pristine": g.get("pristine"), "secondaries": g.get("secondaries")}
            for g in new_gems[:3]
        ],
        "equipped_drops": eq_drops,
        "pets_brief": pets_brief,
        "js_routes": sorted(api_routes),
    }, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        emit_notice("Err", f"{exc}\n{traceback.format_exc()[-800:]}")
