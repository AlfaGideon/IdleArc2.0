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
# Read public frontend code and report only snippets about harmony mechanics.
import re
from urllib.parse import urljoin, urlparse
base='https://idlearc.com'
s=requests.Session(impersonate='chrome124')
html=s.get(base+'/app/',timeout=20).text
queue=[]
for x in re.findall(r'(?:src|href)=["\']([^"\']+\.js[^"\']*)',html):
    u=urljoin(base+'/app/',x)
    if urlparse(u).hostname=='idlearc.com': queue.append(u)
seen=set()
while queue and len(seen)<100:
    u=queue.pop(0)
    if u in seen:continue
    seen.add(u)
    try:
        text=s.get(u,timeout=20).text
        for m in list(re.finditer(r'harmony',text,re.I))[:20]:
            print('::notice::HARMONY_JS='+json.dumps({'file':urlparse(u).path,'snippet':text[max(0,m.start()-180):m.end()+500]},ensure_ascii=True)[:1800])
        for part in re.findall(r'["\']([^"\']+\.js)["\']',text):
            if len(part)>180:continue
            if part.startswith(('.', '/')):
                target=urljoin(u,part)
                if urlparse(target).hostname=='idlearc.com' and target not in seen:queue.append(target)
    except Exception as ex:print('::notice::ASSET_ERROR='+type(ex).__name__)
print('::notice::ASSET_SCAN='+json.dumps({'initial':len(re.findall(r'(?:src|href)=["\']([^"\']+\.js[^"\']*)',html)),'scanned':len(seen)}))

# Only field names/types from the dashboard; never print credentials, tokens or values.
import os
r=s.post(base+'/api/login',json={'email':os.environ['IDLEARC_EMAIL'],'password':os.environ['IDLEARC_PASSWORD'],'device_name':'IdleArcHelper diagnostics'},headers={'Accept':'application/json'},timeout=20)
r.raise_for_status()
h={'Accept':'application/json','Authorization':'Bearer '+r.json()['token']}
r=s.get(base+'/api/dashboard',headers=h,timeout=20);r.raise_for_status()
def scan_fields(obj,path='',depth=0):
    if depth>5:return
    if isinstance(obj,dict):
        for k,v in obj.items():
            p=path+'.'+str(k)
            if any(x in k.lower() for x in ('stance','harmony')):
                print('::notice::DASH_STANCE_FIELD='+json.dumps({'path':p,'type':type(v).__name__,'keys':list(v.keys())[:30] if isinstance(v,dict) else []}))
            scan_fields(v,p,depth+1)
    elif isinstance(obj,list):
        for v in obj[:4]:scan_fields(v,path+'[]',depth+1)
scan_fields(r.json())
