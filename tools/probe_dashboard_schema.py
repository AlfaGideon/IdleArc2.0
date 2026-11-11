#!/usr/bin/env python3
"""
Inspects ONLY game-mechanic sub-schemas inside /api/dashboard (stats, equipped items, gems, talismans, pets, skills).
Strictly redacts all account/player names, IDs, tokens, emails, and guilds.
"""
from __future__ import annotations

import json
import os
import traceback


BASE = "https://idlearc.com"

REDACT_KEYS = {
    "email", "password", "token", "access_token", "user_id", "userid",
    "character_id", "characterid", "account_id", "guild_id", "id",
    "charactername", "character_name", "username", "display_name", "guild_name",
}


def schema_sample(obj, key_name: str = "", depth: int = 0):
    if depth > 10:
        return "<MAX>"
    if obj is None or isinstance(obj, bool):
        return obj
    if isinstance(obj, (int, float)):
        if key_name.lower() in REDACT_KEYS:
            return 0
        # Round numbers to generic placeholders or 1 decimal so only type/scale is shown
        return round(obj, 2) if isinstance(obj, float) else obj
    if isinstance(obj, str):
        if key_name.lower() in REDACT_KEYS or "@" in obj:
            return "<REDACTED>"
        return obj[:80]
    if isinstance(obj, list):
        if not obj:
            return []
        res = [schema_sample(obj[0], key_name, depth + 1)]
        if len(obj) > 1:
            res.append(f"<...len={len(obj)}>")
        return res
    if isinstance(obj, dict):
        out = {}
        items = list(obj.items())
        for k, v in items[:50]:
            ks = str(k)
            if ks.lower() in REDACT_KEYS:
                out[ks] = "<REDACTED>"
            else:
                out[ks] = schema_sample(v, ks, depth + 1)
        if len(items) > 50:
            out["__extra__"] = len(items) - 50
        return out
    return str(type(obj))


def emit_notice(title: str, body: str) -> None:
    escaped = body.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::notice title={title}::{escaped}")


def main() -> None:
    import requests

    email = os.environ["IDLEARC_EMAIL"].strip()
    password = os.environ["IDLEARC_PASSWORD"]

    s = requests.Session()
    ra = s.post(
        f"{BASE}/api/login",
        json={"email": email, "password": password, "device_name": "DruidHelperSchema"},
        headers={"Accept": "application/json"},
        timeout=15,
    )
    token = ra.json()["token"]

    d = s.get(
        f"{BASE}/api/dashboard",
        headers={"Accept": "application/json", "Authorization": f"Bearer {token}"},
        timeout=25,
    ).json()

    groups = [
        ("Stats", {
            k: d.get(k) for k in [
                "level", "gear_score", "activeCombatStance", "attackPower", "maxHealth", "defense",
                "damageReduction", "damageReductionUncapped", "attackDamage", "attackDamageBreakdown",
                "criticalStrikeChance", "criticalDamage", "doubleHitChance", "doubleDamageChance",
                "blockChance", "blockChanceUncapped", "thornsFinalDamage", "retaliation",
                "extraKillChance", "luckyChance", "bossDamage", "elementalConversions",
                "effectivePetMasteryLevel", "bonusStats",
            ]
        }),
        ("EquippedItems", {"characterEquippedItems": d.get("characterEquippedItems"), "newLoot_sample": (d.get("newLoot") or [])[:1]}),
        ("Gems", {"gemSockets": (d.get("gemSockets") if isinstance(d.get("gemSockets"), list) else d.get("gemSockets")), "newGems_sample": (d.get("newGems") or [])[:1]}),
        ("Talismans", {"equippedTalismans": d.get("equippedTalismans"), "talismanInscriptionQuote": d.get("talismanInscriptionQuote"), "talismanAutoInscription": d.get("talismanAutoInscription")}),
        ("Pets", {"activePet": d.get("activePet"), "characterPets": (d.get("characterPets") or [])[:2], "petPresetSlots": d.get("petPresetSlots")}),
        ("Skills", {"activeClassSkillPresetSlot": d.get("activeClassSkillPresetSlot"), "classSkillPresetSlots": d.get("classSkillPresetSlots"), "passives": (d.get("passives") or [])[:3], "treePoints": d.get("treePoints")}),
    ]

    for title, payload in groups:
        txt = json.dumps(schema_sample(payload, title, 0), ensure_ascii=False, indent=2)
        emit_notice(title, txt[:3600])


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        emit_notice("Err", f"{exc}\n{traceback.format_exc()[-600:]}")
