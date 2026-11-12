#!/usr/bin/env python3
"""Public configuration lookup only. Does not log in or change game state."""
import json
from curl_cffi import requests
r = requests.get('https://idlearc.com/api/config/game', headers={'Accept':'application/json'}, impersonate='chrome124', timeout=30)
r.raise_for_status()
d = r.json()
def walk(obj, path=''):
    if isinstance(obj, dict):
        for k, v in obj.items():
            p = path + '.' + str(k)
            if any(x in str(k).lower() for x in ('stance', 'harmony', 'beastmaster', 'beast_master')):
                text = json.dumps(v, ensure_ascii=False, separators=(',', ':'))
                print('::notice::STANCE_CONFIG=' + json.dumps({'path': p, 'value': text[:3000]}, ensure_ascii=True))
            else: walk(v, p)
    elif isinstance(obj, list):
        for i, v in enumerate(obj): walk(v, path + '[' + str(i) + ']')
walk(d)
print('::notice::CONFIG_KEYS=' + json.dumps(list(d) if isinstance(d, dict) else type(d).__name__))
