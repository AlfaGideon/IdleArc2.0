#!/usr/bin/env python3
import io,json,os,re,time,zipfile
from curl_cffi import requests
s=requests.Session(impersonate='chrome124');base='https://idlearc.com'
r=s.post(base+'/api/login',json={'email':os.environ['IDLEARC_EMAIL'],'password':os.environ['IDLEARC_PASSWORD'],'device_name':'IdleArcHelper diagnostics'},headers={'Accept':'application/json'},timeout=20)
if r.status_code not in (200,201) or not r.json().get('token'):raise RuntimeError('Login failed')
h={'Accept':'application/json','Authorization':'Bearer '+r.json()['token']}
def get(p):
 r=s.get(base+p,headers=h,timeout=20); return r.json() if r.status_code==200 else None
first=get('/api/dashboard'); time.sleep(65); second=get('/api/dashboard')
a=first.get('monsterLevelProgress',{});b=second.get('monsterLevelProgress',{})
def number(x):
 try:return float(x)
 except (TypeError,ValueError):return None
ak=number(a.get('kills'));bk=number(b.get('kills'))
print('::notice::PROGRESS_MEASUREMENT='+json.dumps({'keys':list(a.keys()),'kills_type':type(a.get('kills')).__name__,'kills_parseable':ak is not None,'changed':ak is not None and bk is not None and ak!=bk,'monotonic':ak is not None and bk is not None and bk>=ak,'same_floor':first.get('selectedMonsterLevel')==second.get('selectedMonsterLevel'),'effective_equals_selected':a.get('effective_fighting_level')==first.get('selectedMonsterLevel'),'activeFight_type':type(first.get('activeFight')).__name__}))
try:
 r=s.get('https://d.apkpure.net/b/APK/com.mikhaelstudio.idlearc?version=latest',timeout=100,allow_redirects=True);parts=[]
 def read(raw):
  with zipfile.ZipFile(io.BytesIO(raw)) as z:
   for n in z.namelist():
    if n.endswith('.apk'):read(z.read(n))
    elif n.endswith('/libapp.so'):parts.append(z.read(n))
 if r.status_code==200:read(r.content)
 strings=set()
 for blob in parts:
  for raw in re.findall(rb'[ -~]{8,300}',blob):
   t=raw.decode('ascii');l=t.lower()
   if ('catalyst' in l and ((' ' in t and any(w in l for w in ('kill','drop','monster','level','chance','guarantee','farm'))) or re.fullmatch('[a-zA-Z0-9_/?=-]+',t))) or ('pity' in l and any(w in l for w in ('kill','material','catalyst'))):
    if len(t)<=250:strings.add(t)
 matches=sorted(strings,key=str.lower)
 for i in range(0,min(len(matches),90),15):print('::notice::ASTRAL_GAME_TEXT='+json.dumps(matches[i:i+15])[:1800])
except Exception as e:print('::notice::ASTRAL_APP_ERROR='+type(e).__name__)
