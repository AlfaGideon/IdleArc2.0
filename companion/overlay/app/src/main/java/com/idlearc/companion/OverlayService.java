package com.idlearc.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Плавающий API-виджет Druid Helper v3.0 (Pure API Edition).
 * Работает напрямую от https://idlearc.com/api/dashboard без захвата экрана и без OCR.
 */
public class OverlayService extends Service {

    public static final String ACTION_STOP = "com.idlearc.companion.STOP";
    public static final String ACTION_REFRESH_UI = "com.idlearc.companion.REFRESH_UI";

    private static final String CHANNEL_ID = "druid_api_helper_v3";
    private static final int NOTIF_ID = 7703;
    private static final long AUTO_SYNC_INTERVAL_MS = 30000L;

    private WindowManager wm;
    private WindowManager.LayoutParams overlayParams;
    private FrameLayout rootContainer;
    private LinearLayout collapsedPill;
    private LinearLayout expandedCard;

    private TextView pillStatusTv;
    private TextView pillSyncBtn;
    private TextView pillAutoBtn;

    private TextView headerTitleTv;
    private TextView syncNowBtn;
    private TextView autoToggleBtn;
    private TextView subHeaderStatusTv;
    private TextView cardBodyTv;
    private HorizontalScrollView slotBarScroll;

    private final TextView[] modeButtons = new TextView[DruidProfile.BuildMode.values().length];
    private final TextView[] tabButtons = new TextView[6];
    private final TextView[] slotButtons = new TextView[ApiDashboardEngine.EquippedSlotKey.values().length];

    private boolean isExpanded = false;
    private boolean autoSyncEnabled = false;
    private boolean syncInProgress = false;
    private int activeTab = 0; // 0=Сводка, 1=Шмот(10), 2=Сумка, 3=Камни(20), 4=Петы/Талисм, 5=Навыки
    private ApiDashboardEngine.EquippedSlotKey selectedSlotKey = ApiDashboardEngine.EquippedSlotKey.ALL;

    private DruidProfile profile;
    private Handler mainHandler;

