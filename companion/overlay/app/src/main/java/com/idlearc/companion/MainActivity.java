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
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Главный экран приложения IdleArc Druid Helper.
 * Позволяет в 2 клика выдать разрешение «Поверх других окон» и запустить плавающий сканер экрана поверх IdleArc,
 * а также посмотреть полный аудит Друида [UND] AlfaGideon (Ур. 324) и гайд 1 -> Кап.
 */
public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 5001;
    private static final int REQ_PROJECTION = 5002;
    private static final int REQ_NOTIF = 5003;

    private TextView permStatusTv;
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        profile = DruidProfile.load(this);
        updatePermissionBanner();
    }

    private void buildMainScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF12101B);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));

        // Заголовок
        TextView headerTitle = new TextView(this);
        headerTitle.setText("🌿 IdleArc Druid Helper v1.4.1");
        headerTitle.setTextColor(0xFF34D399);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f);
        root.addView(headerTitle);

        TextView subtitle = new TextView(this);
        subtitle.setText("Плавающий сканер + Подбор аффиксов и ★ Перерождения под каждый слот · Офлайн OCR RU/EN");
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
                for (int j = 0; j < allGearSlots.length; j++) {
                    boolean sel = (j == idx);
                    GradientDrawable gd = new GradientDrawable();
                    gd.setColor(sel ? 0xFF5B21B6 : 0xFF252236);
                    gd.setCornerRadius(dp(10));
                    gd.setStroke(dp(1), sel ? 0xFFC4B5FD : 0xFF3F3B54);
                    mainSlotChips[j].setBackground(gd);
                    mainSlotChips[j].setTextColor(sel ? 0xFFFFFFFF : 0xFFD1D5DB);
                }
                slotDetailTv.setText(SlotAffixAdvisor.formatSlotGuide(gs, profile));
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

        // Карточка аудита текущего билда [UND] AlfaGideon
        LinearLayout auditCard = makeCard(0xFF181625, 0xFFFBBF24);
        LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        acLp.topMargin = dp(14);
        auditCard.setLayoutParams(acLp);

        TextView auditBody = new TextView(this);
        auditBody.setText(DruidKnowledgeBase.buildAuditReport(profile));
        auditBody.setTextColor(0xFFF3F4F6);
        auditBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        auditBody.setLineSpacing(dp(2), 1.08f);
        auditCard.addView(auditBody);
        root.addView(auditCard);

        // Карточка Автонадписей и Питомцев
        LinearLayout inscCard = makeCard(0xFF181625, 0xFFA78BFA);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        icLp.topMargin = dp(14);
        inscCard.setLayoutParams(icLp);

        TextView inscBody = new TextView(this);
        inscBody.setText(DruidKnowledgeBase.buildInscriptionsAndPetsGuide(profile));
        inscBody.setTextColor(0xFFE5E7EB);
        inscBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.3f);
        inscBody.setLineSpacing(dp(2), 1.08f);
        inscCard.addView(inscBody);
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
