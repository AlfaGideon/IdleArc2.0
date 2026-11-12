#!/usr/bin/env python3
"""Read-only API schema and arithmetic diagnostics; no player values/tokens printed."""
import json, os
from curl_cffi import requests
s=requests.Session(impersonate='chrome124'); base='https://idlearc.com'
r=s.post(base+'/api/login',json={'email':os.environ['IDLEARC_EMAIL'],'password':os.environ['IDLEARC_PASSWORD'],'device_name':'IdleArcHelper diagnostics'},headers={'Accept':'application/json'},timeout=20);r.raise_for_status()
h={'Accept':'application/json','Authorization':'Bearer '+r.json()['token']}
r=s.get(base+'/api/dashboard',headers=h,timeout=20);r.raise_for_status();d=r.json()
r=s.get(base+'/api/config/game',headers={'Accept':'application/json'},timeout=20);r.raise_for_status();cfg=r.json()
def shape(x):
 if isinstance(x,dict): return {k:shape(v) for k,v in x.items()}
 if isinstance(x,list): return [shape(x[0])] if x else []
 return type(x).__name__
for key in ['activeCombatStanceHarmonyMax','activeCombatStance','level','monsterLevel','monsterLevelProgress','currentMonsterInstance','attackDamage','attackDamageBreakdown','bonusStats']:
 if key not in d: continue
 v=d[key]
 if key=='bonusStats': v={k:v for k,v in v.items() if any(t in k.lower() for t in ('petdamage','stance','harmony','attack','petdps'))}
 print('::notice::DASH_SCHEMA='+json.dumps({key:shape(v)},ensure_ascii=True)[:3000])
for label, arr in [('pet', d.get('bonusStats',{}).get('petDamageBreakdown')),('hero', d.get('attackDamageBreakdown'))]:
 if isinstance(arr,list):
  for item in arr:
   if isinstance(item,dict):
    # Mechanics labels only; omit every numeric value and character-specific data.
    safe={k:v for k,v in item.items() if k in ('key','type','name','label','slug','source') and isinstance(v,str)}
    print('::notice::BREAKDOWN_LABEL='+json.dumps({'for':label,'labels':safe},ensure_ascii=True)[:1200])
stance=cfg.get('combat_stances',{}).get('stances',{}).get('harmony',{})
print('::notice::PUBLIC_HARMONY_CONFIG='+json.dumps(stance))
maxval=d.get('activeCombatStanceHarmonyMax')
print('::notice::HARMONY_CAP_SHAPE='+json.dumps({'numeric':isinstance(maxval,(int,float)), 'positive': isinstance(maxval,(int,float)) and maxval>0, 'gt100': isinstance(maxval,(int,float)) and maxval>100}))
