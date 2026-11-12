package com.idlearc.companion;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Shared, single-flight polling of the dashboard and only the visible sections. */
public final class LiveSyncManager {
    public interface Listener { void onLiveData(); void onLiveStatus(String status); }
    private static final LiveSyncManager INSTANCE = new LiveSyncManager();
    public static LiveSyncManager get() { return INSTANCE; }
    private static final class Owner {
        final Listener listener;
        int tab;
        Owner(Listener listener, int tab) { this.listener = listener; this.tab = tab; }
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Object, Owner> owners = new LinkedHashMap<>();
    private final long[] resourceDue = new long[LiveSections.COUNT];
    private final long[] resourceTimes = new long[LiveSections.COUNT];
    private final String[] resourceErrors = new String[LiveSections.COUNT];
    private final int[] resourceFailures = new int[LiveSections.COUNT];
    private volatile int wantedMask;
    private Context app;
    private int generation, failures, cursor;
    private boolean paused, pending, scheduled, dashboardNext = true;
    private long retryNotBefore;
    private long seenCredentialGeneration = -1L;
    private String seenCharacter = "";
    private String errorStatus = "";
    private final IdleArcApiClient.LiveResourceInterest interest = new IdleArcApiClient.LiveResourceInterest() {
        @Override public boolean isInterested(int resource) { return (wantedMask & (1 << resource)) != 0; }
        @Override public void onDashboard() {
            handler.post(new Runnable() { @Override public void run() {
                notifyStatus();
                for (Owner o : new ArrayList<>(owners.values())) if (o.tab >= 0) o.listener.onLiveData();
            }});
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            scheduled = false;
            if (!hasVisibleOwner() || paused || pending) return;
            long remaining = retryNotBefore - android.os.SystemClock.elapsedRealtime();
            if (remaining > 0) { schedule(remaining); return; }
            if (!IdleArcApiClient.hasSavedCredentials(app)) {
                paused = true; errorStatus = "LIVE: нужен вход в аккаунт"; notifyStatus(); return;
            }
            final int resource = chooseResource(); // -1 = dashboard
            final int requestGeneration = generation;
            pending = true;
            if (resource >= 0) { errorStatus = "LIVE: обновляю " + LiveSections.label(resource); notifyStatus(); }
            IdleArcApiClient.ApiCallback callback = new IdleArcApiClient.ApiCallback() {
                @Override public void onSuccess(ScreenAnalyzer.ScanResult ignored) {
                    handler.post(new Runnable() { @Override public void run() {
                        pending = false;
                        if (requestGeneration != generation) { schedule(0); return; }
                        if (!hasVisibleOwner()) return;
                        failures = 0; retryNotBefore = 0; errorStatus = "";
                        if (resource >= 0) {
                            resourceErrors[resource] = null;
                            resourceFailures[resource] = 0;
                            resourceTimes[resource] = System.currentTimeMillis();
                            resourceDue[resource] = android.os.SystemClock.elapsedRealtime() + 5000L;
                            dashboardNext = true;
                        } else dashboardNext = false;
                        notifyStatus();
                        for (Owner o : new ArrayList<>(owners.values())) if (o.tab >= 0) o.listener.onLiveData();
                        schedule(1000L);
                    }});
                }
                @Override public void onError(final String error) {
                    handler.post(new Runnable() { @Override public void run() {
                        pending = false;
                        if (requestGeneration != generation) { schedule(0); return; }
                        if (!hasVisibleOwner()) return;
                        if (IdleArcApiClient.LIVE_NEEDS_DASHBOARD.equals(error)) {
                            errorStatus = "LIVE: нет данных персонажа, повтор через 5с";
                            dashboardNext = true; notifyStatus(); schedule(5000L); return;
                        }
                        if (IdleArcApiClient.SYNC_BUSY_MESSAGE.equals(error) || IdleArcApiClient.LIVE_CANCELLED.equals(error)) {
                            errorStatus = ""; dashboardNext = true; notifyStatus(); schedule(1000L); return;
                        }
                        String e = error == null ? "" : error.toLowerCase(Locale.ROOT);
                        if (e.contains("401") || e.contains("403") || e.contains("учётных данных")) {
                            paused = true; errorStatus = "LIVE приостановлен: проверь вход в аккаунт"; notifyStatus(); return;
                        }
                        if (resource >= 0 && !e.contains("429")) {
                            resourceFailures[resource]++;
                            long delay = e.contains("404") ? 300_000L
                                    : LivePollPolicy.failureDelayMs(resourceFailures[resource], false);
                            resourceDue[resource] = android.os.SystemClock.elapsedRealtime() + delay;
                            resourceErrors[resource] = "ошибка, последнее " + time(resourceTimes[resource]);
                            errorStatus = ""; dashboardNext = true; notifyStatus(); schedule(1000L); return;
                        }
                        failures++;
                        long delay = LivePollPolicy.failureDelayMs(failures, e.contains("429"));
                        retryNotBefore = android.os.SystemClock.elapsedRealtime() + delay;
                        errorStatus = "LIVE: ошибка " + (resource < 0 ? "основных данных" : LiveSections.label(resource))
                                + " (последнее: " + time(resource < 0 ? IdleArcApiClient.getLastSyncTimeMs() : resourceTimes[resource])
                                + "), повтор через " + (delay / 1000) + "с";
                        notifyStatus(); schedule(delay);
                    }});
                }
            };
            if (resource < 0) IdleArcApiClient.syncDashboardAsync(app, callback);
            else IdleArcApiClient.syncLiveSectionAsync(app, resource, interest, callback);
        }
    };
    private LiveSyncManager() {}

