#!/usr/bin/env python3
import json
import os
import re
import traceback
import urllib.request

BASE = "https://idlearc.com"

def req_text(path_or_url):
    url = path_or_url if path_or_url.startswith("http") else (BASE + path_or_url)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read().decode("utf-8", errors="ignore")

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
    try:
        html = req_text("/login")
        scripts = re.findall(r'(?:src|href)="([^"]*?/build/assets/[^"]+\.js)"', html)
        found = {"scripts": scripts}
        # Fetch app-*.js and look for all chunk filenames + all /api/ routes!
        all_chunks = set(scripts)
        for s in list(scripts)[:6]:
            js = req_text(s)
            more = re.findall(r'assets/[A-Za-z0-9_\-\.]+\.js', js)
            for m in more:
                if any(w in m.lower() for w in ["inv", "item", "bag", "equip", "char"]):
                    all_chunks.add("/build/" + m)
            apis = sorted(set(re.findall(r'/api/[A-Za-z0-9_/\-]+', js)))
            if apis:
                found[s] = apis[:40]

        chunk_details = {}
        for ch in sorted(all_chunks):
            if ch in scripts:
                continue
            try:
                cjs = req_text(ch)
                apis = sorted(set(re.findall(r'/api/[A-Za-z0-9_/\-\?\=\&]+', cjs)))
                pag_ctx = re.findall(r'.{0,60}(?:characterItemsPaginated|inventory\?|items\?|page=).{0,80}', cjs)
                if apis or pag_ctx:
                    chunk_details[ch] = {"apis": apis[:30], "ctx": pag_ctx[:8]}
            except Exception as ce:
                chunk_details[ch] = str(ce)[:40]

        print("::notice::JS_CHUNKS=" + json.dumps({"found": found, "chunks": chunk_details}, ensure_ascii=False)[:1800])
    except Exception:
        print("::notice::ERR=" + traceback.format_exc()[-1000:].replace("\n", " | "))

if __name__ == "__main__":
    main()
