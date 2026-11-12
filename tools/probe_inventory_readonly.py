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
