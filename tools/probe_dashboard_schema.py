#!/usr/bin/env python3
"""
One-shot read-only probe of public /api/config/game + redacted /api/dashboard
to extract exact multi-class mechanics (all 5 classes, class skills, drop_bonus_type pools,
inventory/gem endpoints, pet affix rules, and extra_kill_chance scaling).
"""
from __future__ import annotations
import json
import os
import re
import traceback


BASE = "https://idlearc.com"


def emit_notice(title: str, body: str) -> None:
    escaped = body.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::notice title={title}::{escaped[:3800]}")


def main() -> None:
    import requests

    s = requests.Session()
    # 1. Public /api/config/game
    cfg = s.get(f"{BASE}/api/config/game", headers={"Accept": "application/json"}, timeout=25).json()
    emit_notice("GameCfgKeys", json.dumps(list(cfg.keys()), ensure_ascii=False))

    for k in cfg.keys():
        kl = k.lower()
        if any(w in kl for w in ("class", "stance", "drop", "pet", "gem", "cap", "extra", "kill", "stat", "affix", "salvage")):
            val = cfg[k]
            s_val = json.dumps(val, ensure_ascii=False, indent=2)
            emit_notice(f"Cfg_{k[:22]}", s_val[:3700])
            if len(s_val) > 3700:
                emit_notice(f"Cfg_{k[:22]}_p2", s_val[3700:7400])

    # 2. Authenticated /api/dashboard (strictly redacted)
    token = s.post(
        f"{BASE}/api/login",
        json={
            "email": os.environ["IDLEARC_EMAIL"].strip(),
            "password": os.environ["IDLEARC_PASSWORD"],
            "device_name": "IdleArcHelperSchemaProbe",
        },
        headers={"Accept": "application/json"},
        timeout=15,
    ).json()["token"]

    d = s.get(
        f"{BASE}/api/dashboard",
        headers={"Accept": "application/json", "Authorization": f"Bearer {token}"},
        timeout=25,
    ).json()

    emit_notice("ClassesInDash", json.dumps({
        "characterClassId": d.get("characterClassId"),
        "characterClassName": d.get("characterClassName"),
        "characterClasses": d.get("characterClasses"),
    }, ensure_ascii=False, indent=2))

    new_loot = d.get("newLoot") or []
    new_gems = d.get("newGems") or []
    eq_items = d.get("characterEquippedItems") or []
    eq_drop_bonuses = [(it.get("slot"), it.get("type"), it.get("drop_bonus_type"), it.get("drop_bonus_value")) for it in eq_items]
    loot_summary = [
        {
            "slot": it.get("slot"),
            "type": it.get("type"),
            "tier": it.get("tier"),
            "tier_level": it.get("tier_level"),
            "level": it.get("level"),
            "drop_bonus_type": it.get("drop_bonus_type"),
            "drop_bonus_value": it.get("drop_bonus_value"),
            "locked": it.get("locked") or it.get("is_locked"),
            "keys": list(it.keys()),
        }
        for it in new_loot[:8]
    ]
    gem_summary = [
        {
            "family": g.get("family"),
            "rarity": g.get("rarity"),
            "quality": g.get("quality"),
            "pristine": g.get("pristine"),
            "secondaries": g.get("secondaries"),
            "keys": list(g.keys()),
        }
        for g in new_gems[:8]
    ]
    emit_notice("BagCountsAndSamples", json.dumps({
        "newLoot_len": len(new_loot),
        "newGems_len": len(new_gems),
        "newRunes_len": len(d.get("newRunes") or []),
        "equipped_drop_bonuses": eq_drop_bonuses,
        "newLoot_sample": loot_summary,
        "newGems_sample": gem_summary,
    }, ensure_ascii=False, indent=2))

    pets_info = []
    for p in (d.get("characterPets") or [])[:12]:
        pets_info.append({
            "name": p.get("name"),
            "rarity": p.get("rarity"),
            "is_equipped": p.get("is_equipped"),
            "compound_level": p.get("compound_level"),
            "special_bonus_type": p.get("special_bonus_type"),
            "special_bonus_active_value": p.get("special_bonus_active_value"),
            "affixes": [
                {
                    "name": a.get("name"),
                    "display_name": a.get("display_name"),
                    "level": a.get("level"),
                    "value": a.get("value"),
                    "max_level": a.get("max_level"),
                    "can_upgrade": a.get("can_upgrade"),
                    "upgrade_cost": a.get("upgrade_cost"),
                }
                for a in (p.get("affixes") or [])
            ],
        })
    emit_notice("CharacterPetsSample", json.dumps(pets_info, ensure_ascii=False, indent=2))

    # 3. Check frontend JS routes for any separate /api/inventory or /api/gems endpoints
    html = s.get(f"{BASE}/login", timeout=15).text
    js_urls = re.findall(r'https?://idlearc\.com/build/assets/[^"\']+\.js|/build/assets/[^"\']+\.js', html)
    api_routes = set()
    for ju in js_urls[:4]:
        full_u = ju if ju.startswith("http") else (BASE + ju)
        js_txt = s.get(full_u, timeout=15).text
        for m in re.findall(r'/api/[a-zA-Z0-9_/-]+', js_txt):
            api_routes.add(m)
    # Also check authenticated /app page JS bundles
    app_html = s.get(f"{BASE}/", timeout=15).text
    for ju in re.findall(r'/build/assets/[^"\']+\.js', app_html)[:6]:
        js_txt = s.get(BASE + ju, timeout=15).text
        for m in re.findall(r'/api/[a-zA-Z0-9_/-]+', js_txt):
            api_routes.add(m)
    emit_notice("AllJsApiRoutes", json.dumps(sorted(api_routes), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        emit_notice("Err", f"{exc}\n{traceback.format_exc()[-800:]}")
