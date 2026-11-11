package com.idlearc.companion;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Главный экран Druid Helper v3.0.0 (Pure API Edition).
 * Новый помощник, построенный на 100% вокруг официального API игры (https://idlearc.com/api/dashboard)
 * без захвата экрана, без OCR-погрешностей и со встроенным обновлением по воздуху (OTA).
 */
public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 5001;
    private static final int REQ_NOTIF = 5003;

    private TextView apiStatusTv;
    private LinearLayout authFieldsBox;
    private TextView toggleAuthFormBtn;
    private boolean apiBusy = false;

    private TextView otaStatusTv;
    private TextView otaActionBtn;
    private OtaUpdater.ReleaseInfo availableRelease = null;
    private boolean otaBusy = false;

    private TextView dashHeaderTv;
    private TextView dashBodyTv;
    private HorizontalScrollView mainSlotBarScroll;

    private final TextView[] modeButtons = new TextView[DruidProfile.BuildMode.values().length];
    private final TextView[] tabButtons = new TextView[6];
    private final TextView[] slotButtons = new TextView[ApiDashboardEngine.EquippedSlotKey.values().length];

    private int activeTab = 0;
    private ApiDashboardEngine.EquippedSlotKey selectedSlotKey = ApiDashboardEngine.EquippedSlotKey.ALL;
    private DruidProfile profile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        profile = DruidProfile.load(this);
        IdleArcApiClient.ensureCacheLoaded(this, profile);

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }

        buildMainScreen();
        runOtaCheck(true);
        if (IdleArcApiClient.hasSavedCredentials(this)) {
            triggerMainApiSync(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        profile = DruidProfile.load(this);
        IdleArcApiClient.ensureCacheLoaded(this, profile);
        refreshDashboardUi();
        OtaUpdater.resumePendingInstallIfPermitted(this);
    }

    private void buildMainScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0B111B);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(15), dp(18), dp(15), dp(28));

        // Заголовок v3.0 Pure API
        TextView headerTitle = new TextView(this);
        headerTitle.setText("🌿 Druid Helper v" + OtaUpdater.getCurrentVersionName(this) + " · API");
        headerTitle.setTextColor(0xFF34D399);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22.5f);
        root.addView(headerTitle);

        TextView subtitle = new TextView(this);
        subtitle.setText("100% серверная аналитика из idlearc.com/api/dashboard · Без захвата экрана и без погрешностей OCR");
        subtitle.setTextColor(0xFF94A3B8);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.3f);
        subtitle.setPadding(0, dp(3), 0, dp(14));
        root.addView(subtitle);

        // =========================================================================
        // КАРТОЧКА 1: ПОДКЛЮЧЕНИЕ К IDLEARC API + ЗАПУСК ПЛАВАЮЩЕГО ВИДЖЕТА
        // =========================================================================
        LinearLayout apiCard = makeCard(0xFF111C2D, 0xFF38BDF8);
        root.addView(apiCard);

        TextView apiTitle = new TextView(this);
        apiTitle.setText("🌐 Серверная синхронизация и плавающий виджет");
        apiTitle.setTextColor(0xFF7DD3FC);
        apiTitle.setTypeface(Typeface.DEFAULT_BOLD);
        apiTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        apiCard.addView(apiTitle);

        apiStatusTv = new TextView(this);
        apiStatusTv.setTextColor(0xFFE2E8F0);
        apiStatusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        apiStatusTv.setPadding(0, dp(5), 0, dp(8));
        apiCard.addView(apiStatusTv);

        boolean hasCreds = IdleArcApiClient.hasSavedCredentials(this);

        authFieldsBox = new LinearLayout(this);
        authFieldsBox.setOrientation(LinearLayout.VERTICAL);
        authFieldsBox.setVisibility(hasCreds ? View.GONE : View.VISIBLE);

        final EditText emailInput = new EditText(this);
        emailInput.setHint("Email от аккаунта IdleArc");
        emailInput.setHintTextColor(0xFF64748B);
        emailInput.setTextColor(0xFFF8FAFC);
        emailInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.2f);
        emailInput.setSingleLine(true);
        emailInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailInput.setText(IdleArcApiClient.getSavedEmail(this));
        emailInput.setPadding(dp(11), dp(9), dp(11), dp(9));
        emailInput.setBackground(roundRect(0xFF0B1320, 0xFF334155, dp(1), dp(10)));
        authFieldsBox.addView(emailInput);

        final EditText passInput = new EditText(this);
        passInput.setHint(hasCreds ? "Новый пароль (если менялся)" : "Пароль от аккаунта IdleArc");
        passInput.setHintTextColor(0xFF64748B);
        passInput.setTextColor(0xFFF8FAFC);
        passInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.2f);
        passInput.setSingleLine(true);
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passInput.setPadding(dp(11), dp(9), dp(11), dp(9));
        LinearLayout.LayoutParams pwLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pwLp.topMargin = dp(6);
        pwLp.bottomMargin = dp(8);
        passInput.setLayoutParams(pwLp);
        passInput.setBackground(roundRect(0xFF0B1320, 0xFF334155, dp(1), dp(10)));
        authFieldsBox.addView(passInput);

        TextView clearCredsBtn = makeBigButton("🗑️ Удалить сохранённый логин с устройства", 0xFF1E293B, 0xFF94A3B8);
        LinearLayout.LayoutParams clrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clrLp.bottomMargin = dp(8);
        clearCredsBtn.setLayoutParams(clrLp);
        clearCredsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                IdleArcApiClient.clearCredentials(MainActivity.this);
                emailInput.setText("");
                passInput.setText("");
                authFieldsBox.setVisibility(View.VISIBLE);
                toggleAuthFormBtn.setVisibility(View.GONE);
                refreshDashboardUi();
                Toast.makeText(MainActivity.this, "Локальные данные входа удалены", Toast.LENGTH_SHORT).show();
            }
        });
        authFieldsBox.addView(clearCredsBtn);
        apiCard.addView(authFieldsBox);

        toggleAuthFormBtn = makeBigButton("🔑 Сменить аккаунт / настройки входа", 0xFF1E293B, 0xFFCBD5E1);
        LinearLayout.LayoutParams tglLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tglLp.bottomMargin = dp(8);
        toggleAuthFormBtn.setLayoutParams(tglLp);
        toggleAuthFormBtn.setVisibility(hasCreds ? View.VISIBLE : View.GONE);
        toggleAuthFormBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = authFieldsBox.getVisibility() != View.VISIBLE;
                authFieldsBox.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });
        apiCard.addView(toggleAuthFormBtn);

        TextView syncBtn = makeBigButton("🔄 СИНХРОНИЗИРОВАТЬ С СЕРВЕРОМ IDLEARC", 0xFF0284C7, 0xFFFFFFFF);
        syncBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String em = emailInput.getText() != null ? emailInput.getText().toString().trim() : "";
                String pw = passInput.getText() != null ? passInput.getText().toString() : "";
                if (!em.isEmpty() && !pw.isEmpty()) {
                    IdleArcApiClient.saveCredentials(MainActivity.this, em, pw, "");
                    passInput.setText("");
                    authFieldsBox.setVisibility(View.GONE);
                    toggleAuthFormBtn.setVisibility(View.VISIBLE);
                }
                triggerMainApiSync(false);
            }
        });
        apiCard.addView(syncBtn);

        TextView launchOverlayBtn = makeBigButton("🚀 ЗАПУСТИТЬ ПЛАВАЮЩИЙ API-ВИДЖЕТ ПОВЕРХ ИГРЫ", 0xFF059669, 0xFFFFFFFF);
        LinearLayout.LayoutParams loLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        loLp.topMargin = dp(8);
        launchOverlayBtn.setLayoutParams(loLp);
        launchOverlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startApiOverlay();
            }
        });
        apiCard.addView(launchOverlayBtn);

        TextView stopOverlayBtn = makeBigButton("✖ Выключить плавающий виджет", 0xFF3F1D28, 0xFFFCA5A5);
        LinearLayout.LayoutParams soLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        soLp.topMargin = dp(6);
        stopOverlayBtn.setLayoutParams(soLp);
        stopOverlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent stop = new Intent(MainActivity.this, OverlayService.class)
                        .setAction(OverlayService.ACTION_STOP);
                startService(stop);
                Toast.makeText(MainActivity.this, "Плавающий виджет остановлен", Toast.LENGTH_SHORT).show();
            }
        });
        apiCard.addView(stopOverlayBtn);

        // =========================================================================
        // КАРТОЧКА 2: ОБНОВЛЕНИЕ ПО ВОЗДУХУ (OTA)
        // =========================================================================
        LinearLayout otaCard = makeCard(0xFF131D2B, 0xFF60A5FA);
        LinearLayout.LayoutParams otaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        otaLp.topMargin = dp(13);
        otaCard.setLayoutParams(otaLp);

        TextView otaTitle = new TextView(this);
        otaTitle.setText("☁️ Обновление по воздуху (OTA)");
        otaTitle.setTextColor(0xFF93C5FD);
        otaTitle.setTypeface(Typeface.DEFAULT_BOLD);
        otaTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.8f);
        otaCard.addView(otaTitle);

        otaStatusTv = new TextView(this);
        otaStatusTv.setText("Версия v" + OtaUpdater.getCurrentVersionName(this)
                + " (сборка " + OtaUpdater.getCurrentVersionCode(this) + ") · проверка обновлений...");
        otaStatusTv.setTextColor(0xFFCBD5E1);
        otaStatusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        otaStatusTv.setPadding(0, dp(4), 0, dp(8));
        otaCard.addView(otaStatusTv);

        otaActionBtn = makeBigButton("🔄 Проверить обновление", 0xFF1D4ED8, 0xFFFFFFFF);
        otaActionBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (otaBusy) return;
                if (availableRelease != null) {
                    startOtaDownload(availableRelease);
                } else {
                    runOtaCheck(false);
                }
            }
        });
        otaCard.addView(otaActionBtn);
        root.addView(otaCard);

        // =========================================================================
        // КАРТОЧКА 3: ПОЛНЫЙ 6-ВКЛАДОЧНЫЙ ЦЕНТР ПЕРСОНАЖА ИЗ /api/dashboard
        // =========================================================================
        LinearLayout dashCard = makeCard(0xFF111827, 0xFF34D399);
        LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dcLp.topMargin = dp(14);
        dashCard.setLayoutParams(dcLp);

        dashHeaderTv = new TextView(this);
        dashHeaderTv.setText("📊 Аналитический центр Друида (API)");
        dashHeaderTv.setTextColor(0xFF6EE7B7);
        dashHeaderTv.setTypeface(Typeface.DEFAULT_BOLD);
        dashHeaderTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.8f);
        dashCard.addView(dashHeaderTv);

        // Ряд режимов (Пуш / Боссы / Фарм / 1->Кап)
        HorizontalScrollView modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, dp(8), 0, dp(6));
        final DruidProfile.BuildMode[] modes = DruidProfile.BuildMode.values();
        for (int i = 0; i < modes.length; i++) {
            final DruidProfile.BuildMode bm = modes[i];
            TextView mb = makeChip(bm.title);
            mb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    profile.mode = bm;
                    profile.save(MainActivity.this);
                    refreshDashboardUi();
                }
            });
            modeButtons[i] = mb;
            modeRow.addView(mb);
        }
        modeScroll.addView(modeRow);
        dashCard.addView(modeScroll);

        // Ряд 6 вкладок API
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, dp(2), 0, dp(6));
        String[] tabNames = {
            "🎯 Сводка", "⚔️ Шмот (10)", "🎒 Сумка", "💎 Камни (20)", "🐾 Петы/Талисм.", "🌳 Навыки"
        };
        for (int i = 0; i < tabNames.length; i++) {
            final int tIdx = i;
            TextView tb = makeChip(tabNames[i]);
            tb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activeTab = tIdx;
                    refreshDashboardUi();
                }
            });
            tabButtons[i] = tb;
            tabRow.addView(tb);
        }
        tabScroll.addView(tabRow);
        dashCard.addView(tabScroll);

        // Подряд 10 реальных слотов для вкладки «⚔️ Шмот (10)»
        mainSlotBarScroll = new HorizontalScrollView(this);
        mainSlotBarScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout slotRow = new LinearLayout(this);
        slotRow.setOrientation(LinearLayout.HORIZONTAL);
        slotRow.setPadding(0, dp(2), 0, dp(8));
        final ApiDashboardEngine.EquippedSlotKey[] slotKeys = ApiDashboardEngine.EquippedSlotKey.values();
        for (int i = 0; i < slotKeys.length; i++) {
            final ApiDashboardEngine.EquippedSlotKey sk = slotKeys[i];
            TextView sbBtn = makeChip(sk.chipTitle);
            sbBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedSlotKey = sk;
                    refreshDashboardUi();
                }
            });
            slotButtons[i] = sbBtn;
            slotRow.addView(sbBtn);
        }
        mainSlotBarScroll.addView(slotRow);
        mainSlotBarScroll.setVisibility(View.GONE);
        dashCard.addView(mainSlotBarScroll);

        dashBodyTv = new TextView(this);
        dashBodyTv.setTextColor(0xFFF1F5F9);
        dashBodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.4f);
        dashBodyTv.setLineSpacing(dp(2), 1.08f);
        dashBodyTv.setPadding(0, dp(4), 0, dp(4));
        dashCard.addView(dashBodyTv);

        root.addView(dashCard);

        scroll.addView(root);
        setContentView(scroll);
        refreshDashboardUi();
    }

    private void refreshDashboardUi() {
        Map<String, Object> dash = IdleArcApiClient.getCachedDashboard();
        boolean hasCreds = IdleArcApiClient.hasSavedCredentials(this);

        if (apiStatusTv != null && !apiBusy) {
            if (dash != null) {
                long ts = IdleArcApiClient.getLastSyncTimeMs();
                String timeStr = ts > 0 ? new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ts)) : "локальный кэш";
                apiStatusTv.setText("✅ Подключён (" + IdleArcApiClient.getSavedEmail(this)
                        + ") · Обновлено: " + timeStr + " · Ур. " + profile.charLevel + " · GS " + profile.gearScore);
                apiStatusTv.setTextColor(0xFF6EE7B7);
            } else if (hasCreds) {
                apiStatusTv.setText("Аккаунт сохранён (" + IdleArcApiClient.getSavedEmail(this) + ") — нажми кнопку синхронизации.");
                apiStatusTv.setTextColor(0xFF93C5FD);
            } else {
                apiStatusTv.setText("Введи Email и пароль от IdleArc ниже (хранятся только локально на твоём телефоне). За 0.4 сек загружает все твои статы, все 10 надетых вещей, сумку, 20 камней, петов и навыки!");
                apiStatusTv.setTextColor(0xFFCBD5E1);
            }
        }

        DruidProfile.BuildMode[] modes = DruidProfile.BuildMode.values();
        for (int i = 0; i < modes.length; i++) {
            if (modeButtons[i] != null) {
                boolean sel = (profile.mode == modes[i]);
                modeButtons[i].setBackground(roundRect(sel ? 0xFF065F46 : 0xFF1E293B,
                        sel ? 0xFF34D399 : 0xFF334155, dp(1), dp(10)));
                modeButtons[i].setTextColor(sel ? 0xFFECFDF5 : 0xFFCBD5E1);
            }
        }

        for (int i = 0; i < tabButtons.length; i++) {
            if (tabButtons[i] != null) {
                boolean sel = (activeTab == i);
                tabButtons[i].setBackground(roundRect(sel ? 0xFF0284C7 : 0xFF1E293B,
                        sel ? 0xFF7DD3FC : 0xFF334155, dp(1), dp(10)));
                tabButtons[i].setTextColor(sel ? 0xFFFFFFFF : 0xFFCBD5E1);
            }
        }

        if (mainSlotBarScroll != null) {
            mainSlotBarScroll.setVisibility(activeTab == 1 ? View.VISIBLE : View.GONE);
            if (activeTab == 1) {
                ApiDashboardEngine.EquippedSlotKey[] keys = ApiDashboardEngine.EquippedSlotKey.values();
                for (int i = 0; i < keys.length; i++) {
                    if (slotButtons[i] != null) {
                        boolean sel = (selectedSlotKey == keys[i]);
                        slotButtons[i].setBackground(roundRect(sel ? 0xFF5B21B6 : 0xFF1E293B,
                                sel ? 0xFFC4B5FD : 0xFF334155, dp(1), dp(10)));
                        slotButtons[i].setTextColor(sel ? 0xFFFFFFFF : 0xFFCBD5E1);
                    }
                }
            }
        }

        if (dashBodyTv != null) {
            switch (activeTab) {
                case 0:
                    dashBodyTv.setText(ApiDashboardEngine.buildSummaryAndActionPlan(dash, profile));
                    break;
                case 1:
                    dashBodyTv.setText(ApiDashboardEngine.buildEquipmentReport(dash, profile, selectedSlotKey));
                    break;
                case 2:
                    dashBodyTv.setText(ApiDashboardEngine.buildBagLootAndGemsReport(dash, profile));
                    break;
                case 3:
                    dashBodyTv.setText(ApiDashboardEngine.buildAll20GemsReport(dash, profile));
                    break;
                case 4:
                    dashBodyTv.setText(ApiDashboardEngine.buildPetsAndTalismansReport(dash, profile));
                    break;
                case 5:
                    dashBodyTv.setText(ApiDashboardEngine.buildSkillsAuditReport(
                            dash, profile, IdleArcApiClient.getCachedGameConfig()));
                    break;
            }
        }
    }

    private void triggerMainApiSync(final boolean silentStartup) {
        if (apiBusy) return;
        if (!IdleArcApiClient.hasSavedCredentials(this)) {
            if (!silentStartup) {
                Toast.makeText(this, "Сначала введи Email и пароль от IdleArc!", Toast.LENGTH_LONG).show();
            }
            return;
        }
        apiBusy = true;
        if (apiStatusTv != null) {
            apiStatusTv.setText("⏳ Загрузка живого состояния героя с https://idlearc.com/api/dashboard...");
            apiStatusTv.setTextColor(0xFF93C5FD);
        }
        IdleArcApiClient.syncAsync(this, profile, new IdleArcApiClient.ApiCallback() {
            @Override
            public void onSuccess(final ScreenAnalyzer.ScanResult scanResult) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        apiBusy = false;
                        refreshDashboardUi();
                        if (!silentStartup) {
                            Toast.makeText(MainActivity.this,
                                    "✅ Герой синхронизирован (GS " + profile.gearScore + ")!", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }

            @Override
            public void onError(final String errorMessage) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        apiBusy = false;
                        if (apiStatusTv != null) {
                            apiStatusTv.setText("❌ " + errorMessage);
                            apiStatusTv.setTextColor(0xFFFCA5A5);
                        }
                    }
                });
            }
        });
    }

    private void startApiOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разреши «Поверх других окон» для Druid Helper и нажми ещё раз", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            return;
        }
        Intent svc = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        Toast.makeText(this, "🌿 API-виджет запущен! Открывай IdleArc", Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY && Settings.canDrawOverlays(this)) {
            startApiOverlay();
        }
    }

    private void runOtaCheck(final boolean silentStartup) {
        if (otaBusy) return;
        otaBusy = true;
        if (!silentStartup && otaStatusTv != null) {
            otaStatusTv.setText("⏳ Проверка обновлений...");
            otaStatusTv.setTextColor(0xFF93C5FD);
        }
        OtaUpdater.checkForUpdatesAsync(this, new OtaUpdater.CheckCallback() {
            @Override
            public void onUpdateAvailable(OtaUpdater.ReleaseInfo latest, int currentCode, String currentName) {
                otaBusy = false;
                availableRelease = latest;
                if (otaStatusTv != null) {
                    String log = latest.changelog.isEmpty() ? "" : ("\nЧто нового: " + latest.changelog);
                    otaStatusTv.setText("🔥 Доступна новая версия v" + latest.versionName
                            + " (у тебя v" + currentName + ")!" + log);
                    otaStatusTv.setTextColor(0xFF6EE7B7);
                }
                if (otaActionBtn != null) {
                    otaActionBtn.setText("⬇️ СКАЧАТЬ И УСТАНОВИТЬ v" + latest.versionName);
                    otaActionBtn.setBackground(roundRect(0xFF059669, 0, 0, dp(12)));
                }
            }

            @Override
            public void onUpToDate(int currentCode, String currentName) {
                otaBusy = false;
                availableRelease = null;
                if (otaStatusTv != null) {
                    otaStatusTv.setText("✅ Установлена актуальная версия v" + currentName + " (сборка " + currentCode + ")");
                    otaStatusTv.setTextColor(0xFFA7F3D0);
                }
                if (otaActionBtn != null) {
                    otaActionBtn.setText("🔄 Проверить обновление ещё раз");
                }
            }

            @Override
            public void onError(String message) {
                otaBusy = false;
                if (otaStatusTv != null) {
                    otaStatusTv.setText("Версия v" + OtaUpdater.getCurrentVersionName(MainActivity.this)
                            + (silentStartup ? "" : (" · " + message)));
                    otaStatusTv.setTextColor(silentStartup ? 0xFF94A3B8 : 0xFFFCA5A5);
                }
            }
        });
    }

    private void startOtaDownload(final OtaUpdater.ReleaseInfo rel) {
        if (otaBusy) return;
        otaBusy = true;
        if (otaActionBtn != null) {
            otaActionBtn.setText("⏳ Скачивание v" + rel.versionName + "...");
        }
        OtaUpdater.downloadUpdateAsync(this, rel, new OtaUpdater.DownloadCallback() {
            @Override
            public void onProgress(int percent, long downloadedBytes, long totalBytes) {
                if (otaStatusTv != null) {
                    String pStr = percent >= 0 ? (percent + "%") : ((downloadedBytes / 1024) + " КБ");
                    otaStatusTv.setText("⬇️ Загрузка обновления v" + rel.versionName + ": " + pStr);
                    otaStatusTv.setTextColor(0xFF93C5FD);
                }
            }

            @Override
            public void onReadyToInstall(File apkFile) {
                otaBusy = false;
                if (otaStatusTv != null) {
                    otaStatusTv.setText("✅ Файл v" + rel.versionName + " проверен! Подтверди установку.");
                    otaStatusTv.setTextColor(0xFF6EE7B7);
                }
                if (otaActionBtn != null) {
                    otaActionBtn.setText("📲 Установить v" + rel.versionName);
                }
            }

            @Override
            public void onError(String message) {
                otaBusy = false;
                availableRelease = null;
                if (otaStatusTv != null) {
                    otaStatusTv.setText("❌ Ошибка обновления: " + message);
                    otaStatusTv.setTextColor(0xFFFCA5A5);
                }
                if (otaActionBtn != null) {
                    otaActionBtn.setText("🔄 Повторить проверку и скачивание");
                }
            }
        });
    }

    private LinearLayout makeCard(int bg, int stroke) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(roundRect(bg, stroke, dp(1), dp(16)));
        return card;
    }

    private TextView makeBigButton(String text, int bg, int fg) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(fg);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.2f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(11), dp(12), dp(11));
        tv.setBackground(roundRect(bg, 0, 0, dp(12)));
        return tv;
    }

    private TextView makeChip(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
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
}
