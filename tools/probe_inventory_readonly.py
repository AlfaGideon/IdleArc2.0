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
params = ['page', 'items_page', 'character_items_page', 'inventory_page', 'item_page',
          'characterItemsPage', 'characterItemsPaginated_page', 'itemsPage', 'inventoryPage',
          'inventory', 'inventory_page_number', 'itemPage', 'character_item_page',
          'characterItems', 'p', 'current_page', 'page_number', 'pageNumber',
          'character_items', 'items', 'inventoryPageNumber', 'character_items_paginated_page']
results = {}
for key in params:
    pag = inv_info(get('/api/inventory?' + urlencode({key: 2})))
    if pag is None:
        results[key] = 'error'
        continue
    ids = {str(x.get('id')) for x in pag['data'] if isinstance(x, dict) and x.get('id') is not None}
    results[key] = {'page': pag.get('current_page'), 'count': len(pag['data']), 'new': len(ids - first_ids)}
for key in ('per_page', 'perPage', 'limit', 'pageSize', 'page_size'):
    pag = inv_info(get('/api/inventory?' + urlencode({key: 100})))
    results[key + '=100'] = {'page': pag.get('current_page'), 'count': len(pag['data'])} if pag else 'error'
for name, extra in [('xhr', {'X-Requested-With': 'XMLHttpRequest'}),
                    ('json_charset', {'Accept': 'application/json, text/plain, */*'})]:
    pag = inv_info(get('/api/inventory?page=2', extra))
    ids = {str(x.get('id')) for x in pag['data'] if isinstance(x, dict) and x.get('id') is not None} if pag else set()
    results[name] = {'page': pag.get('current_page'), 'count': len(pag['data']), 'new': len(ids - first_ids)} if pag else 'error'
for i in range(0, len(results), 10):
    print('::notice::INVENTORY_DIAG=' + json.dumps(dict(list(results.items())[i:i+10]), separators=(',', ':')))
print('::notice::INVENTORY_TOTAL=' + json.dumps({'total': first.get('total'), 'page_size': first.get('per_page')}))

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
for path in ('/api/dashboard', '/api/dungeon', '/api/guild'):
    data = get(path)
    print('::notice::TIMER_KEYS=' + json.dumps({'path': path, 'keys': timing_keys(data)}, separators=(',', ':'))[:1800])

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
        with zipfile.ZipFile(io.BytesIO(apk.content)) as archive:
            names = archive.namelist()
            libs = [n for n in names if n.endswith('/libapp.so')]
            print('::notice::APP_STRUCTURE=' + json.dumps({'lib_count': len(libs), 'nested_apks': sum(n.endswith('.apk') for n in names), 'dex_count': sum(n.endswith('.dex') for n in names)}))
            relevant = libs[:1] + [n for n in names if n.endswith('.dex')]
            blob = b'\x00'.join(archive.read(n) for n in relevant)
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
            if any(w in low for w in ('inventorypage', 'inventory_page', 'itemspage', 'items_page',
                                      'characteritemspaginated', 'bosscooldown', 'boss_cooldown',
                                      'bossfight', 'boss_fight', '/inventory')):
                if len(text) <= 130 and not any(x in low for x in ('google', 'flutter', 'android', 'kotlin')):
                    strings.add(text)
        print('::notice::APP_ROUTES=' + json.dumps(sorted(routes))[:1800])
        matches = sorted(strings)
        for i in range(0, min(len(matches), 150), 12):
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
