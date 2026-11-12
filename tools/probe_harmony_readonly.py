#!/usr/bin/env python3
import json, os, math
from curl_cffi import requests
s=requests.Session(impersonate='chrome124');base='https://idlearc.com'
r=s.post(base+'/api/login',json={'email':os.environ['IDLEARC_EMAIL'],'password':os.environ['IDLEARC_PASSWORD'],'device_name':'IdleArcHelper diagnostics'},headers={'Accept':'application/json'},timeout=20);r.raise_for_status()
h={'Accept':'application/json','Authorization':'Bearer '+r.json()['token']}
r=s.get(base+'/api/dashboard',headers=h,timeout=20);r.raise_for_status();d=r.json()
r=s.get(base+'/api/config/game',headers={'Accept':'application/json'},timeout=20);r.raise_for_status();c=r.json()['combat_stances']['stances']['harmony']
v=d.get('activeCombatStanceHarmonyMax'); prog=d.get('monsterLevelProgress') or {}; ml=prog.get('effective_fighting_level',d.get('monsterLevel',0)); lv=d.get('level',0)
# Compare scales with the user's screenshot without publishing account numbers.
print('::notice::CAP_COMPARE='+json.dumps({'near_80':isinstance(v,(float,int)) and abs(v-80)<1,'near_81':isinstance(v,(float,int)) and abs(v-81)<1,'near_8000':isinstance(v,(float,int)) and abs(v-8000)<100,'near_8100':isinstance(v,(float,int)) and abs(v-8100)<100,'greater_than_100':isinstance(v,(float,int)) and v>100,'ml_positive':ml>0,'level_positive':lv>0}))
for k,arr in [('pet',(d.get('bonusStats') or {}).get('petDamageBreakdown') or []),('hero',(d.get('attackDamageBreakdown') or {}).get('multiplicative') or [])]:
 print('::notice::BREAKDOWN_KEYS='+json.dumps({'source':k,'keys':[x.get('key') for x in arr if isinstance(x,dict)]}))
# Config keys and values are public and contain no account data.
print('::notice::HARMONY_CFG='+json.dumps(c))
bs=d.get('bonusStats') or {}; hero=d.get('attackDamageBreakdown') or {}
def stance_entry(arr):
 return next((x for x in arr if isinstance(x,dict) and x.get('key')=='stance'),{})
p=stance_entry(bs.get('petDamageBreakdown') or []);a=stance_entry(hero.get('multiplicative') or [])
def approx(x,y):return isinstance(x,(float,int)) and isinstance(y,(float,int)) and abs(x-y)<max(0.02,abs(y)*0.0001)
print('::notice::HARMONY_UNITS='+json.dumps({'is_harmony':d.get('activeCombatStance')=='harmony','cap_approx_8000':approx(v,8000),'cap_approx_8100':approx(v,8100),'pet_stance_pct_present':isinstance(p.get('pct'),(float,int)),'hero_stance_pct_present':isinstance(a.get('pct'),(float,int)),'pct_equal':approx(p.get('pct'),a.get('pct')),'pet_le_cap':isinstance(p.get('pct'),(float,int)) and isinstance(v,(float,int)) and p['pct']<=v,'hero_mult_matches_pct':approx(a.get('mult'),1+(a.get('pct',0)/100))}))
