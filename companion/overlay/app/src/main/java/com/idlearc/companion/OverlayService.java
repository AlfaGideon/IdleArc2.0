package com.idlearc.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;

/**
 * Плавающий API-виджет Druid Helper v3.1 поверх IdleArc.
 * Работает без захвата экрана, рисует нативные визуальные карточки DashboardCardRenderer
 * и синхронизирует откаты с системным будильником телефона.
 */
public class OverlayService extends Service {

    private static final String CHANNEL_ID = "druid_helper_api_v31";
    private static final int NOTIF_ID = 4203;
    private static volatile boolean running = false;

    private WindowManager wm;
    private FrameLayout rootContainer;
    private View bubbleView;
    private LinearLayout cardView;
    private WindowManager.LayoutParams winParams;

    private DruidProfile profile;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean syncInFlight = false;
    private int autoSyncSec = 0; // 0 = выкл, 1 = LIVE dashboard, 30/60 = full sync
    private String liveStatus = "";
    private long lastInteractionMs;
    private ScrollView bodyScrollView;
    private final LiveSyncManager.Listener liveListener = new LiveSyncManager.Listener() {
        @Override public void onLiveData() {
            if (!running || autoSyncSec != 1) return;
            IdleArcApiClient.applyDashboardToProfile(IdleArcApiClient.getCachedDashboard(), profile);
            if (cardView != null && cardView.getVisibility() == View.VISIBLE
                    && android.os.SystemClock.uptimeMillis() - lastInteractionMs >= 2000L) {
                final int y = bodyScrollView != null ? bodyScrollView.getScrollY() : 0;
                refreshPanelContent();
                if (bodyScrollView != null) bodyScrollView.post(new Runnable() {
                    @Override public void run() { bodyScrollView.scrollTo(0, y); }
                });
            }
        }
        @Override public void onLiveStatus(String status) {
            liveStatus = status;
            if (running && autoSyncSec == 1 && headerSubTv != null) headerSubTv.setText(status);
        }
    };

    private int activeTab = 0;
    private ApiDashboardEngine.EquippedSlotKey selectedSlot = ApiDashboardEngine.EquippedSlotKey.ALL;

    private TextView headerSubTv;
    private Button syncBtn;
    private Button autoSyncBtn;
    private LinearLayout cardsContainer;
    private HorizontalScrollView slotChipScroll;
    private final Button[] modeButtons = new Button[DruidProfile.BuildMode.values().length];
    private final Button[] tabButtons = new Button[7];
    private Button[] slotChipButtons;

    private static final String[] TAB_TITLES = {
        "🔔 Радар",
        "📊 Герой",
        "🛡️ Шмот",
        "🎒 Сумка",
        "💎 Камни",
        "🐾 Петы",
        "🌳 Навыки"
    };

