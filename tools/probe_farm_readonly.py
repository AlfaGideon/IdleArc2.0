#!/usr/bin/env python3
import json, os
from curl_cffi import requests
s=requests.Session(impersonate='chrome124');base='https://idlearc.com'
r=s.post(base+'/api/login',json={'email':os.environ['IDLEARC_EMAIL'],'password':os.environ['IDLEARC_PASSWORD'],'device_name':'IdleArcHelper diagnostics'},headers={'Accept':'application/json'},timeout=20)
if r.status_code not in (200,201) or not r.json().get('token'):raise RuntimeError('Login failed')
h={'Accept':'application/json','Authorization':'Bearer '+r.json()['token']}
def scan(v,p='',depth=0):
 if depth>5:return []
 out=[]
 if isinstance(v,dict):
  for k,x in v.items():
   path=p+'.'+k
   if any(w in k.lower() for w in ('earned','lifetime','total_gold','gold_earned','gold_gained','goldgained','goldEarned','farm','session','total_xp','total_exp')):
    out.append([path,type(x).__name__])
   if isinstance(x,(dict,list)):out.extend(scan(x,path,depth+1))
 elif isinstance(v,list):
  for x in v[:10]:out.extend(scan(x,p+'[]',depth+1))
 return out
for p in ('/api/dashboard','/api/statistics','/api/stats','/api/character','/api/achievements'):
 try:
  r=s.get(base+p,headers=h,timeout=20);d=r.json() if r.status_code==200 else None
  extra={}
  if p=='/api/dashboard' and isinstance(d,dict):
   for k in ('goldAmountStr','gemDustStr','petShardsAmountStr','rubyAmountStr'):
    try:float(d[k]);extra[k]='numeric string'
    except (KeyError,TypeError,ValueError):extra[k]='missing or nonnumeric'
  print('::notice::FARM_TOTALS='+json.dumps({'path':p,'status':r.status_code,'keys':list(d.keys())[:35] if isinstance(d,dict) and p!='/api/dashboard' else [],'fields':scan(d)[:30],'exact':extra},separators=(',',':'))[:1800])
 except Exception as e:print('::notice::FARM_ERROR='+json.dumps({'path':p,'error':type(e).__name__}))