    private final Runnable autoSyncTick = new Runnable() {
        @Override
        public void run() {
            if (!autoSyncEnabled) return;
            triggerApiSync(true);
            mainHandler.postDelayed(this, AUTO_SYNC_INTERVAL_MS);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        profile = DruidProfile.load(this);
        IdleArcApiClient.ensureCacheLoaded(this, profile);
        mainHandler = new Handler(Looper.getMainLooper());
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        startForegroundCompat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разреши показ поверх других окон для Druid Helper", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }

        profile = DruidProfile.load(this);
        IdleArcApiClient.ensureCacheLoaded(this, profile);

        if (rootContainer == null) {
            buildOverlayUi();
        }
        refreshUiContent();

        if (intent == null || !ACTION_REFRESH_UI.equals(intent.getAction())) {
            if (IdleArcApiClient.hasSavedCredentials(this) && IdleArcApiClient.getCachedDashboard() == null) {
                triggerApiSync(true);
            }
        }
        return START_STICKY;
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "IdleArc Druid API Helper", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Прямая синхронизация с сервером IdleArc API поверх игры");
            nm.createNotificationChannel(ch);
        }

        Intent stopIntent = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags);

        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, openApp, piFlags);

        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notif = nb
                .setContentTitle("🌿 Druid Helper v" + OtaUpdater.getCurrentVersionName(this) + " (API)")
                .setContentText("Прямая синхронизация с idlearc.com/api/dashboard активна")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Выключить", stopPi)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }

    private void buildOverlayUi() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = Math.min(dm.widthPixels, dm.heightPixels);

        rootContainer = new FrameLayout(this);

        // 1. КОМПАКТНАЯ ТАБЛЕТКА ПОВЕРХ ИГРЫ
        collapsedPill = new LinearLayout(this);
        collapsedPill.setOrientation(LinearLayout.HORIZONTAL);
        collapsedPill.setGravity(Gravity.CENTER_VERTICAL);
        collapsedPill.setPadding(dp(10), dp(6), dp(8), dp(6));
        collapsedPill.setBackground(roundRect(0xEB101923, 0xFF38BDF8, dp(1), dp(22)));

        pillStatusTv = new TextView(this);
        pillStatusTv.setText("🌿 API");
        pillStatusTv.setTextColor(0xFFECFDF5);
        pillStatusTv.setTypeface(Typeface.DEFAULT_BOLD);
        pillStatusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        pillStatusTv.setPadding(0, 0, dp(8), 0);
        attachDragAndClick(pillStatusTv, new Runnable() {
            @Override
            public void run() {
                setExpanded(true);
            }
        });

        pillSyncBtn = makeButton("🔄 API", 0xFF0284C7, 0xFFFFFFFF, 11.2f);
        pillSyncBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerApiSync(false);
            }
        });

        pillAutoBtn = makeButton("⏱️", 0xFF1E293B, 0xFFE2E8F0, 11.2f);
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        autoLp.leftMargin = dp(5);
        pillAutoBtn.setLayoutParams(autoLp);
        pillAutoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleAutoSync();
            }
        });

        collapsedPill.addView(pillStatusTv);
        collapsedPill.addView(pillSyncBtn);
        collapsedPill.addView(pillAutoBtn);

        // 2. РАЗВЁРНУТАЯ АНАЛИТИЧЕСКАЯ ПАНЕЛЬ (6 ВКЛАДОК API)
        expandedCard = new LinearLayout(this);
        expandedCard.setOrientation(LinearLayout.VERTICAL);
        expandedCard.setPadding(dp(11), dp(10), dp(11), dp(11));
        expandedCard.setBackground(roundRect(0xF5111827, 0xFF38BDF8, dp(1), dp(16)));
        int cardW = Math.min(dp(385), (int) (screenW * 0.95f));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(cardW, FrameLayout.LayoutParams.WRAP_CONTENT);
        expandedCard.setLayoutParams(cardLp);
        expandedCard.setVisibility(View.GONE);

        // Шапка
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        headerTitleTv = new TextView(this);
        headerTitleTv.setText("🌿 Druid API v" + OtaUpdater.getCurrentVersionName(this));
        headerTitleTv.setTextColor(0xFF34D399);
        headerTitleTv.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.8f);
        LinearLayout.LayoutParams htLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        headerTitleTv.setLayoutParams(htLp);
        attachDragAndClick(headerTitleTv, null);

        syncNowBtn = makeButton("🔄 API", 0xFF0284C7, 0xFFFFFFFF, 11f);
        syncNowBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerApiSync(false);
            }
        });

        autoToggleBtn = makeButton("⏱️30с", 0xFF1E293B, 0xFFE2E8F0, 10.5f);
        LinearLayout.LayoutParams atLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        atLp.leftMargin = dp(4);
        autoToggleBtn.setLayoutParams(atLp);
        autoToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleAutoSync();
            }
        });

        TextView minBtn = makeButton("➖", 0xFF1E293B, 0xFFFBBF24, 11f);
        LinearLayout.LayoutParams minLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        minLp.leftMargin = dp(4);
        minBtn.setLayoutParams(minLp);
        minBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setExpanded(false);
            }
        });

        TextView closeBtn = makeButton("✖", 0xFF3F1D28, 0xFFFCA5A5, 11f);
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clLp.leftMargin = dp(4);
        closeBtn.setLayoutParams(clLp);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopSelf();
            }
        });

        header.addView(headerTitleTv);
        header.addView(syncNowBtn);
        header.addView(autoToggleBtn);
        header.addView(minBtn);
        header.addView(closeBtn);
        expandedCard.addView(header);

        // Ряд 1: 4 режима билда (Пуш / Боссы / Фарм / 1->Кап)
        HorizontalScrollView modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, dp(7), 0, dp(4));
        final DruidProfile.BuildMode[] modes = DruidProfile.BuildMode.values();
        for (int i = 0; i < modes.length; i++) {
            final DruidProfile.BuildMode bm = modes[i];
            TextView b = makeChip(bm.title);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    profile.mode = bm;
                    profile.save(OverlayService.this);
                    refreshUiContent();
                }
            });
            modeButtons[i] = b;
            modeRow.addView(b);
        }
        modeScroll.addView(modeRow);
        expandedCard.addView(modeScroll);

        // Ряд 2: 6 аналитических вкладок API
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, dp(2), 0, dp(5));
        String[] tabNames = {
            "🎯 Сводка", "⚔️ Шмот (10)", "🎒 Сумка", "💎 Камни (20)", "🐾 Петы/Талисм.", "🌳 Навыки"
        };
        for (int i = 0; i < tabNames.length; i++) {
            final int tabIdx = i;
            TextView tb = makeChip(tabNames[i]);
            tb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activeTab = tabIdx;
                    refreshUiContent();
                }
            });
            tabButtons[i] = tb;
            tabRow.addView(tb);
        }
        tabScroll.addView(tabRow);
        expandedCard.addView(tabScroll);

        // Ряд 3 (для вкладки «Шмот (10)»): быстрый выбор любого из 10 реальных слотов
        slotBarScroll = new HorizontalScrollView(this);
        slotBarScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout slotRow = new LinearLayout(this);
        slotRow.setOrientation(LinearLayout.HORIZONTAL);
        slotRow.setPadding(0, dp(2), 0, dp(5));
        final ApiDashboardEngine.EquippedSlotKey[] slotKeys = ApiDashboardEngine.EquippedSlotKey.values();
        for (int i = 0; i < slotKeys.length; i++) {
            final ApiDashboardEngine.EquippedSlotKey sk = slotKeys[i];
            TextView sbBtn = makeChip(sk.chipTitle);
            sbBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedSlotKey = sk;
                    refreshUiContent();
                }
            });
            slotButtons[i] = sbBtn;
            slotRow.addView(sbBtn);
        }
        slotBarScroll.addView(slotRow);
        slotBarScroll.setVisibility(View.GONE);
        expandedCard.addView(slotBarScroll);

        subHeaderStatusTv = new TextView(this);
        subHeaderStatusTv.setTextColor(0xFF93C5FD);
        subHeaderStatusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.8f);
        subHeaderStatusTv.setPadding(dp(2), 0, dp(2), dp(4));
        expandedCard.addView(subHeaderStatusTv);

        // Прокручиваемое тело отчёта
        ScrollView bodyScroll = new ScrollView(this);
        int maxBodyH = (int) (Math.max(dm.widthPixels, dm.heightPixels) * 0.48f);
        LinearLayout.LayoutParams bsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxBodyH);
        bodyScroll.setLayoutParams(bsLp);

        cardBodyTv = new TextView(this);
        cardBodyTv.setTextColor(0xFFF3F4F6);
        cardBodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        cardBodyTv.setLineSpacing(dp(2), 1.07f);
        cardBodyTv.setPadding(dp(4), dp(4), dp(4), dp(8));
        bodyScroll.addView(cardBodyTv);
        expandedCard.addView(bodyScroll);

        rootContainer.addView(collapsedPill);
        rootContainer.addView(expandedCard);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = dp(10);
        overlayParams.y = dp(90);

        wm.addView(rootContainer, overlayParams);
    }

    private void triggerApiSync(final boolean silent) {
        if (!IdleArcApiClient.hasSavedCredentials(this)) {
            if (!silent) {
                Toast.makeText(this, "Введи Email и пароль от IdleArc в главном окне Druid Helper!", Toast.LENGTH_LONG).show();
                Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
            return;
        }
        if (syncInProgress) return;
        syncInProgress = true;
        if (pillStatusTv != null) pillStatusTv.setText("⏳ API...");
        if (subHeaderStatusTv != null) subHeaderStatusTv.setText("⏳ Загрузка свежего /api/dashboard с сервера...");

        IdleArcApiClient.syncAsync(this, profile, new IdleArcApiClient.ApiCallback() {
            @Override
            public void onSuccess(ScreenAnalyzer.ScanResult scanResult) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        syncInProgress = false;
                        if (!silent) {
                            setExpanded(true);
                            Toast.makeText(OverlayService.this,
                                    "✅ Серверные данные обновлены (GS " + profile.gearScore + ")", Toast.LENGTH_SHORT).show();
                        } else {
                            refreshUiContent();
                        }
                    }
                });
            }

            @Override
            public void onError(final String errorMessage) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        syncInProgress = false;
                        refreshUiContent();
                        if (!silent) {
                            Toast.makeText(OverlayService.this, "❌ API: " + errorMessage, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    private void toggleAutoSync() {
        if (!autoSyncEnabled && !IdleArcApiClient.hasSavedCredentials(this)) {
            Toast.makeText(this, "Сначала подключи аккаунт IdleArc в главном окне!", Toast.LENGTH_LONG).show();
            return;
        }
        autoSyncEnabled = !autoSyncEnabled;
        mainHandler.removeCallbacks(autoSyncTick);
        if (autoSyncEnabled) {
            mainHandler.post(autoSyncTick);
            Toast.makeText(this, "⏱️ Авто-обновление с сервера каждые 30 сек включено", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Авто-обновление выключено", Toast.LENGTH_SHORT).show();
        }
        refreshUiContent();
    }

    private void setExpanded(boolean exp) {
        isExpanded = exp;
        collapsedPill.setVisibility(exp ? View.GONE : View.VISIBLE);
        expandedCard.setVisibility(exp ? View.VISIBLE : View.GONE);
        refreshUiContent();
        if (wm != null && rootContainer != null) {
            wm.updateViewLayout(rootContainer, overlayParams);
        }
    }

    private void refreshUiContent() {
        if (rootContainer == null) return;
        Map<String, Object> dash = IdleArcApiClient.getCachedDashboard();
        String verStr = OtaUpdater.getCurrentVersionName(this);

        if (dash != null) {
            pillStatusTv.setText("🌿 GS " + profile.gearScore + " · " + shortModeLabel(profile.mode));
            headerTitleTv.setText("🌿 API v" + verStr + " · Ур." + profile.charLevel + " · GS " + profile.gearScore);
            long ts = IdleArcApiClient.getLastSyncTimeMs();
            String timeStr = ts > 0 ? new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ts)) : "кэш";
            subHeaderStatusTv.setText("✅ Синхронизировано с idlearc.com (" + timeStr + ") · " + profile.mode.subtitle);
        } else {
            pillStatusTv.setText("🌿 Druid API");
            headerTitleTv.setText("🌿 Druid API v" + verStr);
            subHeaderStatusTv.setText("⚠️ Аккаунт ещё не синхронизирован — нажми «🔄 API»");
        }

        pillAutoBtn.setText(autoSyncEnabled ? "⏱️ВКЛ" : "⏱️");
        pillAutoBtn.setBackground(roundRect(autoSyncEnabled ? 0xFF065F46 : 0xFF1E293B,
                autoSyncEnabled ? 0xFF34D399 : 0xFF475569, dp(1), dp(14)));
        autoToggleBtn.setText(autoSyncEnabled ? "⏱️ВКЛ" : "⏱️30с");
        autoToggleBtn.setBackground(roundRect(autoSyncEnabled ? 0xFF065F46 : 0xFF1E293B,
                autoSyncEnabled ? 0xFF34D399 : 0xFF475569, dp(1), dp(12)));

        DruidProfile.BuildMode[] modes = DruidProfile.BuildMode.values();
        for (int i = 0; i < modes.length; i++) {
            boolean sel = (profile.mode == modes[i]);
            modeButtons[i].setBackground(roundRect(sel ? 0xFF065F46 : 0xFF1F2937,
                    sel ? 0xFF34D399 : 0xFF374151, dp(1), dp(10)));
            modeButtons[i].setTextColor(sel ? 0xFFECFDF5 : 0xFFD1D5DB);
        }

        for (int i = 0; i < tabButtons.length; i++) {
            boolean sel = (activeTab == i);
            tabButtons[i].setBackground(roundRect(sel ? 0xFF0369A1 : 0xFF1F2937,
                    sel ? 0xFF7DD3FC : 0xFF374151, dp(1), dp(10)));
            tabButtons[i].setTextColor(sel ? 0xFFFFFFFF : 0xFFD1D5DB);
        }

        slotBarScroll.setVisibility(activeTab == 1 ? View.VISIBLE : View.GONE);
        if (activeTab == 1) {
            ApiDashboardEngine.EquippedSlotKey[] keys = ApiDashboardEngine.EquippedSlotKey.values();
            for (int i = 0; i < keys.length; i++) {
                boolean sel = (selectedSlotKey == keys[i]);
                slotButtons[i].setBackground(roundRect(sel ? 0xFF5B21B6 : 0xFF1F2937,
                        sel ? 0xFFC4B5FD : 0xFF374151, dp(1), dp(10)));
                slotButtons[i].setTextColor(sel ? 0xFFFFFFFF : 0xFFD1D5DB);
            }
        }

        switch (activeTab) {
            case 0:
                cardBodyTv.setText(ApiDashboardEngine.buildSummaryAndActionPlan(dash, profile));
                break;
            case 1:
                cardBodyTv.setText(ApiDashboardEngine.buildEquipmentReport(dash, profile, selectedSlotKey));
                break;
            case 2:
                cardBodyTv.setText(ApiDashboardEngine.buildBagLootAndGemsReport(dash, profile));
                break;
            case 3:
                cardBodyTv.setText(ApiDashboardEngine.buildAll20GemsReport(dash, profile));
                break;
            case 4:
                cardBodyTv.setText(ApiDashboardEngine.buildPetsAndTalismansReport(dash, profile));
                break;
            case 5:
                cardBodyTv.setText(ApiDashboardEngine.buildSkillsAuditReport(
                        dash, profile, IdleArcApiClient.getCachedGameConfig()));
                break;
        }
    }

    private String shortModeLabel(DruidProfile.BuildMode m) {
        switch (m) {
            case PUSH: return "Пуш";
            case BOSS: return "Босс";
            case FARM: return "Фарм";
            case LEVELING: return "Кач";
            default: return "";
        }
    }

    private void attachDragAndClick(View handle, final Runnable onClick) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private int initX, initY;
            private float touchX, touchY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = overlayParams.x;
                        initY = overlayParams.y;
                        touchX = e.getRawX();
                        touchY = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - touchX);
                        int dy = (int) (e.getRawY() - touchY);
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        overlayParams.x = initX + dx;
                        overlayParams.y = initY + dy;
                        if (wm != null && rootContainer != null) {
                            wm.updateViewLayout(rootContainer, overlayParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved && onClick != null) onClick.run();
                        return true;
                }
                return false;
            }
        });
    }

    private TextView makeButton(String label, int bg, int fg, float sp) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(fg);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setPadding(dp(8), dp(5), dp(8), dp(5));
        tv.setBackground(roundRect(bg, 0x33FFFFFF, dp(1), dp(12)));
        return tv;
    }

    private TextView makeChip(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f);
        tv.setPadding(dp(9), dp(5), dp(9), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(5);
        tv.setLayoutParams(lp);
        return tv;
    }

    private GradientDrawable roundRect(int fill, int stroke, int strokeW, int radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(radius);
        if (strokeW > 0) gd.setStroke(strokeW, stroke);
        return gd;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        autoSyncEnabled = false;
        if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
        if (wm != null && rootContainer != null) {
            try {
                wm.removeView(rootContainer);
            } catch (Exception ignored) {
            }
            rootContainer = null;
        }
        super.onDestroy();
    }
}