    private final Runnable autoSyncRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running || autoSyncSec <= 0) return;
            if (cardView != null && cardView.getVisibility() == View.VISIBLE) {
                triggerApiSync(false);
            }
            uiHandler.postDelayed(this, autoSyncSec * 1000L);
        }
    };

    public static boolean isRunning() {
        return running;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        profile = DruidProfile.load(this);
        IdleArcApiClient.ensureCacheLoaded(this, profile);
        startForegroundWithNotification();

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        winParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        winParams.gravity = Gravity.TOP | Gravity.START;
        winParams.x = dp(10);
        winParams.y = dp(130);

        rootContainer = new FrameLayout(this) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                lastInteractionMs = android.os.SystemClock.uptimeMillis();
                return super.dispatchTouchEvent(event);
            }
        };
        bubbleView = buildBubbleView();
        cardView = buildExpandedCardView();
        cardView.setVisibility(View.GONE);

        rootContainer.addView(bubbleView);
        rootContainer.addView(cardView);
        wm.addView(rootContainer, winParams);

        refreshPanelContent();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        profile = DruidProfile.load(this);
        refreshPanelContent();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        uiHandler.removeCallbacks(autoSyncRunnable);
        LiveSyncManager.get().remove(this);
        if (wm != null && rootContainer != null) {
            try {
                wm.removeView(rootContainer);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    private View buildBubbleView() {
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_app_medallion);
        int sz = dp(50);
        int pad = dp(3);
        iv.setPadding(pad, pad, pad, pad);
        iv.setLayoutParams(new FrameLayout.LayoutParams(sz, sz));

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.parseColor("#F00C1322"));
        circle.setStroke(dp(2), Color.parseColor("#FFD54F"));
        iv.setBackground(circle);

        attachDragAndClick(iv, new Runnable() {
            @Override
            public void run() {
                profile = DruidProfile.load(OverlayService.this);
                bubbleView.setVisibility(View.GONE);
                cardView.setVisibility(View.VISIBLE);
                if (autoSyncSec == 1) LiveSyncManager.get().setTab(OverlayService.this, activeTab);
                wm.updateViewLayout(rootContainer, winParams);
                refreshPanelContent();
                if (IdleArcApiClient.hasSavedCredentials(OverlayService.this)) {
                    long ageMs = System.currentTimeMillis() - IdleArcApiClient.getLastSyncTimeMs();
                    if (autoSyncSec != 1 && ageMs > 20_000L) {
                        triggerApiSync(false);
                    }
                }
            }
        });
        return iv;
    }

    private LinearLayout buildExpandedCardView() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int cardW = Math.min(dp(380), (int) (dm.widthPixels * 0.95f));
        int maxScrollH = (int) (dm.heightPixels * 0.62f);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int p = dp(9);
        panel.setPadding(p, dp(8), p, p);
        panel.setLayoutParams(new FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F5091118"));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#4FC3F7"));
        panel.setBackground(bg);

        // 1. Шапка плавающего окна (перетаскиваемая)
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleTv = new TextView(this);
        titleTv.setText("⭐ IdleArcHelper v" + OtaUpdater.getCurrentVersionName(this));
        titleTv.setTextColor(Color.parseColor("#FFD54F"));
        titleTv.setTextSize(13.5f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(titleTv);

        headerSubTv = new TextView(this);
        headerSubTv.setTextColor(Color.parseColor("#90CAF9"));
        headerSubTv.setTextSize(10.5f);
        titleCol.addView(headerSubTv);
        attachDragAndClick(titleCol, null);
        topBar.addView(titleCol);

        syncBtn = miniBtn("🔄 API", "#174236", "#69F0AE");
        syncBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerApiSync(true);
            }
        });
        topBar.addView(syncBtn, marginRightLp(4));

        autoSyncBtn = miniBtn("⏱ Выкл", "#1B2834", "#B0BEC5");
        autoSyncBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleAutoSync();
            }
        });
        topBar.addView(autoSyncBtn, marginRightLp(4));

        Button minBtn = miniBtn("—", "#263238", "#ECEFF1");
        minBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cardView.setVisibility(View.GONE);
                if (autoSyncSec == 1) LiveSyncManager.get().setTab(OverlayService.this, -1);
                bubbleView.setVisibility(View.VISIBLE);
                wm.updateViewLayout(rootContainer, winParams);
            }
        });
        topBar.addView(minBtn);
        panel.addView(topBar);

        // 2. Режим расчёта (Пуш / Фарм / Босс)
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, dp(5), 0, dp(5));
        final DruidProfile.BuildMode[] modes = DruidProfile.BuildMode.values();
        for (int i = 0; i < modes.length; i++) {
            final int idx = i;
            Button mb = miniBtn(modes[i].title, "#121D27", "#90A4AE");
            mb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    profile.mode = modes[idx];
                    profile.save(OverlayService.this);
                    refreshPanelContent();
                }
            });
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < modes.length - 1) mlp.rightMargin = dp(4);
            modeButtons[i] = mb;
            modeRow.addView(mb, mlp);
        }
        panel.addView(modeRow);

        // 3. Лента 7 вкладок
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, 0, 0, dp(5));
        for (int i = 0; i < TAB_TITLES.length; i++) {
            final int tIdx = i;
            Button tb = miniBtn(TAB_TITLES[i], "#121C24", "#90A4AE");
            tb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activeTab = tIdx;
                    if (autoSyncSec == 1) LiveSyncManager.get().setTab(OverlayService.this, activeTab);
                    refreshPanelContent();
                }
            });
            tabButtons[i] = tb;
            tabRow.addView(tb, marginRightLp(4));
        }
        tabScroll.addView(tabRow);
        panel.addView(tabScroll);

        // 4. Под-чипы 10 слотов для вкладки «🛡️ Шмот»
        slotChipScroll = new HorizontalScrollView(this);
        slotChipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, 0, 0, dp(5));
        final ApiDashboardEngine.EquippedSlotKey[] keys = ApiDashboardEngine.EquippedSlotKey.values();
        slotChipButtons = new Button[keys.length];
        for (int i = 0; i < keys.length; i++) {
            final ApiDashboardEngine.EquippedSlotKey sk = keys[i];
            Button cb = miniBtn(sk.chipTitle, "#121C24", "#90A4AE");
            cb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedSlot = sk;
                    refreshPanelContent();
                }
            });
            slotChipButtons[i] = cb;
            chipRow.addView(cb, marginRightLp(4));
        }
        slotChipScroll.addView(chipRow);
        panel.addView(slotChipScroll);

        // 5. Прокручиваемый контейнер визуальных карточек
        ScrollView bodyScroll = new ScrollView(this);
        bodyScrollView = bodyScroll;
        bodyScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxScrollH));
        cardsContainer = new LinearLayout(this);
        cardsContainer.setOrientation(LinearLayout.VERTICAL);
        bodyScroll.addView(cardsContainer);
        panel.addView(bodyScroll);

        return panel;
    }

    private void cycleAutoSync() {
        uiHandler.removeCallbacks(autoSyncRunnable);
        LiveSyncManager.get().remove(this);
        if (autoSyncSec == 0) autoSyncSec = 1;
        else if (autoSyncSec == 1) autoSyncSec = 30;
        else if (autoSyncSec == 30) autoSyncSec = 60;
        else autoSyncSec = 0;
        if (autoSyncSec == 1) {
            if (!IdleArcApiClient.hasSavedCredentials(this)) {
                autoSyncSec = 0;
                Toast.makeText(this, "Сначала подключи аккаунт в приложении", Toast.LENGTH_LONG).show();
            } else {
                autoSyncBtn.setText("● LIVE");
                styleMini(autoSyncBtn, "#1B4332", "#69F0AE", true);
                LiveSyncManager.get().add(this, this, liveListener, activeTab);
                Toast.makeText(this, "LIVE обновляет только открытую вкладку. При сворачивании опрос приостанавливается.", Toast.LENGTH_LONG).show();
                return;
            }
        }
        liveStatus = "";
        if (autoSyncSec > 0) {
            autoSyncBtn.setText("⏱ " + autoSyncSec + "с");
            styleMini(autoSyncBtn, "#1B4332", "#69F0AE", true);
            uiHandler.postDelayed(autoSyncRunnable, autoSyncSec * 1000L);
        } else {
            autoSyncBtn.setText("⏱ Выкл");
            styleMini(autoSyncBtn, "#1B2834", "#B0BEC5", false);
        }
        refreshPanelContent();
    }

    private void triggerApiSync(final boolean showToast) { triggerApiSync(showToast, 0); }

    private void triggerApiSync(final boolean showToast, final int retry) {
        if (syncInFlight) return;
        if (!IdleArcApiClient.hasSavedCredentials(this)) {
            Toast.makeText(this, "Открой Druid Helper и подключи аккаунт API", Toast.LENGTH_LONG).show();
            return;
        }
        syncInFlight = true;
        syncBtn.setText("⏳...");
        IdleArcApiClient.syncAsync(this, profile, new IdleArcApiClient.ApiCallback() {
            @Override
            public void onSuccess(final ScreenAnalyzer.ScanResult result) {
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        syncInFlight = false;
                        syncBtn.setText("🔄 API");
                        Map<String, Object> dash = IdleArcApiClient.getCachedDashboard();
                        SystemAlarmAndNotifier.processDashboardEvents(OverlayService.this, dash);
                        refreshPanelContent();
                        if (showToast) {
                            Toast.makeText(OverlayService.this,
                                    "✅ API синхронизирован (Ур. " + profile.charLevel + ")",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }

            @Override
            public void onError(final String errorMessage) {
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        syncInFlight = false;
                        syncBtn.setText("🔄 API");
                        if (showToast && IdleArcApiClient.SYNC_BUSY_MESSAGE.equals(errorMessage) && retry < 6 && running) {
                            uiHandler.postDelayed(new Runnable() { @Override public void run() {
                                if (running) triggerApiSync(true, retry + 1);
                            }}, 300L);
                            return;
                        }
                        if (showToast) {
                            Toast.makeText(OverlayService.this, IdleArcApiClient.SYNC_BUSY_MESSAGE.equals(errorMessage)
                                    ? "Синхронизация уже идёт, повторите через секунду." : "Ошибка API: " + errorMessage, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    private void refreshPanelContent() {
        if (cardView == null) return;
        Map<String, Object> dash = IdleArcApiClient.getCachedDashboard();
        Map<String, Object> cfg = IdleArcApiClient.getCachedGameConfig();

        if (dash != null && !dash.isEmpty()) {
            long ageSec = Math.max(0L, (System.currentTimeMillis() - IdleArcApiClient.getLastSyncTimeMs()) / 1000L);
            headerSubTv.setText(SimpleJson.str(dash, "name", "Герой")
                    + " · Ур." + profile.charLevel + " · " + ageSec + "с назад");
        } else {
            headerSubTv.setText("Нет слепка — нажми 🔄 API");
        }

        if (autoSyncSec == 1 && !liveStatus.isEmpty()) headerSubTv.setText(liveStatus);
        DruidProfile.BuildMode[] modes = DruidProfile.BuildMode.values();
        for (int i = 0; i < modes.length; i++) {
            boolean sel = (profile.mode == modes[i]);
            styleMini(modeButtons[i], sel ? "#1B4B3A" : "#121D27", sel ? "#69F0AE" : "#90A4AE", sel);
        }
        for (int i = 0; i < tabButtons.length; i++) {
            boolean sel = (activeTab == i);
            styleMini(tabButtons[i], sel ? "#1A374D" : "#121C24", sel ? "#4FC3F7" : "#90A4AE", sel);
        }

        slotChipScroll.setVisibility(activeTab == 2 ? View.VISIBLE : View.GONE);
        if (activeTab == 2 && slotChipButtons != null) {
            ApiDashboardEngine.EquippedSlotKey[] keys = ApiDashboardEngine.EquippedSlotKey.values();
            for (int i = 0; i < keys.length; i++) {
                boolean sel = (selectedSlot == keys[i]);
                styleMini(slotChipButtons[i], sel ? "#2F2418" : "#121C24", sel ? "#FFD54F" : "#90A4AE", sel);
            }
        }

        DashboardCardRenderer.UiRefreshCallback cb = new DashboardCardRenderer.UiRefreshCallback() {
            @Override
            public void onRequestRefresh() {
                refreshPanelContent();
            }
            @Override
            public void onRequestSync() {
                triggerApiSync(true);
            }
        };

        switch (activeTab) {
            case 0:
                DashboardCardRenderer.renderRadarTab(this, cardsContainer, dash, profile, cb);
                break;
            case 1:
                DashboardCardRenderer.renderHeroSummaryTab(this, cardsContainer, dash, profile);
                break;
            case 2:
                DashboardCardRenderer.renderEquipmentTab(this, cardsContainer, dash, profile, selectedSlot);
                break;
            case 3:
                DashboardCardRenderer.renderBagAndResourcesTab(this, cardsContainer, dash, profile);
                break;
            case 4:
                DashboardCardRenderer.renderGemsTab(this, cardsContainer, dash, profile);
                break;
            case 5:
                DashboardCardRenderer.renderPetsAndTalismansTab(this, cardsContainer, dash, profile);
                break;
            case 6:
            default:
                DashboardCardRenderer.renderSkillsAndWorldTab(this, cardsContainer, dash, profile, cfg);
                break;
        }
    }

    private Button miniBtn(String text, String bgHex, String fgHex) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(11f);
        b.setMinimumHeight(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinWidth(0);
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        styleMini(b, bgHex, fgHex, false);
        return b;
    }

    private void styleMini(Button b, String bgHex, String fgHex, boolean bold) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        gd.setCornerRadius(dp(7));
        gd.setStroke(dp(1), Color.parseColor(bold ? fgHex : "#243442"));
        b.setBackground(gd);
        b.setTextColor(Color.parseColor(fgHex));
        b.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private LinearLayout.LayoutParams marginRightLp(int dpRight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(dpRight);
        return lp;
    }

    private void attachDragAndClick(View handle, final Runnable onClickAction) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float touchX, touchY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = winParams.x;
                        startY = winParams.y;
                        touchX = ev.getRawX();
                        touchY = ev.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(ev.getRawX() - touchX);
                        int dy = Math.round(ev.getRawY() - touchY);
                        if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) {
                            moved = true;
                        }
                        if (moved) {
                            winParams.x = startX + dx;
                            winParams.y = startY + dy;
                            wm.updateViewLayout(rootContainer, winParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved && onClickAction != null) {
                            onClickAction.run();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void startForegroundWithNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Druid Helper API Widget", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent openIntent = new Intent(this, MainActivity.class);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification n = nb.setContentTitle("⭐ IdleArcHelper v" + OtaUpdater.getCurrentVersionName(this))
                .setContentText("Мультиклассовый помощник, Сумка и Будильник откатов активны")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, n);
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }
}