    /** All owner methods run on the main thread. tab=-1 means no visible section. */
    public void add(Context context, Object owner, Listener listener, int tab) {
        boolean empty = owners.isEmpty();
        app = context.getApplicationContext();
        owners.put(owner, new Owner(listener, tab));
        if (empty) { generation++; paused = false; failures = 0; dashboardNext = true; }
        recomputeMask(); notifyStatus(); schedule(0);
    }
    /** Re-evaluate a paused poll after login/logout; discard callbacks from the old account. */
    public void credentialsChanged() {
        generation++;
        paused = false;
        failures = 0;
        errorStatus = "";
        dashboardNext = true;
        handler.removeCallbacks(tick);
        scheduled = false;
        notifyStatus();
        schedule(0);
    }
    public void setTab(Object owner, int tab) {
        Owner o = owners.get(owner);
        if (o == null || o.tab == tab) return;
        o.tab = tab;
        recomputeMask(); notifyStatus();
        if (hasVisibleOwner()) schedule(0);
        else { handler.removeCallbacks(tick); scheduled = false; }
    }
    public void remove(Object owner) {
        owners.remove(owner);
        recomputeMask();
        if (owners.isEmpty()) {
            generation++; handler.removeCallbacks(tick); scheduled = false; paused = false; failures = 0;
        } else if (!hasVisibleOwner()) { handler.removeCallbacks(tick); scheduled = false; }
        else { notifyStatus(); schedule(0); }
    }
    private void recomputeMask() {
        int mask = 0;
        for (Owner o : owners.values()) if (o.tab >= 0) mask |= LiveSections.maskForTab(o.tab);
        wantedMask = mask;
    }
    private boolean hasVisibleOwner() {
        // A running collapsed foreground widget still samples the dashboard for farm history.
        return !owners.isEmpty();
    }
    private int chooseResource() {
        if (dashboardNext || IdleArcApiClient.getLastSyncTimeMs() <= 0) return -1;
        long now = android.os.SystemClock.elapsedRealtime();
        for (int i = 0; i < LiveSections.COUNT; i++) {
            int r = (cursor + i) % LiveSections.COUNT;
            if ((wantedMask & (1 << r)) != 0 && now >= resourceDue[r]) {
                cursor = (r + 1) % LiveSections.COUNT; return r;
            }
        }
        return -1;
    }
    private void schedule(long delay) {
        if (pending || scheduled || paused || !hasVisibleOwner()) return;
        long wait = Math.max(delay, Math.max(0, retryNotBefore - android.os.SystemClock.elapsedRealtime()));
        scheduled = true;
        handler.postDelayed(tick, wait);
    }
    private void notifyStatus() {
        long currentGeneration = IdleArcApiClient.getCredentialGeneration();
        String character = IdleArcApiClient.getLiveCharacterId();
        if (currentGeneration != seenCredentialGeneration || !character.equals(seenCharacter)) {
            java.util.Arrays.fill(resourceTimes, 0L);
            java.util.Arrays.fill(resourceDue, 0L);
            java.util.Arrays.fill(resourceErrors, null);
            java.util.Arrays.fill(resourceFailures, 0);
            seenCredentialGeneration = currentGeneration;
            seenCharacter = character;
        }
        for (Owner o : new ArrayList<>(owners.values())) {
            if (o.tab < 0) { o.listener.onLiveStatus("LIVE: фоновый сбор данных"); continue; }
            if (!errorStatus.isEmpty()) { o.listener.onLiveStatus(errorStatus); continue; }
            StringBuilder text = new StringBuilder("LIVE: данные ").append(time(IdleArcApiClient.getLastSyncTimeMs()));
            int mask = LiveSections.maskForTab(o.tab);
            for (int r = 0; r < LiveSections.COUNT; r++) if ((mask & (1 << r)) != 0)
                text.append(" · ").append(LiveSections.label(r)).append(" ")
                        .append(resourceErrors[r] == null ? time(resourceTimes[r]) : resourceErrors[r]);
            o.listener.onLiveStatus(text.toString());
        }
    }
    private String time(long ms) {
        return ms <= 0 ? "—" : new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date(ms));
    }
}
