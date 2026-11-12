#!/usr/bin/env python3
import io
import json
import os
import re
import zipfile

from curl_cffi import requests as creq

BASE = "https://idlearc.com"
PKG = "com.mikhaelstudio.idlearc"

def extract_api_strings_from_bytes(blob: bytes, out_set: set):
    for m in re.findall(rb'/?api/[a-zA-Z0-9_/\-\?\=\&\{\}\$\.]{2,90}', blob):
        s = m.decode("utf-8", errors="ignore")
        if not s.startswith("/"):
            s = "/" + s
        out_set.add(s)
    for m in re.findall(rb'[a-zA-Z0-9_/\-]{0,30}inventory[a-zA-Z0-9_/\-\?\=\&]{0,50}', blob):
        s = m.decode("utf-8", errors="ignore")
        if "/" in s or "?" in s or "_" in s:
            out_set.add("INV_STR:" + s)

def inspect_zip_or_apk(raw_bytes: bytes, out_set: set):
    try:
        with zipfile.ZipFile(io.BytesIO(raw_bytes)) as zf:
            for name in zf.namelist():
                low = name.lower()
                if low.endswith(".apk"):
                    inspect_zip_or_apk(zf.read(name), out_set)
                elif "libapp.so" in low or low.endswith(".dex") or "manifest" in low:
                    extract_api_strings_from_bytes(zf.read(name), out_set)
    except Exception:
        pass

def main():
    s = creq.Session(impersonate="chrome124")
    found_strs = set()

    # Try downloading public APK/XAPK of com.mikhaelstudio.idlearc to read libapp.so routes
    dl_urls = [
        f"https://d.apkpure.net/b/XAPK/{PKG}?version=latest",
        f"https://d.apkpure.net/b/APK/{PKG}?version=latest",
        f"https://d.apkpure.com/b/XAPK/{PKG}?version=latest",
        f"https://d.apkpure.com/b/APK/{PKG}?version=latest",
    ]
    dl_status = {}
    for u in dl_urls:
        try:
            r = s.get(u, timeout=35, allow_redirects=True)
            dl_status[u.split("/")[4] + "_" + u.split("/")[2]] = f"{r.status_code}:{len(r.content)}"
            if r.status_code == 200 and len(r.content) > 500_000:
                inspect_zip_or_apk(r.content, found_strs)
                if found_strs:
                    break
        except Exception as e:
            dl_status[u[:30]] = str(e)[:30]

    api_only = sorted(x for x in found_strs if x.startswith("/api/"))
    inv_only = sorted(x for x in found_strs if x.startswith("INV_STR:"))
    print("::notice::APK_API_ROUTES_1=" + json.dumps({"dl": dl_status, "cnt": len(api_only), "routes": api_only[:70]}, ensure_ascii=False)[:1850])
    print("::notice::APK_API_ROUTES_2=" + json.dumps({"routes2": api_only[70:150], "inv_strs": inv_only[:40]}, ensure_ascii=False)[:1850])

    # Login to idlearc.com API and test ALL discovered inventory/item/bag routes + /api/inventory/2!
    r_login = s.post(
        BASE + "/api/login",
        json={
            "email": os.environ["IDLEARC_EMAIL"],
            "password": os.environ["IDLEARC_PASSWORD"],
            "device_name": "IdleArcProbe",
        },
        headers={"Accept": "application/json"},
        timeout=20,
    )
    tok = r_login.json()["token"]
    hdrs = {"Accept": "application/json", "Authorization": f"Bearer {tok}"}

    test_paths = [
        "/api/inventory/2",
        "/api/inventory?page=2",
    ]
    for rt in api_only:
        clean = re.split(r'[\$\{]', rt)[0].rstrip("/")
        if any(w in clean.lower() for w in ["inv", "item", "bag", "gear", "equip", "salvage", "preset", "forge", "smith", "boss", "char"]):
            if clean not in test_paths and clean not in ("/api/login", "/api/dashboard", "/api/config/game"):
                test_paths.append(clean)
                if "?" not in clean:
                    test_paths.append(clean + "?page=2")

    verified = {}
    for tp in test_paths[:45]:
        try:
            rr = s.get(BASE + tp, headers=hdrs, timeout=12)
            if rr.status_code == 200 and rr.text.strip().startswith(("{", "[")):
                dj = rr.json()
                if isinstance(dj, dict):
                    info = {}
                    for k, v in dj.items():
                        if isinstance(v, list):
                            info[k] = f"list({len(v)})"
                        elif isinstance(v, (int, float, str)) and any(w in k.lower() for w in ["cap", "count", "total", "slot", "max", "boss", "cool"]):
                            info[k] = v
                        elif isinstance(v, dict) and "data" in v:
                            d = v.get("data", [])
                            id0 = d[0].get("id") if d and isinstance(d[0], dict) else None
                            info[k] = f"pag(cur={v.get('current_page')},tot={v.get('total')},n={len(d)},id0={id0})"
                    verified[tp] = info if info else list(dj.keys())[:10]
        except Exception:
            pass
    print("::notice::VERIFIED_BAG_ROUTES=" + json.dumps(verified, ensure_ascii=False)[:1850])

if __name__ == "__main__":
    main()
