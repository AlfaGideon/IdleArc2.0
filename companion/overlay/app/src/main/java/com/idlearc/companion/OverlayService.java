package com.idlearc.companion;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Плавающий виджет-помощник поверх IdleArc (аналог сканера заказов, но для прокачки и шмота Друида).
 * Поддерживает:
 *  - компактную плавающую таблетку «🌿 ДРУИД | 🔍 СКАН | ⚡»;
 *  - раскрытую карточку с вердиктом, режимами (Пуш / Босс / Фарм / 1->Кап), аудитом и шпаргалкой;
 *  - разовый скан и авто-отслеживание новых окон (MediaProjection + офлайн OCR без кликов по игре).
 */
public class OverlayService extends Service {

    public static final String EXTRA_RESULT_CODE = "mp_result_code";
    public static final String EXTRA_RESULT_DATA = "mp_result_data";
    public static final String ACTION_STOP = "com.idlearc.companion.STOP";

    private static final String CHANNEL_ID = "druid_overlay_channel";
    private static final int NOTIF_ID = 7324;

    private WindowManager wm;
    private WindowManager.LayoutParams overlayParams;
    private LinearLayout rootContainer;
    private LinearLayout collapsedBar;
    private LinearLayout expandedCard;

    private TextView pillStatusTv;
    private TextView pillAutoBtn;
    private TextView headerTitleTv;
    private TextView cardHeadlineTv;
    private TextView cardSubTypeTv;
    private TextView cardBodyTv;
    private TextView autoToggleBtn;
    private final TextView[] modeButtons = new TextView[4];
    private final TextView[] tabButtons = new TextView[5];
    private HorizontalScrollView slotPickerScroll;
    private final TextView[] slotButtons = new TextView[SlotAffixAdvisor.GearSlot.values().length];
    private SlotAffixAdvisor.GearSlot selectedSlot = SlotAffixAdvisor.GearSlot.WEAPON;

    private boolean isExpanded = false;
    private boolean autoScanEnabled = false;
    private int activeTab = 0; // 0=Скан, 1=Слоты и ★, 2=Аудит статов, 3=Надписи/Петы, 4=Гайд 1->Кап
    private boolean scanInProgress = false;
    private long lastFrameSignature = 0L;

    private DruidProfile profile;
    private IdleArcOcrEngine ocrEngine;
    private ScreenAnalyzer.ScanResult lastScanResult;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int captureW = 0;
    private int captureH = 0;
    private int captureDensity = 0;

    private Handler mainHandler;
    private HandlerThread workerThread;
    private Handler workerHandler;

    private final Runnable autoScanRunnable = new Runnable() {
        @Override
        public void run() {
            if (!autoScanEnabled) return;
            if (mediaProjection != null && !scanInProgress && activeTab == 0) {
                triggerScreenScan(true);
            }
            mainHandler.postDelayed(this, 1900L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        workerThread = new HandlerThread("DruidOcrWorker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        profile = DruidProfile.load(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Загружаем офлайн OCR-шаблоны в фоне
        workerHandler.post(() -> {
            try (InputStream in = getAssets().open("ocr_glyphs.bin")) {
                ocrEngine = IdleArcOcrEngine.loadFromStream(in);
            } catch (Exception e) {
                ocrEngine = null;
            }
        });

        buildOverlayUi();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && intent.hasExtra(EXTRA_RESULT_CODE) && intent.hasExtra(EXTRA_RESULT_DATA)) {
            int resCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent resData = getParcelableExtraCompat(intent, EXTRA_RESULT_DATA);
            if (resCode == Activity.RESULT_OK && resData != null) {
                startForegroundWithProjectionNotification();
                initMediaProjection(resCode, resData);
            }
        }

        profile = DruidProfile.load(this);
        refreshUiContent();
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private static Intent getParcelableExtraCompat(Intent intent, String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(key, Intent.class);
        }
        return intent.getParcelableExtra(key);
    }

    private void startForegroundWithProjectionNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Druid Helper Overlay", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Плавающий помощник Друида поверх IdleArc");
            nm.createNotificationChannel(ch);
        }

