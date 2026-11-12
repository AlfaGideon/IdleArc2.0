#!/usr/bin/env python3
"""Read-only protocol probe; does not emit credentials, item data or player IDs."""
import json
import os
from curl_cffi import requests
BASE = 'https://idlearc.com'
s = requests.Session(impersonate='chrome124')
r = s.post(BASE + '/api/login', json={'email': os.environ['IDLEARC_EMAIL'], 'password': os.environ['IDLEARC_PASSWORD'], 'device_name': 'IdleArcHelper diagnostics'}, headers={'Accept': 'application/json'}, timeout=20)
if r.status_code not in (200, 201) or not r.json().get('token'):
    raise RuntimeError('Login failed: HTTP %d' % r.status_code)
h = {'Accept': 'application/json', 'Authorization': 'Bearer ' + r.json()['token']}

def fetch(path):
    r = s.get(BASE + path, headers=h, timeout=20)
    try: return r.status_code, r.json()
    except ValueError: return r.status_code, None

def item_arrays(value, path='', depth=0):
    if depth > 4: return []
    if isinstance(value, dict):
        return sum((item_arrays(v, path + '.' + str(k), depth+1) for k,v in value.items()), [])
    if isinstance(value, list):
        if value and isinstance(value[0], dict) and 'id' in value[0] and any(k in value[0] for k in ('tier', 'drop_bonus_type', 'slot')):
            return [(path, value)]
        return sum((item_arrays(v, path+'[]', depth+1) for v in value[:5]), [])
    return []

first = set()
for path in ('/api/inventory', '/api/items-page/1', '/api/items-page/2', '/api/items-page/3', '/api/items-page/7'):
    status, data = fetch(path)
    arrays = item_arrays(data)
    entries = []
    for name, items in arrays:
        ids = {str(x['id']) for x in items if isinstance(x, dict) and 'id' in x}
        if path == '/api/inventory' and 'characterItemsPaginated' in name: first = ids
        entries.append({'path': name, 'count': len(items), 'new': len(ids-first)})
    keys = list(data.keys()) if isinstance(data, dict) else []
    meta = {k:v for k,v in data.items() if k in ('current_page', 'last_page', 'total', 'per_page') and isinstance(v, (int, float, str))} if isinstance(data, dict) else {}
    print('::notice::ITEMS_PAGE=' + json.dumps({'path': path, 'http': status, 'keys': keys, 'meta': meta, 'arrays': entries}, separators=(',', ':'))[:1800])
