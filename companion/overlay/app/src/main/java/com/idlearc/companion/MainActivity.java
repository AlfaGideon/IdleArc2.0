package com.idlearc.companion;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
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

/**
 * Главный экран приложения IdleArc Druid Helper v2.2.0.
 * Прямая синхронизация с официальным API (idlearc.com/api/dashboard), визуальный OCR-сканер,
 * анализатор Гнёзд/Самоцветов, реляционный симулятор снаряжения и обновление по воздуху (OTA).
 */
public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 5001;
    private static final int REQ_PROJECTION = 5002;
    private static final int REQ_NOTIF = 5003;

    private TextView permStatusTv;
    private TextView otaStatusTv;
    private TextView otaActionBtn;
    private OtaUpdater.ReleaseInfo availableRelease = null;
    private boolean otaBusy = false;

    private TextView apiStatusTv;
    private TextView apiReportTv;
    private boolean apiBusy = false;

    private TextView gemBodyTv;
    private TextView auditBodyTv;
    private TextView inscBodyTv;
    private TextView slotDetailBodyTv;
    private final TextView[] mainModeButtons = new TextView[DruidProfile.BuildMode.values().length];
    private SlotAffixAdvisor.GearSlot activeMainSlot = SlotAffixAdvisor.GearSlot.WEAPON;
    private DruidProfile profile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        profile = DruidProfile.load(this);

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }

        buildMainScreen();
        runOtaCheck(true);
        if (IdleArcApiClient.hasSavedCredentials(this)) {
            triggerMainApiSync();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        profile = DruidProfile.load(this);
        updatePermissionBanner();
        refreshDynamicCards();
        OtaUpdater.resumePendingInstallIfPermitted(this);
    }

    private void refreshDynamicCards() {
        DruidProfile.BuildMode[] modes = DruidProfile.BuildMode.values();
        for (int i = 0; i < modes.length; i++) {
            if (mainModeButtons[i] != null) {
                boolean sel = (profile.mode == modes[i]);
                GradientDrawable gd = new GradientDrawable();
                gd.setColor(sel ? 0xFF065F46 : 0xFF252236);
                gd.setCornerRadius(dp(10));
                gd.setStroke(dp(1), sel ? 0xFF34D399 : 0xFF3F3B54);
                mainModeButtons[i].setBackground(gd);
                mainModeButtons[i].setTextColor(sel ? 0xFFECFDF5 : 0xFFD1D5DB);
            }
        }
        if (gemBodyTv != null) {
            gemBodyTv.setText(GemSocketOptimizer.buildOptimalGemBlueprint(profile, 46));
        }
        if (auditBodyTv != null) {
            auditBodyTv.setText(DruidKnowledgeBase.buildAuditReport(profile));
        }
        if (inscBodyTv != null) {
            inscBodyTv.setText(DruidKnowledgeBase.buildInscriptionsAndPetsGuide(profile));
        }
        if (slotDetailBodyTv != null && activeMainSlot != null) {
            String apiSlot = IdleArcApiClient.buildEquippedSlotDetailFromCache(activeMainSlot, profile);
            String baseGuide = SlotAffixAdvisor.formatSlotGuide(activeMainSlot, profile);
            slotDetailBodyTv.setText(apiSlot != null ? (apiSlot + "\n\n" + baseGuide) : baseGuide);
        }
        if (apiReportTv != null && IdleArcApiClient.getCachedDashboard() != null) {
            ScreenAnalyzer.ScanResult sr = IdleArcApiClient.buildFullDashboardScanResult(
                    IdleArcApiClient.getCachedDashboard(), profile);
            apiReportTv.setText(sr.details);
            apiReportTv.setVisibility(View.VISIBLE);
        }
    }

    private void buildMainScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF12101B);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));

        // Заголовок
        TextView headerTitle = new TextView(this);
        headerTitle.setText("🌿 IdleArc Druid Helper v" + OtaUpdater.getCurrentVersionName(this));
        headerTitle.setTextColor(0xFF34D399);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f);
        root.addView(headerTitle);

        TextView subtitle = new TextView(this);
        subtitle.setText("Прямая синхронизация с сервером /api/dashboard + Live OCR + Гнёзда/Самоцветы + OTA");
        subtitle.setTextColor(0xFF9CA3AF);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        // Карточка запуска виджета
        LinearLayout launchCard = makeCard(0xFF1B1829, 0xFF34D399);

        permStatusTv = new TextView(this);
        permStatusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        permStatusTv.setPadding(0, 0, 0, dp(10));
        launchCard.addView(permStatusTv);

        TextView mainLaunchBtn = makeBigButton(
                "🚀 ЗАПУСТИТЬ ПЛАВАЮЩИЙ СКАНЕР ПОВЕРХ ИГРЫ",
                0xFF059669, 0xFFFFFFFF);
        mainLaunchBtn.setOnClickListener(v -> startFullScannerFlow());
        launchCard.addView(mainLaunchBtn);

        TextView guideOnlyBtn = makeBigButton(
                "📖 Запустить виджет-шпаргалку (без захвата экрана)",
                0xFF2D2844, 0xFFE5E7EB);
        LinearLayout.LayoutParams gpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gpLp.topMargin = dp(8);
        guideOnlyBtn.setLayoutParams(gpLp);
        guideOnlyBtn.setOnClickListener(v -> startGuideOnlyOverlay());
        launchCard.addView(guideOnlyBtn);

        TextView stopBtn = makeBigButton(
                "✖ Остановить плавающий виджет",
                0xFF3B1C26, 0xFFFCA5A5);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(8);
        stopBtn.setLayoutParams(stLp);
        stopBtn.setOnClickListener(v -> {
            stopService(new Intent(MainActivity.this, OverlayService.class));
            Toast.makeText(MainActivity.this, "Виджет остановлен", Toast.LENGTH_SHORT).show();
        });
        launchCard.addView(stopBtn);

        TextView howToTv = new TextView(this);
        howToTv.setText("\nКак пользоваться в IdleArc:\n"
                + "1. Нажми зелёную кнопку выше -> разреши «Поверх других окон» и «Запись/трансляцию экрана».\n"
                + "2. Открой IdleArc — сбоку появится плавающая таблетка «🌿 ДРУИД | 🔍 СКАН | ⚡».\n"
                + "3. Открой любой выпавший предмет, сравнение шмота, 3 надписи талисмана, навык или «Характеристики персонажа» и нажми «🔍 СКАН» (или включи «⚡» для авто-отслеживания!).");
        howToTv.setTextColor(0xFFD1D5DB);
        howToTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        howToTv.setLineSpacing(dp(2), 1.05f);
        launchCard.addView(howToTv);

        root.addView(launchCard);

        // Карточка обновления по воздуху (OTA)
        LinearLayout otaCard = makeCard(0xFF16202B, 0xFF38BDF8);
        LinearLayout.LayoutParams otaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        otaLp.topMargin = dp(12);
        otaCard.setLayoutParams(otaLp);

        TextView otaTitle = new TextView(this);
        otaTitle.setText("☁️ Обновление по воздуху (OTA)");
        otaTitle.setTextColor(0xFF7DD3FC);
        otaTitle.setTypeface(Typeface.DEFAULT_BOLD);
        otaTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        otaCard.addView(otaTitle);

        otaStatusTv = new TextView(this);
        otaStatusTv.setText("Текущая версия: v" + OtaUpdater.getCurrentVersionName(this)
                + " (сборка " + OtaUpdater.getCurrentVersionCode(this) + ") · проверка обновлений...");
        otaStatusTv.setTextColor(0xFFD1D5DB);
        otaStatusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        otaStatusTv.setPadding(0, dp(4), 0, dp(8));
        otaCard.addView(otaStatusTv);

        otaActionBtn = makeBigButton("🔄 Проверить обновление на GitHub", 0xFF0369A1, 0xFFFFFFFF);
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

        // Карточка прямой синхронизации с официальным API игры (https://idlearc.com/api/dashboard)
        LinearLayout apiCard = makeCard(0xFF132030, 0xFF38BDF8);
        LinearLayout.LayoutParams apiCardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        apiCardLp.topMargin = dp(14);
        apiCard.setLayoutParams(apiCardLp);

        TextView apiTitle = new TextView(this);
        apiTitle.setText("🌐 Прямая синхронизация с сервером (idlearc.com/api/dashboard)");
        apiTitle.setTextColor(0xFF7DD3FC);
        apiTitle.setTypeface(Typeface.DEFAULT_BOLD);
        apiTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.2f);
        apiCard.addView(apiTitle);

        apiStatusTv = new TextView(this);
        apiStatusTv.setText(IdleArcApiClient.hasSavedCredentials(this)
                ? ("✅ Аккаунт подключён (" + IdleArcApiClient.getSavedEmail(this) + ") · можно обновлять в 1 клик кнопкой «🌐 API» прямо в виджете!")
                : "Введи Email и пароль от IdleArc один раз (хранятся только локально на твоём телефоне). За 0.4 сек загружает все 31 стат, все 10 надетых вещей и все 20 камней с 0% погрешности OCR!");
        apiStatusTv.setTextColor(0xFFD1D5DB);
        apiStatusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.1f);
        apiStatusTv.setPadding(0, dp(4), 0, dp(8));
        apiCard.addView(apiStatusTv);

        final EditText emailInput = new EditText(this);
        emailInput.setHint("Email от аккаунта IdleArc");
        emailInput.setHintTextColor(0xFF6B7280);
        emailInput.setTextColor(0xFFF9FAFB);
        emailInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        emailInput.setSingleLine(true);
        emailInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailInput.setText(IdleArcApiClient.getSavedEmail(this));
        emailInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable emBg = new GradientDrawable();
        emBg.setColor(0xFF0F172A);
        emBg.setCornerRadius(dp(10));
        emBg.setStroke(dp(1), 0xFF334155);
        emailInput.setBackground(emBg);
        apiCard.addView(emailInput);

        final EditText passInput = new EditText(this);
        passInput.setHint(IdleArcApiClient.hasSavedCredentials(this)
                ? "Пароль сохранён локально (оставь пустым или введи заново)"
                : "Пароль от аккаунта IdleArc");
        passInput.setHintTextColor(0xFF6B7280);
        passInput.setTextColor(0xFFF9FAFB);
        passInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        passInput.setSingleLine(true);
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams pwLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pwLp.topMargin = dp(6);
        passInput.setLayoutParams(pwLp);
        GradientDrawable pwBg = new GradientDrawable();
        pwBg.setColor(0xFF0F172A);
        pwBg.setCornerRadius(dp(10));
        pwBg.setStroke(dp(1), 0xFF334155);
        passInput.setBackground(pwBg);
        apiCard.addView(passInput);

        TextView syncApiBtn = makeBigButton("🌐 Синхронизировать героя (31 стат + 10 вещей + 20 камней)", 0xFF0284C7, 0xFFFFFFFF);
        LinearLayout.LayoutParams syncLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        syncLp.topMargin = dp(8);
        syncApiBtn.setLayoutParams(syncLp);
        syncApiBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String em = emailInput.getText() != null ? emailInput.getText().toString().trim() : "";
                String pw = passInput.getText() != null ? passInput.getText().toString() : "";
                if (!em.isEmpty() && !pw.isEmpty()) {
                    IdleArcApiClient.saveCredentials(MainActivity.this, em, pw, "");
                    passInput.setText("");
                    passInput.setHint("Пароль сохранён локально");
                }
                triggerMainApiSync();
            }
        });
        apiCard.addView(syncApiBtn);

        TextView clearApiBtn = makeBigButton("🗑️ Удалить сохранённый логин API с устройства", 0xFF1E293B, 0xFF94A3B8);
        LinearLayout.LayoutParams clrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clrLp.topMargin = dp(6);
        clearApiBtn.setLayoutParams(clrLp);
        clearApiBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                IdleArcApiClient.clearCredentials(MainActivity.this);
                emailInput.setText("");
                passInput.setText("");
                passInput.setHint("Пароль от аккаунта IdleArc");
                apiStatusTv.setText("Локальные данные API удалены.");
                apiStatusTv.setTextColor(0xFF9CA3AF);
                if (apiReportTv != null) apiReportTv.setVisibility(View.GONE);
                refreshDynamicCards();
            }
        });
        apiCard.addView(clearApiBtn);

        apiReportTv = new TextView(this);
        apiReportTv.setTextColor(0xFFE5E7EB);
        apiReportTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.1f);
        apiReportTv.setLineSpacing(dp(2), 1.08f);
        apiReportTv.setPadding(0, dp(10), 0, 0);
        apiReportTv.setVisibility(View.GONE);
        apiCard.addView(apiReportTv);

        root.addView(apiCard);

        // Переключатель сборки + Карточка «💎 Гнёзда и Самоцветы»
        LinearLayout gemCard = makeCard(0xFF172226, 0xFF34D399);
        LinearLayout.LayoutParams gemLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gemLp.topMargin = dp(14);
        gemCard.setLayoutParams(gemLp);

        TextView gemTitle = new TextView(this);
        gemTitle.setText("💎 Гнёзда и Самоцветы под разные сборки");
        gemTitle.setTextColor(0xFF6EE7B7);
        gemTitle.setTypeface(Typeface.DEFAULT_BOLD);
        gemTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        gemCard.addView(gemTitle);

        TextView gemSub = new TextView(this);
        gemSub.setText("Выбери активную сборку — расчёт лучших камней и доп. статов пересчитается автоматически (а при скане предмета читает камни прямо над аффиксами!):");
        gemSub.setTextColor(0xFF9CA3AF);
        gemSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        gemSub.setPadding(0, dp(3), 0, dp(8));
        gemCard.addView(gemSub);

        HorizontalScrollView modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, 0, 0, dp(10));
        final DruidProfile.BuildMode[] allModes = DruidProfile.BuildMode.values();
        for (int i = 0; i < allModes.length; i++) {
            final DruidProfile.BuildMode bm = allModes[i];
            TextView mb = new TextView(this);
            mb.setText(bm.title);
            mb.setTypeface(Typeface.DEFAULT_BOLD);
            mb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            mb.setPadding(dp(10), dp(7), dp(10), dp(7));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) mlp.leftMargin = dp(6);
            mb.setLayoutParams(mlp);
            mb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    profile.mode = bm;
                    profile.save(MainActivity.this);
                    refreshDynamicCards();
                }
            });
            mainModeButtons[i] = mb;
            modeRow.addView(mb);
        }
        modeScroll.addView(modeRow);
        gemCard.addView(modeScroll);

        gemBodyTv = new TextView(this);
        gemBodyTv.setTextColor(0xFFF3F4F6);
        gemBodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.4f);
        gemBodyTv.setLineSpacing(dp(2), 1.08f);
        gemCard.addView(gemBodyTv);
        root.addView(gemCard);

        // Интерактивная карточка: Подбор аффиксов и ★ Перерождения/Пробуждения под каждый вид предмета (11 слотов)
        LinearLayout slotCard = makeCard(0xFF1A162B, 0xFFC084FC);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scLp.topMargin = dp(14);
        slotCard.setLayoutParams(scLp);

        TextView slotCardTitle = new TextView(this);
        slotCardTitle.setText("🛠️ Аффиксы и ★ Перерождение под каждый слот");
        slotCardTitle.setTextColor(0xFFE9D5FF);
        slotCardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        slotCardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        slotCard.addView(slotCardTitle);

        TextView slotCardSub = new TextView(this);
        slotCardSub.setText("Нажми на нужный вид предмета (доступно и прямо в плавающем виджете на вкладке «Слоты & ★»):");
        slotCardSub.setTextColor(0xFF9CA3AF);
        slotCardSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        slotCardSub.setPadding(0, dp(3), 0, dp(8));
        slotCard.addView(slotCardSub);

        HorizontalScrollView mainSlotScroll = new HorizontalScrollView(this);
        mainSlotScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout mainSlotRow = new LinearLayout(this);
        mainSlotRow.setOrientation(LinearLayout.HORIZONTAL);
        mainSlotRow.setPadding(0, 0, 0, dp(10));

        final TextView slotDetailTv = new TextView(this);
        slotDetailTv.setTextColor(0xFFF3F4F6);
        slotDetailTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.4f);
        slotDetailTv.setLineSpacing(dp(2), 1.08f);
        slotDetailBodyTv = slotDetailTv;

        final SlotAffixAdvisor.GearSlot[] allGearSlots = SlotAffixAdvisor.GearSlot.values();
        final TextView[] mainSlotChips = new TextView[allGearSlots.length];
        for (int i = 0; i < allGearSlots.length; i++) {
            final int idx = i;
            final SlotAffixAdvisor.GearSlot gs = allGearSlots[i];
            TextView chip = new TextView(this);
            chip.setText(gs.shortChip);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            chip.setPadding(dp(10), dp(7), dp(10), dp(7));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) clp.leftMargin = dp(6);
            chip.setLayoutParams(clp);
            chip.setOnClickListener(v -> {
                activeMainSlot = gs;
                for (int j = 0; j < allGearSlots.length; j++) {
                    boolean sel = (j == idx);
                    GradientDrawable gd = new GradientDrawable();
                    gd.setColor(sel ? 0xFF5B21B6 : 0xFF252236);
                    gd.setCornerRadius(dp(10));
                    gd.setStroke(dp(1), sel ? 0xFFC4B5FD : 0xFF3F3B54);
                    mainSlotChips[j].setBackground(gd);
                    mainSlotChips[j].setTextColor(sel ? 0xFFFFFFFF : 0xFFD1D5DB);
                }
                String apiSlot = IdleArcApiClient.buildEquippedSlotDetailFromCache(gs, profile);
                String baseGuide = SlotAffixAdvisor.formatSlotGuide(gs, profile);
                slotDetailTv.setText(apiSlot != null ? (apiSlot + "\n\n" + baseGuide) : baseGuide);
            });
            mainSlotChips[i] = chip;
            mainSlotRow.addView(chip);
        }
        mainSlotScroll.addView(mainSlotRow);
        slotCard.addView(mainSlotScroll);
        slotCard.addView(slotDetailTv);
        // Инициализируем первый слот (Оружие)
        mainSlotChips[0].performClick();
        root.addView(slotCard);

        // Карточка живого математического аудита отсканированного героя
        LinearLayout auditCard = makeCard(0xFF181625, 0xFFFBBF24);
        LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        acLp.topMargin = dp(14);
        auditCard.setLayoutParams(acLp);

        auditBodyTv = new TextView(this);
        auditBodyTv.setText(DruidKnowledgeBase.buildAuditReport(profile));
        auditBodyTv.setTextColor(0xFFF3F4F6);
        auditBodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        auditBodyTv.setLineSpacing(dp(2), 1.08f);
        auditCard.addView(auditBodyTv);

        TextView resetProfileBtn = makeBigButton(
                "🗑️ Сбросить сохранённый скан характеристик",
                0xFF2D2844, 0xFFFCD34D);
        LinearLayout.LayoutParams rpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rpLp.topMargin = dp(10);
        resetProfileBtn.setLayoutParams(rpLp);
        resetProfileBtn.setOnClickListener(v -> {
            profile.resetStats(MainActivity.this);
            refreshDynamicCards();
            Toast.makeText(MainActivity.this,
                    "Сохранённый скан сброшен. Открой «Характеристики персонажа» в игре и нажми СКАН.",
                    Toast.LENGTH_SHORT).show();
        });
        auditCard.addView(resetProfileBtn);
        root.addView(auditCard);

        // Карточка Автонадписей и Питомцев
        LinearLayout inscCard = makeCard(0xFF181625, 0xFFA78BFA);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        icLp.topMargin = dp(14);
        inscCard.setLayoutParams(icLp);

        inscBodyTv = new TextView(this);
        inscBodyTv.setText(DruidKnowledgeBase.buildInscriptionsAndPetsGuide(profile));
        inscBodyTv.setTextColor(0xFFE5E7EB);
        inscBodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.3f);
        inscBodyTv.setLineSpacing(dp(2), 1.08f);
        inscCard.addView(inscBodyTv);
        root.addView(inscCard);

        // Карточка полного гайда 1 -> Кап + все 18 навыков
        LinearLayout guideCard = makeCard(0xFF181625, 0xFF34D399);
        LinearLayout.LayoutParams gcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gcLp.topMargin = dp(14);
        guideCard.setLayoutParams(gcLp);

        StringBuilder skillsSummary = new StringBuilder();
        skillsSummary.append(DruidKnowledgeBase.buildFullLevelingGuide()).append("\n\n")
                     .append("━━ СПРАВОЧНИК ВСЕХ 18 НАВЫКОВ ДРУИДА (1.3.15) ━━\n");
        for (DruidKnowledgeBase.SkillInfo s : DruidKnowledgeBase.SKILLS) {
            skillsSummary.append("\n• ").append(s.ruName)
                         .append(" [").append(s.branch).append(" Р").append(s.tierRank).append("] — ")
                         .append(s.formulaRu).append("\n  ").append(s.capNoteRu);
        }

        TextView guideBody = new TextView(this);
        guideBody.setText(skillsSummary.toString());
        guideBody.setTextColor(0xFFE5E7EB);
        guideBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        guideBody.setLineSpacing(dp(2), 1.06f);
        guideCard.addView(guideBody);
        root.addView(guideCard);

        scroll.addView(root);
        setContentView(scroll);
        updatePermissionBanner();
        refreshDynamicCards();
    }

    private void triggerMainApiSync() {
        if (apiBusy) return;
        apiBusy = true;
        if (apiStatusTv != null) {
            apiStatusTv.setText("⏳ Подключение к https://idlearc.com/api/dashboard...");
            apiStatusTv.setTextColor(0xFF93C5FD);
        }
        IdleArcApiClient.syncAsync(this, profile, new IdleArcApiClient.ApiCallback() {
            @Override
            public void onSuccess(final ScreenAnalyzer.ScanResult scanResult) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        apiBusy = false;
                        if (apiStatusTv != null) {
                            apiStatusTv.setText("✅ " + scanResult.headline + " · Переключай режимы и слоты ниже!");
                            apiStatusTv.setTextColor(0xFF6EE7B7);
                        }
                        if (apiReportTv != null) {
                            apiReportTv.setText(scanResult.details);
                            apiReportTv.setVisibility(View.VISIBLE);
                        }
                        refreshDynamicCards();
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

    private void runOtaCheck(final boolean silentStartup) {
        if (otaBusy) return;
        otaBusy = true;
        if (!silentStartup && otaStatusTv != null) {
            otaStatusTv.setText("⏳ Проверка обновлений на GitHub...");
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
                    GradientDrawable gd = new GradientDrawable();
                    gd.setColor(0xFF059669);
                    gd.setCornerRadius(dp(12));
                    otaActionBtn.setBackground(gd);
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
                    otaStatusTv.setText("Текущая версия: v" + OtaUpdater.getCurrentVersionName(MainActivity.this)
                            + (silentStartup ? " · нажми кнопку для проверки сети" : (" · " + message)));
                    otaStatusTv.setTextColor(silentStartup ? 0xFF9CA3AF : 0xFFFCA5A5);
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
                    otaStatusTv.setText("⬇️ Загрузка обновления v" + rel.versionName + ": " + pStr
                            + " (проверка SHA-256...)");
                    otaStatusTv.setTextColor(0xFF93C5FD);
                }
            }

            @Override
            public void onReadyToInstall(File apkFile) {
                otaBusy = false;
                if (otaStatusTv != null) {
                    otaStatusTv.setText("✅ Файл v" + rel.versionName + " проверен! Подтверди установку поверх текущей версии.");
                    otaStatusTv.setTextColor(0xFF6EE7B7);
                }
                if (otaActionBtn != null) {
                    otaActionBtn.setText("📲 Установить скачанный v" + rel.versionName);
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
                    otaActionBtn.setText("🔄 Повторить проверку и скачивание v" + rel.versionName);
                }
            }
        });
    }

    private void updatePermissionBanner() {
        boolean canOverlay = Settings.canDrawOverlays(this);
        if (canOverlay) {
            permStatusTv.setText("✅ Разрешение «Поверх других окон» выдано — можно запускать сканер!");
            permStatusTv.setTextColor(0xFF6EE7B7);
        } else {
            permStatusTv.setText("⚠️ Шаг 1: требуется разрешение «Поверх других окон» (откроется по нажатию кнопки)");
            permStatusTv.setTextColor(0xFFFBBF24);
        }
    }

    private void startFullScannerFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Включи переключатель для Druid Helper и вернись назад", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm != null) {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
        }
    }

    private void startGuideOnlyOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            return;
        }
        Intent svc = new Intent(this, OverlayService.class);
        startService(svc);
        Toast.makeText(this, "🌿 Плавающая шпаргалка запущена! Открой IdleArc", Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            updatePermissionBanner();
            if (Settings.canDrawOverlays(this)) {
                startFullScannerFlow();
            }
        } else if (requestCode == REQ_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Intent svc = new Intent(this, OverlayService.class);
                svc.putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode);
                svc.putExtra(OverlayService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
                Toast.makeText(this, "🌿 Виджет запущен! Переключайся в IdleArc", Toast.LENGTH_SHORT).show();
                moveTaskToBack(true);
            } else {
                Toast.makeText(this, "Захват экрана отменён — можно запустить шпаргалку без захвата", Toast.LENGTH_LONG).show();
            }
        }
    }

    private LinearLayout makeCard(int bg, int stroke) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(1), stroke);
        card.setBackground(gd);
        return card;
    }

    private TextView makeBigButton(String text, int bg, int fg) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(fg);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadius(dp(12));
        tv.setBackground(gd);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
