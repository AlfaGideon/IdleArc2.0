"""Deterministic lifecycle tests with fake UI scheduler and fake network; no real API calls."""
import subprocess
import tempfile
import unittest
from pathlib import Path
import jdk4py

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'companion/overlay/app/src/main/java/com/idlearc/companion'
ECJ = Path('/tmp/android-tools/ecj.jar')

class LiveManagerTest(unittest.TestCase):
    @unittest.skipUnless(ECJ.exists() or (jdk4py.JAVA_HOME / 'bin/javac').exists(), 'Java compiler unavailable')
    def test_shared_loop_backoff_and_lifecycle(self):
        with tempfile.TemporaryDirectory() as d:
            t = Path(d)
            files = {
                'android/content/Context.java': '''package android.content; public class Context { public Context getApplicationContext(){ return this; } }''',
                'android/os/SystemClock.java': '''package android.os; public class SystemClock { public static long elapsedRealtime(){return System.currentTimeMillis();} }''',
                'android/os/Looper.java': '''package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }''',
                'android/os/Handler.java': '''package android.os;
import java.util.*;
public class Handler {
 public static Handler last; public final List<Runnable> queue = new ArrayList<>(); public long delay;
 public Handler(Looper l){last=this;} public boolean post(Runnable r){queue.add(r); return true;}
 public boolean postDelayed(Runnable r,long ms){delay=ms;queue.add(r);return true;}
 public void removeCallbacks(Runnable r){queue.removeIf(x -> x==r);} public void next(){queue.remove(0).run();}
}''',
                'com/idlearc/companion/ScreenAnalyzer.java': '''package com.idlearc.companion; public class ScreenAnalyzer { public static class ScanResult {} }''',
                'com/idlearc/companion/IdleArcApiClient.java': '''package com.idlearc.companion;
import android.content.Context;
public class IdleArcApiClient {
 public static final String SYNC_BUSY_MESSAGE="SYNC_BUSY", LIVE_CANCELLED="LIVE_CANCELLED", LIVE_NEEDS_DASHBOARD="LIVE_NEEDS_DASHBOARD"; public static boolean creds=true;
 public static int calls, resource=-1; public static ApiCallback pending; public static LiveResourceInterest interest;
 public interface ApiCallback { void onSuccess(ScreenAnalyzer.ScanResult result); void onError(String msg); }
 public interface LiveResourceInterest {boolean isInterested(int resource);}
 public static boolean hasSavedCredentials(Context c){return creds;}
 public static void syncDashboardAsync(Context c,ApiCallback cb){calls++;resource=-1;pending=cb;}
 public static void syncLiveSectionAsync(Context c,int r,LiveResourceInterest i,ApiCallback cb){calls++;resource=r;interest=i;pending=cb;}
 public static long getLastSyncTimeMs(){return 10000;}
 public static long getCredentialGeneration(){return 1;}
}''',
                'com/idlearc/companion/ManagerSuite.java': '''package com.idlearc.companion;
import android.content.Context; import android.os.Handler;
public class ManagerSuite {
 static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
 static class L implements LiveSyncManager.Listener {int updates;String state="";public void onLiveData(){updates++;}public void onLiveStatus(String s){state=s;}}
 public static void main(String[] args){
  LiveSyncManager m=LiveSyncManager.get(); Context ctx=new Context(); Object a=new Object(),b=new Object();L x=new L(),y=new L();
  m.add(ctx,a,x,1);m.add(ctx,b,y,1); Handler h=Handler.last; check(h.queue.size()==1,"shared scheduler");
  h.next();check(IdleArcApiClient.calls==1&&IdleArcApiClient.resource==-1,"one dashboard call");
  IdleArcApiClient.pending.onSuccess(null);h.next();check(x.updates==1&&y.updates==1,"both owners notified");check(h.delay==1000,"one second after response");
  m.setTab(a,-1);m.setTab(b,-1);check(h.queue.isEmpty(),"polling pauses when no section visible");
  m.setTab(a,3);check(h.queue.size()==1,"opening a section resumes polling");h.next();check(IdleArcApiClient.resource==LiveSections.INVENTORY,"bag requests inventory");
  check(IdleArcApiClient.interest.isInterested(LiveSections.INVENTORY),"bag is interested");
  m.setTab(a,4);check(!IdleArcApiClient.interest.isInterested(LiveSections.INVENTORY),"leaving bag cancels pages");
  IdleArcApiClient.pending.onError(IdleArcApiClient.LIVE_CANCELLED);h.next();h.next();
  check(IdleArcApiClient.resource==-1,"dashboard after cancellation");IdleArcApiClient.pending.onSuccess(null);h.next();h.next();
  check(IdleArcApiClient.resource==LiveSections.GEMS,"gems tab fetches gems and not bag");
  IdleArcApiClient.pending.onError("HTTP 429");h.next();check(h.delay>=59000&&x.state.contains("60"),"rate limit visible and backed off");
  m.remove(a);m.remove(b);check(h.queue.isEmpty(),"last owner stops polling");
  // Section mapping covers radar, bag, gems, pets and dashboard-only tabs.
  check(LiveSections.maskForTab(0)==((1<<LiveSections.BOSS)|(1<<LiveSections.GUILD)),"radar endpoints");
  check(LiveSections.maskForTab(3)==((1<<LiveSections.INVENTORY)|(1<<LiveSections.GEMS)),"bag endpoints");
  check(LiveSections.maskForTab(1)==0 && LiveSections.maskForTab(5)==(1<<LiveSections.GEMS),"other tabs");
 }
}'''
            }
            paths = []
            for name, content in files.items():
                p = t / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(content, encoding='utf-8')
                paths.append(str(p))
            paths.extend([str(SRC / 'LivePollPolicy.java'), str(SRC / 'LiveSections.java'), str(SRC / 'LiveSyncManager.java')])
            cmd = ([str(jdk4py.JAVA), '-jar', str(ECJ), '-17', '-encoding', 'UTF-8', '-d', str(t)]
                   if ECJ.exists() else [str(jdk4py.JAVA_HOME / 'bin/javac'), '-encoding', 'UTF-8', '-d', str(t)])
            subprocess.run(cmd + paths, check=True, capture_output=True)
            subprocess.run([str(jdk4py.JAVA), '-cp', str(t), 'com.idlearc.companion.ManagerSuite'], check=True, capture_output=True)
