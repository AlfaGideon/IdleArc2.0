#!/usr/bin/env python3
from __future__ import annotations
import json, os, traceback

BASE = "https://idlearc.com"

def emit_notice(title: str, body: str) -> None:
    escaped = body.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::notice title={title}::{escaped}")

def main() -> None:
    import requests
    s = requests.Session()
    token = s.post(
        f"{BASE}/api/login",
        json={"email": os.environ["IDLEARC_EMAIL"].strip(), "password": os.environ["IDLEARC_PASSWORD"], "device_name": "DruidHelperSchema"},
        headers={"Accept": "application/json"},
        timeout=15,
    ).json()["token"]
    d = s.get(
        f"{BASE}/api/dashboard",
        headers={"Accept": "application/json", "Authorization": f"Bearer {token}"},
        timeout=25,
    ).json()
    bs = d.get("bonusStats") or {}
    emit_notice("BonusStatsKeys", json.dumps(list(bs.keys()), ensure_ascii=False))
    emit_notice("BonusStatsFull1", json.dumps(bs, ensure_ascii=False, indent=2)[:3600])
    emit_notice("BonusStatsFull2", json.dumps(bs, ensure_ascii=False, indent=2)[3600:7200])
    # Also check all 10 equipped item slot names & gemSockets track names
    eq_slots = [(it.get("slot"), it.get("type"), it.get("equipped_sub_slot")) for it in (d.get("characterEquippedItems") or [])]
    gem_tracks = list((d.get("gemSockets") or {}).keys()) if isinstance(d.get("gemSockets"), dict) else []
    emit_notice("SlotsAndTracks", json.dumps({"equipped_slots": eq_slots, "gem_tracks": gem_tracks}, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        emit_notice("Err", f"{exc}\n{traceback.format_exc()[-500:]}")
