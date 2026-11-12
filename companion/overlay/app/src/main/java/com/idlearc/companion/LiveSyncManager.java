package com.idlearc.companion;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Single opt-in live dashboard poller shared by the activity and foreground widget. */
public final class LiveSyncManager {
    public interface Listener {
        void onLiveData();
        void onLiveStatus(String status);
    }
    private static final LiveSyncManager INSTANCE = new LiveSyncManager();
    public static LiveSyncManager get() { return INSTANCE; }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Object, Listener> owners = new LinkedHashMap<>();
    private Context app;
    private int generation;
    private int failures;
    private boolean paused;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (owners.isEmpty() || paused) return;
            if (!IdleArcApiClient.hasSavedCredentials(app)) {
                paused = true;
                notifyStatus("LIVE: нужен вход в аккаунт");
                return;
            }
            final int requestGeneration = generation;
            IdleArcApiClient.syncDashboardAsync(app, new IdleArcApiClient.ApiCallback() {
                @Override public void onSuccess(ScreenAnalyzer.ScanResult ignored) {
                    handler.post(new Runnable() { @Override public void run() {
                        if (requestGeneration != generation || owners.isEmpty()) return;
                        failures = 0;
                        String time = new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                .format(new java.util.Date(IdleArcApiClient.getLastSyncTimeMs()));
                        notifyStatus("LIVE: обновлено " + time);
                        for (Listener l : new ArrayList<>(owners.values())) l.onLiveData();
                        handler.postDelayed(tick, 1000L);
                    }});
                }
                @Override public void onError(final String error) {
                    handler.post(new Runnable() { @Override public void run() {
                        if (requestGeneration != generation || owners.isEmpty()) return;
                        if (IdleArcApiClient.SYNC_BUSY_MESSAGE.equals(error)) {
                            handler.postDelayed(tick, 1000L);
                            return;
                        }
                        String e = error == null ? "" : error.toLowerCase(Locale.ROOT);
                        if (e.contains("401") || e.contains("403") || e.contains("учётных данных")) {
                            paused = true;
                            notifyStatus("LIVE приостановлен: проверь вход в аккаунт");
                            return;
                        }
                        failures++;
                        long delay = LivePollPolicy.failureDelayMs(failures, e.contains("429"));
                        notifyStatus("LIVE: нет обновления, повтор через " + (delay / 1000) + "с");
                        handler.postDelayed(tick, delay);
                    }});
                }
            });
        }
    };
    private LiveSyncManager() {}

    /** Calls must be made on the main thread. Each caller removes itself when no longer visible/running. */
    public void add(Context context, Object owner, Listener listener) {
        boolean wasEmpty = owners.isEmpty();
        app = context.getApplicationContext();
        owners.put(owner, listener);
        if (wasEmpty || paused) {
            paused = false;
            failures = 0;
            generation++;
            handler.removeCallbacks(tick);
            notifyStatus("LIVE: подключение…");
            handler.post(tick);
        } else listener.onLiveStatus("LIVE включён");
    }
    public void remove(Object owner) {
        owners.remove(owner);
        if (owners.isEmpty()) {
            generation++;
            handler.removeCallbacks(tick);
            paused = false;
            failures = 0;
        }
    }
    private void notifyStatus(String value) {
        for (Listener l : new ArrayList<>(owners.values())) l.onLiveStatus(value);
    }
}
