#!/usr/bin/env python3
import io,json,os,re,zipfile
from curl_cffi import requests
s=requests.Session(impersonate='chrome124'); base='https://idlearc.com'
r=s.post(base+'/api/login',json={'email':os.environ['IDLEARC_EMAIL'],'password':os.environ['IDLEARC_PASSWORD'],'device_name':'IdleArcHelper diagnostics'},headers={'Accept':'application/json'},timeout=20)
if r.status_code not in (200,201) or not r.json().get('token'): raise RuntimeError('Login failed')
h={'Accept':'application/json','Authorization':'Bearer '+r.json()['token']}
def get(p):
 r=s.get(base+p,headers=h,timeout=20)
 return r.json() if r.status_code==200 else None

def keys(v,p='',depth=0):
 if depth>5:return []
 out=[]
 if isinstance(v,dict):
  for k,x in v.items():
   path=p+'.'+k
   if any(t in k.lower() for t in ('pity','kill','drop','catalys','astral')):
    out.append([path, x if isinstance(x,(int,float,bool,type(None))) else type(x).__name__])
   if isinstance(x,(dict,list)):out.extend(keys(x,path,depth+1))
 elif isinstance(v,list):
  for x in v[:10]:out.extend(keys(x,p+'[]',depth+1))
 return out

dash=get('/api/dashboard'); material=get('/api/materials'); config=get('/api/config/game')
for name,data in [('dashboard',dash),('materials',material)]:
 found=keys(data)
 for i in range(0,min(len(found),100),30): print('::notice::ASTRAL_DETAIL='+json.dumps({'path':name,'fields':found[i:i+30]},separators=(',',':'))[:1800])
if isinstance(material,dict):
 catalyst=[x for x in material.get('materials',[]) if isinstance(x,dict) and x.get('slug')=='astral-catalyst']
 print('::notice::CATALYST_MATERIAL='+json.dumps(catalyst,ensure_ascii=False)[:1800])
 print('::notice::MATERIALS_ROOT_KEYS='+json.dumps(list(material.keys())))
if isinstance(config,dict):
 print('::notice::CATALYST_CONFIG='+json.dumps(config.get('itemization',{}).get('drop_bonus_enhancement',{}),ensure_ascii=False)[:1800])
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
  for raw in re.findall(rb'[ -~]{4,250}',blob):
   t=raw.decode('ascii');l=t.lower()
   if any(w in l for w in ('catalys','astral','pity')) and len(t)<=200:strings.add(t)
 matches=sorted(strings,key=lambda x:(not bool(re.search(r'(?i)(pity|drop|kill|rate|chance|timer|astral.catalyst|catalyst)',x)),x.lower()))
 for i in range(0,min(len(matches),60),20): print('::notice::ASTRAL_STRINGS='+json.dumps(matches[i:i+20])[:1800])
except Exception as e:print('::notice::ASTRAL_APP_ERROR='+type(e).__name__)
