package com.idlearc.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;

/**
 * Главный экран IdleArcHelper v4.0 (Все 5 классов + Полная Сумка по бонусу дропа + Советник аффиксов петов):
 * 7 интерактивных вкладок карточек без сырых JSON/GSON-ключей и без захвата экрана.
 */
public class MainActivity extends Activity {

    private DruidProfile profile;
    private TextView syncStatusTv;
    private Button syncNowBtn;
    private Button liveBtn;
    private boolean liveEnabled;
    private boolean activityResumed;
    private long lastInteractionMs;
    private String liveStatus = "";
    private ScrollView rootScrollView;
    private final LiveSyncManager.Listener liveListener = new LiveSyncManager.Listener() {
        @Override public void onLiveData() {
            if (!activityResumed || !liveEnabled) return;
            IdleArcApiClient.applyDashboardToProfile(IdleArcApiClient.getCachedDashboard(), profile);
            if (android.os.SystemClock.uptimeMillis() - lastInteractionMs < 2000L) return;
            final int y = rootScrollView != null ? rootScrollView.getScrollY() : 0;
            refreshAllViews();
            if (rootScrollView != null) rootScrollView.post(new Runnable() {
                @Override public void run() { rootScrollView.scrollTo(0, y); }
            });
        }
        @Override public void onLiveStatus(String status) {
            liveStatus = status;
            if (activityResumed && liveEnabled) updateLiveStatusText();
        }
    };
    private Button overlayBtn;
    private Button classPickerBtn;
    private LinearLayout cardsContainer;
    private HorizontalScrollView slotChipScroll;
    private final Button[] modeButtons = new Button[DruidProfile.BuildMode.values().length];
    private final Button[] tabButtons = new Button[7];
    private Button[] slotChipButtons;

    private int activeTab = 0;
    private ApiDashboardEngine.EquippedSlotKey selectedEquipSlot = ApiDashboardEngine.EquippedSlotKey.ALL;

    private static final String[] TAB_TITLES = {
        "🔔 Радар",
        "📊 Герой",
        "🛡️ Шмот (10)",
        "🎒 Сумка и ROI",
        "💎 Камни (20)",
        "🐾 Петы и Тал.",
        "🌳 Навыки и Мир"
    };

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        lastInteractionMs = android.os.SystemClock.uptimeMillis();
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        profile = DruidProfile.load(this);

