#!/usr/bin/env python3
"""Read-only diagnostic of resource fields; no tokens or balances in output."""
import json, os, time
from curl_cffi import requests
s=requests.Session(impersonate='chrome124'); base='https://idlearc.com'
r=s.post(base+'/api/login',json={'email':os.environ['IDLEARC_EMAIL'],'password':os.environ['IDLEARC_PASSWORD'],'device_name':'IdleArcHelper diagnostics'},headers={'Accept':'application/json'},timeout=20)
if r.status_code not in (200,201) or not r.json().get('token'): raise RuntimeError('Login failed')
h={'Accept':'application/json','Authorization':'Bearer '+r.json()['token']}
def get(path):
 r=s.get(base+path,headers=h,timeout=20)
 if r.status_code!=200: return None
 return r.json()
def schema(v, p='', depth=0):
 out=[]
 if depth>3 or not isinstance(v,dict): return out
 for k,x in v.items():
  path=p+'.'+k
  if any(z in k.lower() for z in ('gold','dust','shard','ruby','rubies','exp','xp','progress','session','earning','gain')):
   out.append([path, type(x).__name__, list(x.keys()) if isinstance(x,dict) and len(x)<20 else None])
  if isinstance(x,dict):out.extend(schema(x,path,depth+1))
 return out
first=get('/api/dashboard')
for p in ('/api/dashboard','/api/gems','/api/pets','/api/materials'):
 d=first if p=='/api/dashboard' else get(p)
 found=schema(d)
 for i in range(0,min(len(found),90),30):
  print('::notice::FARM_SCHEMA='+json.dumps({'path':p,'fields':found[i:i+30]},separators=(',',':'))[:1800])
if first is not None:
 lp=first.get('levelProgress')
 print('::notice::FARM_LEVEL_PROGRESS='+json.dumps({'type':type(lp).__name__,'value':lp if isinstance(lp,(int,float,str,dict)) else None},separators=(',',':'))[:1200])
 time.sleep(35)
 second=get('/api/dashboard')
 if isinstance(second,dict):
  diffs={}
  for k in ('goldAmount','goldAmountStr','gemDust','petShardsAmount','rubyAmount','level','levelProgress'):
   a,b=first.get(k),second.get(k)
   try: diffs[k]={'exists':k in first and k in second,'changed':float(a)!=float(b),'negative':float(b)<float(a)}
   except (TypeError,ValueError):diffs[k]={'exists':k in first and k in second,'type':type(b).__name__}
  print('::notice::FARM_SNAPSHOTS='+json.dumps(diffs,separators=(',',':')))
