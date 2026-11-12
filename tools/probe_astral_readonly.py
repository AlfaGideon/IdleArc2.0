#!/usr/bin/env python3
"""Inspect read-only public game schemas and the authenticated account's catalyst fields."""
import io, json, os, re, zipfile
from curl_cffi import requests
s = requests.Session(impersonate='chrome124')
base = 'https://idlearc.com'
r = s.post(base + '/api/login', json={'email': os.environ['IDLEARC_EMAIL'], 'password': os.environ['IDLEARC_PASSWORD'], 'device_name':'IdleArcHelper diagnostics'}, headers={'Accept':'application/json'}, timeout=20)
if r.status_code not in (200, 201) or not r.json().get('token'):
    raise RuntimeError('Login failed, HTTP ' + str(r.status_code))
h = {'Accept':'application/json', 'Authorization':'Bearer ' + r.json()['token']}

def scan(v, path='', depth=0):
    if depth > 8: return []
    result = []
    if isinstance(v, dict):
        for k, x in v.items():
            p = path + '.' + str(k)
            if any(t in str(k).lower() for t in ('astral','catalyst','catalys')):
                if isinstance(x, (int, float, bool, str, type(None))):
                    result.append([p, x])
                elif isinstance(x, dict):
                    result.append([p, {a:b for a,b in x.items() if isinstance(b, (int, float, bool, type(None))) or (isinstance(b,str) and len(b)<60)}])
                elif isinstance(x, list): result.append([p, 'list:' + str(len(x))])
            if isinstance(x, (dict, list)): result.extend(scan(x,p,depth+1))
    elif isinstance(v, list):
        for x in v[:100]:
            if isinstance(x, dict) and any('catalys' in str(x.get(k,'')).lower() or 'astral' in str(x.get(k,'')).lower() for k in ('name','slug','type','key')):
                result.append([path+'[]', {k:v for k,v in x.items() if isinstance(v, (int, float, bool, type(None))) or (k in ('name','slug','type','key') and isinstance(v,str))}])
            result.extend(scan(x,path+'[]',depth+1))
    return result

for path in ('/api/dashboard', '/api/config/game', '/api/inventory', '/api/forge', '/api/materials', '/api/arcstone-ascent/status', '/api/boss-fight/status'):
    try:
        r=s.get(base+path,headers=h,timeout=20)
        found=scan(r.json()) if r.status_code==200 else []
        print('::notice::ASTRAL_API='+json.dumps({'path':path,'status':r.status_code,'found':found},ensure_ascii=False,separators=(',',':'))[:1800])
    except Exception as e: print('::notice::ASTRAL_API='+json.dumps({'path':path,'error':type(e).__name__}))

try:
    r=s.get('https://d.apkpure.net/b/APK/com.mikhaelstudio.idlearc?version=latest',timeout=100,allow_redirects=True)
    parts=[]
    def read(raw):
        with zipfile.ZipFile(io.BytesIO(raw)) as z:
            for n in z.namelist():
                if n.endswith('.apk'): read(z.read(n))
                elif n.endswith('/libapp.so'): parts.append(z.read(n))
    if r.status_code==200: read(r.content)
    strings=set()
    for blob in parts:
        for raw in re.findall(rb'[ -~]{4,160}',blob):
            t=raw.decode('ascii')
            if any(x in t.lower() for x in ('astral','catalys')) and len(t)<=130: strings.add(t)
    matches=sorted(strings,key=str.lower)
    for i in range(0,min(50,len(matches)),25):
        print('::notice::ASTRAL_STRINGS='+json.dumps(matches[i:i+25],ensure_ascii=True)[:1800])
except Exception as e:
    print('::notice::ASTRAL_APP_ERROR='+type(e).__name__)