        ScrollView rootScroll = new ScrollView(this);
        rootScrollView = rootScroll;
        rootScroll.setBackgroundColor(Color.parseColor("#080E14"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, dp(10), pad, dp(22));
        rootScroll.addView(root);

        // 1. Фирменная шапка IdleArcHelper v4.0 с медальоном 5 классов (5-конечная звезда)
        LinearLayout headerCard = DashboardCardRenderer.makeCard(this, "#0F1B26", "#FFD54F");
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView medallionIv = new ImageView(this);
        medallionIv.setImageResource(R.drawable.ic_app_medallion);
        LinearLayout.LayoutParams medLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        medLp.rightMargin = dp(8);
        medallionIv.setLayoutParams(medLp);
        titleRow.addView(medallionIv);

        TextView title = new TextView(this);
        title.setText("IdleArcHelper v" + OtaUpdater.getCurrentVersionName(this));
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#FFD54F"));
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(title);

        Button launchGameBtn = DashboardCardRenderer.pillButton(this, "🎮 Открыть игру", "#183D32", "#69F0AE");
        launchGameBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SystemAlarmAndNotifier.openIdleArcGame(MainActivity.this);
            }
        });
        titleRow.addView(launchGameBtn);
        headerCard.addView(titleRow);

        syncStatusTv = new TextView(this);
        syncStatusTv.setTextSize(12f);
        syncStatusTv.setTextColor(Color.parseColor("#B3E5FC"));
        syncStatusTv.setPadding(0, dp(4), 0, dp(8));
        headerCard.addView(syncStatusTv);

        // Кнопки управления API, виджетом и OTA
        LinearLayout topBtns = new LinearLayout(this);
        topBtns.setOrientation(LinearLayout.HORIZONTAL);

        syncNowBtn = makeTopActionBtn("🔄 Обновить API", "#174236", "#69F0AE");
        syncNowBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerApiSync(true);
            }
        });
        topBtns.addView(syncNowBtn, weightLp(4));

        Button accBtn = makeTopActionBtn("🔑 Аккаунт", "#1A3248", "#81D4FA");
        accBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCredentialsDialog();
            }
        });
        topBtns.addView(accBtn, weightLp(4));

        overlayBtn = makeTopActionBtn("📌 Виджет", "#2B223C", "#CE93D8");
        overlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleFloatingApiWidget();
            }
        });
        topBtns.addView(overlayBtn, weightLp(0));
        headerCard.addView(topBtns);

        LinearLayout subTopRow = new LinearLayout(this);
        subTopRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams subTopLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subTopLp.topMargin = dp(6);
        subTopRow.setLayoutParams(subTopLp);

        liveBtn = DashboardCardRenderer.pillButton(this, "LIVE: выкл", "#20323A", "#B0BEC5");
        liveBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!liveEnabled && !IdleArcApiClient.hasSavedCredentials(MainActivity.this)) {
                    showCredentialsDialog();
                    return;
                }
                liveEnabled = !liveEnabled;
                liveBtn.setText(liveEnabled ? "● LIVE 1с" : "LIVE: выкл");
                if (liveEnabled && activityResumed) LiveSyncManager.get().add(MainActivity.this, MainActivity.this, liveListener);
                else LiveSyncManager.get().remove(MainActivity.this);
                if (!liveEnabled) liveStatus = "";
                refreshAllViews();
            }
        });
        subTopRow.addView(liveBtn, weightLp(5));

        classPickerBtn = DashboardCardRenderer.pillButton(
                this, "🌟 Класс: Авто", "#221C35", "#FFE082");
        classPickerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showClassPickerDialog();
            }
        });
        subTopRow.addView(classPickerBtn, weightLp(5));

        Button otaBtn = DashboardCardRenderer.pillButton(
                this, "⬇️ Проверить OTA", "#132230", "#80DEEA");
        otaBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runOtaCheck(true);
            }
        });
        subTopRow.addView(otaBtn, weightLp(0));
        headerCard.addView(subTopRow);
        root.addView(headerCard);

        // 2. Переключатель цели расчёта (Пуш / Фарм / Босс)
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, 0, 0, dp(8));
        final DruidProfile.BuildMode[] modes = DruidProfile.BuildMode.values();
        for (int i = 0; i < modes.length; i++) {
            final int idx = i;
            Button mb = new Button(this);
            mb.setText(modes[i].title);
            mb.setAllCaps(false);
            mb.setTextSize(12f);
            mb.setMinimumHeight(0);
            mb.setMinHeight(0);
            mb.setPadding(dp(6), dp(7), dp(6), dp(7));
            mb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    profile.mode = modes[idx];
                    profile.save(MainActivity.this);
                    refreshAllViews();
                }
            });
            modeButtons[i] = mb;
            modeRow.addView(mb, weightLp(i < modes.length - 1 ? 5 : 0));
        }
        root.addView(modeRow);

        // 3. Горизонтальная лента 7 вкладок
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, 0, 0, dp(8));
        for (int i = 0; i < TAB_TITLES.length; i++) {
            final int tabIdx = i;
            Button tb = new Button(this);
            tb.setText(TAB_TITLES[i]);
            tb.setAllCaps(false);
            tb.setTextSize(12f);
            tb.setMinimumHeight(0);
            tb.setMinHeight(0);
            tb.setPadding(dp(11), dp(7), dp(11), dp(7));
            tb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activeTab = tabIdx;
                    refreshAllViews();
                }
            });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.rightMargin = dp(6);
            tabButtons[i] = tb;
            tabRow.addView(tb, tlp);
        }
        tabScroll.addView(tabRow);
        root.addView(tabScroll);

        // 4. Под-фильтр слотов для вкладки «🛡️ Шмот (10)»
        slotChipScroll = new HorizontalScrollView(this);
        slotChipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, 0, 0, dp(8));
        final ApiDashboardEngine.EquippedSlotKey[] slotKeys = ApiDashboardEngine.EquippedSlotKey.values();
        slotChipButtons = new Button[slotKeys.length];
        for (int i = 0; i < slotKeys.length; i++) {
            final ApiDashboardEngine.EquippedSlotKey sk = slotKeys[i];
            Button cb = new Button(this);
            cb.setText(sk.chipTitle);
            cb.setAllCaps(false);
            cb.setTextSize(11.5f);
            cb.setMinimumHeight(0);
            cb.setMinHeight(0);
            cb.setPadding(dp(9), dp(5), dp(9), dp(5));
            cb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedEquipSlot = sk;
                    refreshAllViews();
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = dp(5);
            slotChipButtons[i] = cb;
            chipRow.addView(cb, clp);
        }
        slotChipScroll.addView(chipRow);
        root.addView(slotChipScroll);

        // 5. Контейнер нативных карточек активной вкладки
        cardsContainer = new LinearLayout(this);
        cardsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(cardsContainer);

        setContentView(rootScroll);

        IdleArcApiClient.ensureCacheLoaded(this, profile);
        refreshAllViews();
        runOtaCheck(false);

        if (IdleArcApiClient.hasSavedCredentials(this)) {
            triggerApiSync(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (liveEnabled) LiveSyncManager.get().add(this, this, liveListener);
        OtaUpdater.resumePendingInstallIfPermitted(this);
        profile = DruidProfile.load(this);
        refreshAllViews();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        LiveSyncManager.get().remove(this);
        super.onPause();
    }

    private void updateLiveStatusText() {
        if (syncStatusTv != null && liveEnabled && !liveStatus.isEmpty()) {
            syncStatusTv.setText(liveStatus + " · только dashboard; сумка и таймеры — полное обновление API");
        }
    }

    private void runOtaCheck(final boolean manual) {
        if (manual) {
            Toast.makeText(this, "Проверяю обновления на GitHub...", Toast.LENGTH_SHORT).show();
        }
        OtaUpdater.checkForUpdatesAsync(this, new OtaUpdater.CheckCallback() {
            @Override
            public void onUpdateAvailable(final OtaUpdater.ReleaseInfo latest, int currentCode, String currentName) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("🚀 Доступен IdleArcHelper v" + latest.versionName)
                        .setMessage("Текущая версия: v" + currentName + "\nНовая версия: v" + latest.versionName
                                + (latest.changelog.isEmpty() ? "" : ("\n\nЧто нового:\n" + latest.changelog)))
                        .setPositiveButton("Скачать и обновить", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                Toast.makeText(MainActivity.this, "⏳ Скачиваю v" + latest.versionName + "...", Toast.LENGTH_SHORT).show();
                                OtaUpdater.downloadUpdateAsync(MainActivity.this, latest, new OtaUpdater.DownloadCallback() {
                                    @Override
                                    public void onProgress(int percent, long downloadedBytes, long totalBytes) {
                                    }

                                    @Override
                                    public void onReadyToInstall(java.io.File apkFile) {
                                    }

                                    @Override
                                    public void onError(String message) {
                                        Toast.makeText(MainActivity.this, "Ошибка загрузки OTA: " + message, Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        })
                        .setNegativeButton("Позже", null)
                        .show();
            }

            @Override
            public void onUpToDate(int currentCode, String currentName) {
                if (manual) {
                    Toast.makeText(MainActivity.this, "✅ Установлена самая свежая версия v" + currentName, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                if (manual) {
                    Toast.makeText(MainActivity.this, "Не удалось проверить OTA: " + message, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void triggerApiSync(final boolean showToast) { triggerApiSync(showToast, 0); }

    private void triggerApiSync(final boolean showToast, final int retry) {
        if (syncNowBtn != null && !syncNowBtn.isEnabled()) return;
        if (!IdleArcApiClient.hasSavedCredentials(this)) {
            showCredentialsDialog();
            return;
        }
        syncNowBtn.setText("⏳ Синхр...");
        syncNowBtn.setEnabled(false);
        IdleArcApiClient.syncAsync(this, profile, new IdleArcApiClient.ApiCallback() {
            @Override
            public void onSuccess(final ScreenAnalyzer.ScanResult result) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        syncNowBtn.setText("🔄 Обновить API");
                        syncNowBtn.setEnabled(true);
                        Map<String, Object> dash = IdleArcApiClient.getCachedDashboard();
                        SystemAlarmAndNotifier.processDashboardEvents(MainActivity.this, dash);
                        refreshAllViews();
                        if (showToast) {
                            Toast.makeText(MainActivity.this,
                                    "✅ Серверный слепок обновлён (Ур. " + profile.charLevel + ")",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }

            @Override
            public void onError(final String errorMessage) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        syncNowBtn.setText("🔄 Обновить API");
                        syncNowBtn.setEnabled(true);
                        refreshAllViews();
                        if (showToast && IdleArcApiClient.SYNC_BUSY_MESSAGE.equals(errorMessage) && retry < 6 && activityResumed) {
                            syncNowBtn.postDelayed(new Runnable() { @Override public void run() {
                                if (activityResumed) triggerApiSync(true, retry + 1);
                            }}, 300L);
                            return;
                        }
                        if (showToast) {
                            String message = IdleArcApiClient.SYNC_BUSY_MESSAGE.equals(errorMessage)
                                    ? "Синхронизация уже идёт, повторите через секунду." : "Ошибка API: " + errorMessage;
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    private void showCredentialsDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        form.setPadding(p, dp(10), p, dp(4));

        TextView note = new TextView(this);
        note.setText("Вход в API idlearc.com. Приложение запрашивает данные для чтения, но полномочия токена задаёт игра.\nПароль и токен сохраняются локально без дополнительного шифрования. Не используй пароль, который повторяется на других сайтах.");
        note.setTextSize(12.5f);
        form.addView(note);

        final EditText emailEt = new EditText(this);
        emailEt.setHint("Email от IdleArc");
        emailEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailEt.setText(IdleArcApiClient.getSavedEmail(this));
        form.addView(emailEt);

        final EditText passEt = new EditText(this);
        passEt.setHint("Пароль от IdleArc");
        passEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(passEt);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("🔑 Подключение аккаунта IdleArc")
                .setView(form)
                .setPositiveButton("Подключить и синхронизировать", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String em = emailEt.getText().toString().trim();
                        String pw = passEt.getText().toString();
                        if (em.isEmpty()) {
                            Toast.makeText(MainActivity.this, "Укажи Email от игры.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!pw.isEmpty()) {
                            IdleArcApiClient.saveCredentials(MainActivity.this, em, pw, "");
                        }
                        triggerApiSync(true);
                    }
                })
                .setNegativeButton("Отмена", null);

        if (IdleArcApiClient.hasSavedCredentials(this)) {
            b.setNeutralButton("Отвязать", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    IdleArcApiClient.clearCredentials(MainActivity.this);
                    refreshAllViews();
                }
            });
        }
        AlertDialog dialog = b.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            if (Build.VERSION.SDK_INT >= 31) dialog.getWindow().setHideOverlayWindows(true);
        }
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
    }

    private void toggleFloatingApiWidget() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разреши показ виджета поверх окон для Druid Helper", Toast.LENGTH_LONG).show();
            Intent perm = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(perm);
            return;
        }
        Intent svc = new Intent(this, OverlayService.class);
        if (OverlayService.isRunning()) {
            stopService(svc);
            overlayBtn.setText("📌 Виджет");
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            overlayBtn.setText("⏹ Скрыть");
            Toast.makeText(this, "Виджет 🌿 запущен поверх IdleArc!", Toast.LENGTH_SHORT).show();
        }
    }

    private void showClassPickerDialog() {
        HeroClassProfile.HeroClass det = HeroClassProfile.HeroClass.fromIdOrName(profile.detectedClassId, "");
        String[] items = {
            "✨ Автоматически из аккаунта API (" + det.icon + " " + det.ruName + ")",
            "⚔️ Воин (Сила / Оплот / Возмездие)",
            "🏹 Лучник (Меткость / Охота / Зверолов)",
            "🔮 Маг (Тайная магия / Разрушение / Хаос)",
            "🗡️ Разбойник (Убийца / Тень / Вор)",
            "🌿 Друид (Ложа / Туннели / Питомцы)"
        };
        new AlertDialog.Builder(this)
                .setTitle("🌟 Выбери класс для расчёта Сумки, Шмота и Камней")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        profile.overrideClassId = which; // 0 = Авто, 1..5 = конкретный класс
                        profile.save(MainActivity.this);
                        refreshAllViews();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void refreshAllViews() {
        overlayBtn.setText(OverlayService.isRunning() ? "⏹ Скрыть" : "📌 Виджет");
        Map<String, Object> dash = IdleArcApiClient.getCachedDashboard();
        Map<String, Object> cfg = IdleArcApiClient.getCachedGameConfig();

        HeroClassProfile.HeroClass effCls = HeroClassProfile.resolveHeroClass(dash, profile);
        if (classPickerBtn != null) {
            String prefix = (profile.overrideClassId >= 1 && profile.overrideClassId <= 5) ? "Класс: " : "Авто: ";
            classPickerBtn.setText("🌟 " + prefix + effCls.icon + " " + effCls.ruName);
        }

        // Статус синхронизации
        if (dash != null && !dash.isEmpty()) {
            long ageSec = Math.max(0L, (System.currentTimeMillis() - IdleArcApiClient.getLastSyncTimeMs()) / 1000L);
            String ageText = ageSec < 60 ? (ageSec + " сек назад") : ((ageSec / 60L) + " мин назад");
            int bagCnt = IdleArcApiClient.getCachedInventoryItems() != null ? IdleArcApiClient.getCachedInventoryItems().size() : 0;
            syncStatusTv.setText("🌐 " + SimpleJson.str(dash, "name", "Герой")
                    + " · " + effCls.icon + " " + effCls.ruName
                    + " Ур." + profile.charLevel
                    + " · Сумка: " + bagCnt + " вещ. · " + ageText);
        } else if (IdleArcApiClient.hasSavedCredentials(this)) {
            syncStatusTv.setText("🔑 Аккаунт подключён (" + IdleArcApiClient.getSavedEmail(this) + ") — нажми «🔄 Обновить API»");
        } else {
            syncStatusTv.setText("⚠️ Аккаунт ещё не подключён — нажми «🔑 Аккаунт» для входа в API idlearc.com");
        }

        updateLiveStatusText();
        // Подсветка режима
        DruidProfile.BuildMode[] modes = DruidProfile.BuildMode.values();
        for (int i = 0; i < modes.length; i++) {
            boolean sel = (profile.mode == modes[i]);
            styleChip(modeButtons[i], sel ? "#1B4B3A" : "#121D27", sel ? "#69F0AE" : "#90A4AE", sel);
        }

        // Подсветка вкладок
        for (int i = 0; i < tabButtons.length; i++) {
            boolean sel = (activeTab == i);
            styleChip(tabButtons[i], sel ? "#1A374D" : "#121C24", sel ? "#4FC3F7" : "#90A4AE", sel);
        }

        // Фильтр слотов виден только на вкладке «🛡️ Шмот (10)» (индекс 2)
        slotChipScroll.setVisibility(activeTab == 2 ? View.VISIBLE : View.GONE);
        if (activeTab == 2 && slotChipButtons != null) {
            ApiDashboardEngine.EquippedSlotKey[] keys = ApiDashboardEngine.EquippedSlotKey.values();
            for (int i = 0; i < keys.length; i++) {
                boolean sel = (selectedEquipSlot == keys[i]);
                styleChip(slotChipButtons[i], sel ? "#2F2418" : "#121C24", sel ? "#FFD54F" : "#90A4AE", sel);
            }
        }

        DashboardCardRenderer.UiRefreshCallback cb = new DashboardCardRenderer.UiRefreshCallback() {
            @Override
            public void onRequestRefresh() {
                refreshAllViews();
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
                DashboardCardRenderer.renderEquipmentTab(this, cardsContainer, dash, profile, selectedEquipSlot);
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

    private Button makeTopActionBtn(String label, String bgHex, String fgHex) {
        return DashboardCardRenderer.pillButton(this, label, bgHex, fgHex);
    }

    private void styleChip(Button b, String bgHex, String fgHex, boolean bold) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        gd.setCornerRadius(dp(8));
        gd.setStroke(dp(1), Color.parseColor(bold ? fgHex : "#233442"));
        b.setBackground(gd);
        b.setTextColor(Color.parseColor(fgHex));
        b.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private LinearLayout.LayoutParams weightLp(int rightMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (rightMarginDp > 0) lp.rightMargin = dp(rightMarginDp);
        return lp;
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }
}
