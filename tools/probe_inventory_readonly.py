#!/usr/bin/env python3
"""Read-only schema probe; never emits credentials, tokens, item details or player IDs."""
import json
import os
from urllib.parse import urlencode
from curl_cffi import requests

BASE = 'https://idlearc.com'
session = requests.Session(impersonate='chrome124')
login = session.post(BASE + '/api/login', json={
    'email': os.environ['IDLEARC_EMAIL'], 'password': os.environ['IDLEARC_PASSWORD'],
    'device_name': 'IdleArcHelper diagnostics',
}, headers={'Accept': 'application/json'}, timeout=20)
if login.status_code not in (200, 201):
    raise RuntimeError('Login failed: HTTP %d' % login.status_code)
token = login.json().get('token')
if not token:
    raise RuntimeError('Login did not return a token')
headers = {'Accept': 'application/json', 'Authorization': 'Bearer ' + token}

def get(path, hdr=None):
    response = session.get(BASE + path, headers={**headers, **(hdr or {})}, timeout=20)
    if response.status_code != 200:
        return None
    try:
        return response.json()
    except ValueError:
        return None

def inv_info(data):
    if not isinstance(data, dict):
        return None
    pag = data.get('characterItemsPaginated')
    if not isinstance(pag, dict) or not isinstance(pag.get('data'), list):
        return None
    return pag

first = inv_info(get('/api/inventory'))
if first is None:
    raise RuntimeError('Missing inventory page')
first_ids = {str(x.get('id')) for x in first['data'] if isinstance(x, dict) and x.get('id') is not None}
# Print field names only; values and player-specific guild details stay private.
def timing_keys(value, prefix='', depth=0):
    if depth > 3:
        return []
    out = []
    if isinstance(value, dict):
        for key, val in value.items():
            path = prefix + '.' + key if prefix else key
            if any(word in key.lower() for word in ('boss', 'cooldown', 'ready', 'fight', 'last', 'expiration', 'remaining')):
                out.append(path + ':' + type(val).__name__)
            if isinstance(val, (dict, list)):
                out.extend(timing_keys(val, path, depth + 1))
    elif isinstance(value, list):
        for val in value[:5]:
            out.extend(timing_keys(val, prefix + '[]', depth + 1))
    return sorted(set(out))
boss = get('/api/boss-fight/status')
if isinstance(boss, dict):
    safe = {k: v for k, v in boss.items() if isinstance(v, (bool, int, float)) and any(w in k.lower() for w in ('time', 'second', 'cooldown', 'remaining', 'available', 'can_fight'))}
    print('::notice::BOSS_STATUS=' + json.dumps({'keys': list(boss.keys()), 'timing': safe}, separators=(',', ':')))
dash = get('/api/dashboard')
if isinstance(dash, dict):
    print('::notice::DASH_INVENTORY_KEYS=' + json.dumps({k: (len(v) if isinstance(v, list) else type(v).__name__) for k,v in dash.items() if 'item' in k.lower() or 'loot' in k.lower() or 'inventory' in k.lower()}))

# Inspect public app package for actual route strings, without persisting its contents.
import io
import re
import zipfile
apk_url = 'https://d.apkpure.net/b/APK/com.mikhaelstudio.idlearc?version=latest'
try:
    apk = session.get(apk_url, timeout=100, allow_redirects=True)
    if apk.status_code != 200:
        print('::notice::APP_SCAN=HTTP_' + str(apk.status_code))
    else:
        parts = []
        structures = []
        def scan_zip(contents):
            with zipfile.ZipFile(io.BytesIO(contents)) as archive:
                names = archive.namelist()
                libs = [n for n in names if n.endswith('/libapp.so')]
                nested = [n for n in names if n.endswith('.apk')]
                dexes = [n for n in names if n.endswith('.dex')]
                structures.append({'libs': len(libs), 'apks': len(nested), 'dex': len(dexes)})
                for n in libs[:1] + dexes:
                    parts.append(archive.read(n))
                for n in nested:
                    scan_zip(archive.read(n))
        scan_zip(apk.content)
        print('::notice::APP_STRUCTURE=' + json.dumps(structures))
        blob = b'\x00'.join(parts)
        routes = set()
        for match in re.findall(rb'/?api/[a-zA-Z0-9_/\-]{2,100}', blob):
            route = match.decode('ascii').lstrip('/')
            if any(w in route.lower() for w in ('boss', 'inventory', 'item', 'equipment', 'fight', 'battle', 'combat')):
                routes.add('/' + route)
        # Dart's string table includes strings terminated by zero bytes.
        strings = set()
        for match in re.findall(rb'[ -~]{4,160}', blob):
            text = match.decode('ascii')
            low = text.lower()
            if any(w in low for w in ('inventory', 'item', 'gear', 'preset')):
                if len(text) <= 100 and re.fullmatch(r'[a-zA-Z0-9_./?=&:-]+', text) and not any(x in low for x in ('google', 'flutter', 'android', 'kotlin')):
                    if '/' in text or '?' in text or 'allcharacteritems' in low:
                        strings.add(text)
        print('::notice::APP_ROUTES=' + json.dumps(sorted(routes))[:1800])
        matches = sorted(strings, key=str.lower)
        for i in range(0, min(len(matches), 84), 12):
            print('::notice::APP_STRINGS=' + json.dumps(matches[i:i+12])[:1800])
except Exception as exc:
    print('::notice::APP_SCAN_ERROR=' + type(exc).__name__)

# Check whether the authenticated web inventory exposes a separate read-only route.
for path in ('/inventory?page=1', '/inventory?page=2', '/api/inventory?page=2'):
    try:
        response = session.get(BASE + path, headers=headers, timeout=20, allow_redirects=False)
        print('::notice::WEB_ROUTE=' + json.dumps({'path': path, 'status': response.status_code, 'content_type': response.headers.get('content-type', '').split(';')[0], 'redirect': bool(response.headers.get('location'))}))
    except Exception as exc:
        print('::notice::WEB_ROUTE=' + json.dumps({'path': path, 'error': type(exc).__name__}))