        Intent stopIntent = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags);

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, openIntent, piFlags);

        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        nb.setSmallIcon(android.R.drawable.ic_menu_compass)
          .setContentTitle("🌿 Druid Helper активен")
          .setContentText("Нажми СКАН в плавающем виджете поверх IdleArc")
          .setContentIntent(openPi)
          .addAction(new Notification.Action.Builder(
                  android.R.drawable.ic_menu_close_clear_cancel, "Выключить", stopPi).build())
          .setOngoing(true);

        Notification notif = nb.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }

    private void initMediaProjection(int resultCode, Intent resultData) {
        stopProjection();
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) return;
            mediaProjection = mpm.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) return;

            // ОБЯЗАТЕЛЬНО для Android 14+ (API 34+): регистрируем Callback ДО createVirtualDisplay!
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    mainHandler.post(() -> {
                        autoScanEnabled = false;
                        stopProjection();
                        refreshUiContent();
                    });
                }
            }, mainHandler);

            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            // Для скорости и точности держим ширину около 1080px
            captureW = dm.widthPixels;
            captureH = dm.heightPixels;
            captureDensity = dm.densityDpi;
            if (captureW > 1200) {
                float scale = 1080f / captureW;
                captureW = 1080;
                captureH = Math.max(800, Math.round(dm.heightPixels * scale));
            }

            imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2);
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "IdleArcDruidScanner",
                    captureW, captureH, captureDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null, workerHandler);

            Toast.makeText(this, "🌿 Сканер экрана IdleArc готов!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка инициализации захвата: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopProjection() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }
    }

    private void buildOverlayUi() {
        rootContainer = new LinearLayout(this);
        rootContainer.setOrientation(LinearLayout.VERTICAL);

        // --- 1. Свёрнутая плавающая таблетка (как в сканере заказов) ---
        collapsedBar = new LinearLayout(this);
        collapsedBar.setOrientation(LinearLayout.HORIZONTAL);
        collapsedBar.setGravity(Gravity.CENTER_VERTICAL);
        collapsedBar.setPadding(dp(10), dp(6), dp(10), dp(6));
        collapsedBar.setBackground(roundRect(0xEB171522, 0xFF34D399, dp(2), dp(22)));

        pillStatusTv = new TextView(this);
        pillStatusTv.setText("🌿 ДРУИД");
        pillStatusTv.setTextColor(0xFFECFDF5);
        pillStatusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        pillStatusTv.setTypeface(Typeface.DEFAULT_BOLD);
        pillStatusTv.setPadding(dp(4), dp(2), dp(8), dp(2));

        TextView pillScanBtn = makeButton("🔍 СКАН", 0xFF059669, 0xFFFFFFFF, 12f);
        pillScanBtn.setOnClickListener(v -> triggerScreenScan(false));

        pillAutoBtn = makeButton("⚡", 0xFF2B283A, 0xFFFBBF24, 12f);
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        autoLp.leftMargin = dp(5);
        pillAutoBtn.setLayoutParams(autoLp);
        pillAutoBtn.setOnClickListener(v -> toggleAutoScan());

        collapsedBar.addView(pillStatusTv);
        collapsedBar.addView(pillScanBtn);
        collapsedBar.addView(pillAutoBtn);

        // Перетаскивание плавающей таблетки пальцем + клик для раскрытия
        attachDragAndClick(pillStatusTv, () -> setExpanded(!isExpanded));
        attachDragOnly(collapsedBar);

        // --- 2. Раскрытая карточка советника Друида ---
        expandedCard = new LinearLayout(this);
        expandedCard.setOrientation(LinearLayout.VERTICAL);
        expandedCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        expandedCard.setBackground(roundRect(0xF5181624, 0xFF34D399, dp(2), dp(16)));
        expandedCard.setVisibility(View.GONE);

        // Шапка карточки (перетаскиваемая)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        headerTitleTv = new TextView(this);
        headerTitleTv.setText("🌿 Druid Helper v2.0");
        headerTitleTv.setTextColor(0xFF34D399);
        headerTitleTv.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        headerTitleTv.setLayoutParams(titleLp);
        attachDragOnly(header);
        attachDragOnly(headerTitleTv);

        TextView scanNowBtn = makeButton("🔍 СКАН", 0xFF059669, 0xFFFFFFFF, 11.5f);
        scanNowBtn.setOnClickListener(v -> {
            activeTab = 0;
            triggerScreenScan(false);
        });

        autoToggleBtn = makeButton("⚡ АВТО: ВЫКЛ", 0xFF2B283A, 0xFFE5E7EB, 11f);
        LinearLayout.LayoutParams atLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        atLp.leftMargin = dp(5);
        autoToggleBtn.setLayoutParams(atLp);
        autoToggleBtn.setOnClickListener(v -> toggleAutoScan());

        TextView minBtn = makeButton("➖", 0xFF2B283A, 0xFFFBBF24, 11.5f);
        LinearLayout.LayoutParams minLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        minLp.leftMargin = dp(5);
        minBtn.setLayoutParams(minLp);
        minBtn.setOnClickListener(v -> setExpanded(false));

        TextView closeBtn = makeButton("✖", 0xFF3F1D28, 0xFFFCA5A5, 11.5f);
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clLp.leftMargin = dp(4);
        closeBtn.setLayoutParams(clLp);
        closeBtn.setOnClickListener(v -> stopSelf());

        header.addView(headerTitleTv);
        header.addView(scanNowBtn);
        header.addView(autoToggleBtn);
        header.addView(minBtn);
        header.addView(closeBtn);
        expandedCard.addView(header);

        // Переключатель режима билда (Пуш / Босс / Фарм / 1->Кап)
        HorizontalScrollView modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, dp(7), 0, dp(4));
        DruidProfile.BuildMode[] modes = DruidProfile.BuildMode.values();
        for (int i = 0; i < modes.length; i++) {
            final DruidProfile.BuildMode bm = modes[i];
            TextView b = makeButton(bm.title, 0xFF252236, 0xFFD1D5DB, 11f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(5);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                profile.mode = bm;
                profile.save(OverlayService.this);
                if (lastScanResult != null && lastScanResult.isAffixItemScan) {
                    ScreenAnalyzer.recomputeAffixScan(lastScanResult, lastScanResult.detectedSlot, profile);
                }
                refreshUiContent();
            });
            modeButtons[i] = b;
            modeRow.addView(b);
        }
        modeScroll.addView(modeRow);
        expandedCard.addView(modeScroll);

        // Переключатель вкладок внутри оверлея
        String[] tabNames = {"🎯 Скан", "🛠️ Слоты & ★", "📊 Статы", "✒️ Надписи", "🗺️ 1→Кап"};
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, dp(2), 0, dp(6));
        for (int i = 0; i < tabNames.length; i++) {
            final int tabIdx = i;
            TextView tb = makeButton(tabNames[i], 0xFF201D2E, 0xFF9CA3AF, 10.5f);
            tb.setPadding(dp(4), dp(5), dp(4), dp(5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(3);
            tb.setGravity(Gravity.CENTER);
            tb.setLayoutParams(lp);
            tb.setOnClickListener(v -> {
                activeTab = tabIdx;
                refreshUiContent();
            });
            tabButtons[i] = tb;
            tabRow.addView(tb);
        }
        expandedCard.addView(tabRow);

        // Полоса выбора конкретного вида предмета (11 слотов) — видна на вкладке «🛠️ Слоты & ★»
        slotPickerScroll = new HorizontalScrollView(this);
        slotPickerScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout slotRow = new LinearLayout(this);
        slotRow.setOrientation(LinearLayout.HORIZONTAL);
        slotRow.setPadding(0, 0, 0, dp(6));
        SlotAffixAdvisor.GearSlot[] allSlots = SlotAffixAdvisor.GearSlot.values();
        for (int i = 0; i < allSlots.length; i++) {
            final SlotAffixAdvisor.GearSlot gs = allSlots[i];
            TextView sb = makeButton(gs.shortChip, 0xFF252236, 0xFFD1D5DB, 11f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) slp.leftMargin = dp(5);
            sb.setLayoutParams(slp);
            sb.setOnClickListener(v -> {
                selectedSlot = gs;
                if (activeTab == 0 && lastScanResult != null && lastScanResult.isAffixItemScan
                        && gs != SlotAffixAdvisor.GearSlot.TALISMAN && gs != SlotAffixAdvisor.GearSlot.PET) {
                    ScreenAnalyzer.recomputeAffixScan(lastScanResult, gs, profile);
                }
                refreshUiContent();
            });
            slotButtons[i] = sb;
            slotRow.addView(sb);
        }
        slotPickerScroll.addView(slotRow);
        slotPickerScroll.setVisibility(View.GONE);
        expandedCard.addView(slotPickerScroll);

        // Плашка вердикта
        cardSubTypeTv = new TextView(this);
        cardSubTypeTv.setTextColor(0xFF9CA3AF);
        cardSubTypeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        expandedCard.addView(cardSubTypeTv);

        cardHeadlineTv = new TextView(this);
        cardHeadlineTv.setTextColor(0xFFECFDF5);
        cardHeadlineTv.setTypeface(Typeface.DEFAULT_BOLD);
        cardHeadlineTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        cardHeadlineTv.setPadding(dp(8), dp(6), dp(8), dp(6));
        expandedCard.addView(cardHeadlineTv);

        // Прокручиваемый текст советов
        ScrollView bodyScroll = new ScrollView(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int maxCardHeight = Math.min(dp(290), (int) (dm.heightPixels * 0.38f));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxCardHeight);
        scrollLp.topMargin = dp(6);
        bodyScroll.setLayoutParams(scrollLp);

        cardBodyTv = new TextView(this);
        cardBodyTv.setTextColor(0xFFE5E7EB);
        cardBodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        cardBodyTv.setLineSpacing(dp(2), 1.05f);
        bodyScroll.addView(cardBodyTv);
        expandedCard.addView(bodyScroll);

        rootContainer.addView(collapsedBar);
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
        overlayParams.x = dp(12);
        overlayParams.y = dp(110);

        wm.addView(rootContainer, overlayParams);
        refreshUiContent();
    }

    private void setExpanded(boolean exp) {
        isExpanded = exp;
        collapsedBar.setVisibility(exp ? View.GONE : View.VISIBLE);
        expandedCard.setVisibility(exp ? View.VISIBLE : View.GONE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        overlayParams.width = exp ? Math.min(dp(370), (int) (dm.widthPixels * 0.94f))
                                  : WindowManager.LayoutParams.WRAP_CONTENT;
        try {
            wm.updateViewLayout(rootContainer, overlayParams);
        } catch (Exception ignored) {
        }
        refreshUiContent();
    }

    private void toggleAutoScan() {
        if (mediaProjection == null) {
            Toast.makeText(this, "Открой Druid Helper и включи «Захват экрана» для сканера!", Toast.LENGTH_LONG).show();
            Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        }
        autoScanEnabled = !autoScanEnabled;
        mainHandler.removeCallbacks(autoScanRunnable);
        if (autoScanEnabled) {
            mainHandler.postDelayed(autoScanRunnable, 600L);
        }
        refreshUiContent();
    }

    private void triggerScreenScan(final boolean silentAuto) {
        if (scanInProgress) return;
        if (mediaProjection == null || imageReader == null) {
            if (!silentAuto) {
                Toast.makeText(this, "Сначала разреши захват экрана в приложении Druid Helper", Toast.LENGTH_LONG).show();
                Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
            return;
        }
        if (ocrEngine == null) {
            if (!silentAuto) Toast.makeText(this, "Загрузка OCR-базы, подожди 1 сек...", Toast.LENGTH_SHORT).show();
            return;
        }

        scanInProgress = true;
        if (!silentAuto) {
            pillStatusTv.setText("⏳ Скан...");
        }

        // Прячем оверлей на 85 мс, чтобы он не загораживал текст игры на снимке
        rootContainer.setAlpha(0.0f);
        mainHandler.postDelayed(() -> workerHandler.post(() -> {
            int[] argb = null;
            int w = 0, h = 0;
            try (Image img = imageReader != null ? imageReader.acquireLatestImage() : null) {
                if (img != null) {
                    w = img.getWidth();
                    h = img.getHeight();
                    Image.Plane plane = img.getPlanes()[0];
                    ByteBuffer buf = plane.getBuffer();
                    int pixelStride = plane.getPixelStride();
                    int rowStride = plane.getRowStride();
                    argb = new int[w * h];
                    byte[] rowBytes = new byte[rowStride];
                    for (int y = 0; y < h; y++) {
                        buf.position(y * rowStride);
                        int toRead = Math.min(rowStride, buf.remaining());
                        buf.get(rowBytes, 0, toRead);
                        int outOff = y * w;
                        for (int x = 0; x < w; x++) {
                            int bi = x * pixelStride;
                            int r = rowBytes[bi] & 0xFF;
                            int g = rowBytes[bi + 1] & 0xFF;
                            int b = rowBytes[bi + 2] & 0xFF;
                            argb[outOff + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            // Мгновенно возвращаем видимость виджета
            mainHandler.post(() -> rootContainer.setAlpha(1.0f));

            if (argb == null || w == 0 || h == 0) {
                mainHandler.post(() -> {
                    scanInProgress = false;
                    if (!silentAuto) {
                        pillStatusTv.setText("🌿 Повтори скан");
                        Toast.makeText(OverlayService.this, "Кадр ещё обновляется — нажми СКАН ещё раз", Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }

            // В авто-режиме пропускаем OCR, если картинка в центре экрана не менялась
            long sig = computeCenterHash(argb, w, h);
            if (silentAuto && sig == lastFrameSignature && lastScanResult != null) {
                mainHandler.post(() -> scanInProgress = false);
                return;
            }
            lastFrameSignature = sig;

            final ScreenAnalyzer.ScanResult result = ScreenAnalyzer.analyzeFrame(ocrEngine, argb, w, h, profile);
            if (result.profileUpdated) {
                profile.save(OverlayService.this);
            }

            mainHandler.post(() -> {
                lastScanResult = result;
                if (result.detectedSlot != null) {
                    selectedSlot = result.detectedSlot;
                }
                scanInProgress = false;
                if (!silentAuto) {
                    activeTab = 0;
                    setExpanded(true);
                } else {
                    refreshUiContent();
                }
            });
        }), 90L);
    }

    private static long computeCenterHash(int[] argb, int w, int h) {
        long hash = 1125899906842597L;
        int stepY = Math.max(8, h / 40);
        int stepX = Math.max(8, w / 30);
        for (int y = h / 6; y < (h * 5) / 6; y += stepY) {
            int off = y * w;
            for (int x = w / 8; x < (w * 7) / 8; x += stepX) {
                // Квантуем младшие биты шума
                int px = (argb[off + x] >> 4) & 0x0F0F0F;
                hash = 31L * hash + px;
            }
        }
        return hash;
    }

    private void refreshUiContent() {
        if (rootContainer == null) return;

        if (headerTitleTv != null) {
            headerTitleTv.setText(profile.hasScannedStats
                    ? ("🌿 Druid Helper v2.0 · Скан: " + profile.scannedStatsCount + " стат.")
                    : "🌿 Druid Helper v2.0 · Live OCR");
        }

        pillAutoBtn.setText(autoScanEnabled ? "⚡ВКЛ" : "⚡");
        pillAutoBtn.setBackground(roundRect(autoScanEnabled ? 0xFF065F46 : 0xFF2B283A,
                autoScanEnabled ? 0xFF34D399 : 0xFF4B5563, dp(1), dp(14)));

        autoToggleBtn.setText(autoScanEnabled ? "⚡ АВТО: ВКЛ" : "⚡ АВТО: ВЫКЛ");
        autoToggleBtn.setBackground(roundRect(autoScanEnabled ? 0xFF065F46 : 0xFF2B283A,
                autoScanEnabled ? 0xFF34D399 : 0xFF4B5563, dp(1), dp(12)));

        DruidProfile.BuildMode[] modes = DruidProfile.BuildMode.values();
        for (int i = 0; i < modes.length; i++) {
            boolean sel = (profile.mode == modes[i]);
            modeButtons[i].setBackground(roundRect(sel ? 0xFF065F46 : 0xFF252236,
                    sel ? 0xFF34D399 : 0xFF3F3B54, dp(1), dp(12)));
            modeButtons[i].setTextColor(sel ? 0xFFECFDF5 : 0xFFD1D5DB);
        }

        for (int i = 0; i < tabButtons.length; i++) {
            boolean sel = (activeTab == i);
            tabButtons[i].setBackground(roundRect(sel ? 0xFF3B2F63 : 0xFF201D2E,
                    sel ? 0xFFA78BFA : 0xFF343049, dp(1), dp(10)));
            tabButtons[i].setTextColor(sel ? 0xFFFFFFFF : 0xFF9CA3AF);
        }

        if (slotPickerScroll != null) {
            boolean showSlots = (activeTab == 1)
                    || (activeTab == 0 && lastScanResult != null && lastScanResult.isAffixItemScan);
            slotPickerScroll.setVisibility(showSlots ? View.VISIBLE : View.GONE);
            SlotAffixAdvisor.GearSlot[] allSlots = SlotAffixAdvisor.GearSlot.values();
            for (int i = 0; i < allSlots.length; i++) {
                boolean sel = (selectedSlot == allSlots[i]);
                slotButtons[i].setBackground(roundRect(sel ? 0xFF5B21B6 : 0xFF252236,
                        sel ? 0xFFC4B5FD : 0xFF3F3B54, dp(1), dp(10)));
                slotButtons[i].setTextColor(sel ? 0xFFFFFFFF : 0xFFD1D5DB);
            }
        }

        if (lastScanResult != null) {
            pillStatusTv.setText(lastScanResult.shortBadge);
        } else {
            pillStatusTv.setText(mediaProjection != null ? "🌿 ДРУИД · Готов" : "🌿 ДРУИД · Гайд");
        }

        if (activeTab == 0) {
            if (lastScanResult != null) {
                cardSubTypeTv.setText("Экран: " + lastScanResult.screenType + "  |  Режим: " + profile.mode.title);
                cardHeadlineTv.setText(lastScanResult.headline);
                int bg = lastScanResult.color == ScreenAnalyzer.VerdictColor.GOOD ? 0xFF064E3B
                       : lastScanResult.color == ScreenAnalyzer.VerdictColor.BAD ? 0xFF5C1924 : 0xFF4A3512;
                int stroke = lastScanResult.color == ScreenAnalyzer.VerdictColor.GOOD ? 0xFF34D399
                           : lastScanResult.color == ScreenAnalyzer.VerdictColor.BAD ? 0xFFF87171 : 0xFFFBBF24;
                cardHeadlineTv.setBackground(roundRect(bg, stroke, dp(1), dp(10)));
                cardBodyTv.setText(lastScanResult.details);
            } else {
                cardSubTypeTv.setText("Режим: " + profile.mode.title + " — " + profile.mode.subtitle);
                cardHeadlineTv.setText(mediaProjection != null
                        ? "🔍 Открой предмет/кузницу/навык и жми СКАН (или вкладку «Слоты & ★»)"
                        : "⚠️ Режим шпаргалки (для OCR включи Захват экрана)");
                cardHeadlineTv.setBackground(roundRect(0xFF1F2937, 0xFF34D399, dp(1), dp(10)));
                cardBodyTv.setText(DruidKnowledgeBase.buildAuditReport(profile));
            }
        } else if (activeTab == 1) {
            cardSubTypeTv.setText("Выбери слот выше · Режим: " + profile.mode.title + " (разные пулы аффиксов!)");
            cardHeadlineTv.setText("🛠️ " + selectedSlot.shortChip + " — " + selectedSlot.awakenHeadline);
            cardHeadlineTv.setBackground(roundRect(0xFF3B1F5E, 0xFFC084FC, dp(1), dp(10)));
            cardBodyTv.setText(SlotAffixAdvisor.formatSlotGuide(selectedSlot, profile));
        } else if (activeTab == 2) {
            cardSubTypeTv.setText("Живой профиль героя (обновляется кнопкой СКАН в «Характеристиках»)");
            cardHeadlineTv.setText("📊 Динамический математический разбор статов и капов");
            cardHeadlineTv.setBackground(roundRect(0xFF064E3B, 0xFF34D399, dp(1), dp(10)));
            cardBodyTv.setText(DruidKnowledgeBase.buildAuditReport(profile));
        } else if (activeTab == 3) {
            cardSubTypeTv.setText("Расчёт ценности Автонадписей и механика отряда из 4 питомцев");
            cardHeadlineTv.setText("✒️ Живой приоритет Автонадписей и Петов");
            cardHeadlineTv.setBackground(roundRect(0xFF3B2F63, 0xFFA78BFA, dp(1), dp(10)));
            cardBodyTv.setText(DruidKnowledgeBase.buildInscriptionsAndPetsGuide(profile));
        } else {
            cardSubTypeTv.setText("Справочник механик прогрессии класса Друид");
            cardHeadlineTv.setText("🗺️ Прогрессия Друида по этапам игры");
            cardHeadlineTv.setBackground(roundRect(0xFF4A3512, 0xFFFBBF24, dp(1), dp(10)));
            cardBodyTv.setText(DruidKnowledgeBase.buildFullLevelingGuide());
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
                        try { wm.updateViewLayout(rootContainer, overlayParams); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved && onClick != null) onClick.run();
                        return true;
                }
                return false;
            }
        });
    }

    private void attachDragOnly(View handle) {
        attachDragAndClick(handle, null);
    }

    private TextView makeButton(String label, int bgColor, int textColor, float sp) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(9), dp(5), dp(9), dp(5));
        tv.setBackground(roundRect(bgColor, 0xFF4B5563, dp(1), dp(13)));
        return tv;
    }

    private GradientDrawable roundRect(int fillColor, int strokeColor, int strokePx, float radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(radiusPx);
        gd.setStroke(strokePx, strokeColor);
        return gd;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        autoScanEnabled = false;
        mainHandler.removeCallbacksAndMessages(null);
        stopProjection();
        if (rootContainer != null && wm != null) {
            try { wm.removeView(rootContainer); } catch (Exception ignored) {}
            rootContainer = null;
        }
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
