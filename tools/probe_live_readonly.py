#!/usr/bin/env python3
"""Read-only transport/schema probe. Do not print credentials or account values."""
import json, os, re
from urllib.parse import urljoin, urlparse
from curl_cffi import requests
base='https://idlearc.com'; s=requests.Session(impersonate='chrome124')
r=s.get(base+'/app/',timeout=30); r.raise_for_status()
queue=[urljoin(base+'/app/', x) for x in re.findall(r'(?:src|href)=["\']([^"\']+\.js[^"\']*)',r.text)]
seen=set(); matches=[]
while queue and len(seen)<100:
 u=queue.pop(0)
 if u in seen or urlparse(u).hostname!='idlearc.com':continue
 seen.add(u)
 try:
  resp=s.get(u,timeout=25);resp.raise_for_status(); text=resp.text
  for m in re.finditer(r'WebSocket|EventSource|socket\.io|/api/dashboard|/api/inventory|/api/items-page|setInterval|setTimeout',text,re.I):
   snippet=text[max(0,m.start()-130):min(len(text),m.end()+240)]
   if any(x in snippet.lower() for x in ('inventory','dashboard','websocket','eventsource','socket.io','tick','resource','update')):
    matches.append({'file':urlparse(u).path, 'snippet':snippet})
  for part in re.findall(r'["\']([^"\']+\.js)["\']',text):
   if len(part)<180 and part.startswith(('.', '/')):
    q=urljoin(u,part)
    if urlparse(q).hostname=='idlearc.com' and q not in seen:queue.append(q)
 except Exception as exc: print('::notice::ASSET_ERROR='+type(exc).__name__)
print('::notice::PUBLIC_SCAN='+json.dumps({'assets':len(seen),'matches':len(matches)}))
for hit in matches[:70]:print('::notice::PUBLIC_TRANSPORT='+json.dumps(hit,ensure_ascii=True)[:1100])
r=s.post(base+'/api/login',json={'email':os.environ['IDLEARC_EMAIL'],'password':os.environ['IDLEARC_PASSWORD'],'device_name':'IdleArcHelper diagnostics'},headers={'Accept':'application/json'},timeout=20);r.raise_for_status()
h={'Accept':'application/json','Authorization':'Bearer '+r.json()['token']}
r=s.get(base+'/api/dashboard',headers=h,timeout=25);r.raise_for_status(); d=r.json()
def schema(v,path='',depth=0):
 if depth>5:return
 if isinstance(v,dict):
  for k,x in v.items():
   p=path+'.'+k
   if any(z in k.lower() for z in ('tick','resource','income','earned','loot','profit','reward','farm','session','kill','combat','currency','perhour','per_hour','gold','dust','shard','ruby','exp','progress')):
    print('::notice::LIVE_FIELD='+json.dumps({'path':p,'type':type(x).__name__},ensure_ascii=True))
   if isinstance(x,dict):schema(x,p,depth+1)
   elif isinstance(x,list) and x and isinstance(x[0],dict):schema(x[0],p+'[]',depth+1)
schema(d)
