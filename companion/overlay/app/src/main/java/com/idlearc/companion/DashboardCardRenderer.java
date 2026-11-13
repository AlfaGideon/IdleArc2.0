package com.idlearc.companion;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Нативный визуальный рендерер карточек Druid Helper v3.1.
 * Никаких сырых JSON/GSON-ключей и простыней текста: карточки с рамками редкости/тира,
 * цветные бейджи, графические прогресс-бары капов и роллов, аккордеоны, системные будильники
 * и интерактивная «Примерочная статов» (What-If).
 */
public final class DashboardCardRenderer {

    public interface UiRefreshCallback {
        void onRequestRefresh();
        void onRequestSync();
    }

    private static final java.util.WeakHashMap<Context, Map<String, Boolean>> expandedCards = new java.util.WeakHashMap<>();
    private static int activeItemBagFilter = 0; // 0 = Все, 1 = Уничтожить, 2 = Оставить
    private static int itemBagPage = 0;
    private static final int ITEM_BAG_PAGE_SIZE = 10;
    private static int activeGemBagFilter = 0;  // 0 = Все, 1 = Вставить/Слить, 2 = В пыль

    private DashboardCardRenderer() {
    }

    // =========================================================================
    // ВКЛАДКА 0: РАДАР АККАУНТА, БУДИЛЬНИКИ, БОЙ И ФАРМ/ЧАС
    // =========================================================================
    public static void renderRadarTab(final Context ctx,
                                      LinearLayout root,
                                      final Map<String, Object> dash,
                                      final DruidProfile profile,
                                      final UiRefreshCallback refreshCb) {
        root.removeAllViews();
        if (dash == null || dash.isEmpty()) {
            root.addView(emptySyncCard(ctx));
            return;
        }
        final long now = System.currentTimeMillis();
        final RadarTimeParser.RadarState radar = RadarTimeParser.parseRadar(dash, now);
        final boolean bossHasServerDeadline = radar.bossFightReadyAtMs > now;
        SystemAlarmAndNotifier.applyManualBossTimer(ctx, radar, now);
        final boolean quietHours = SystemAlarmAndNotifier.isQuietHoursEnabled(ctx);

        // 0. Баннер «До / После» (если были изменения статов с прошлой синхронизации)
        String diff = SnapshotHistoryTracker.getLastDiffSummary();
        if (diff != null && !diff.isEmpty()) {
            boolean pos = SnapshotHistoryTracker.isLastDiffPositive();
            LinearLayout diffCard = makeCard(ctx, pos ? "#122A20" : "#2D1D16", pos ? "#39D98A" : "#FFB74D");
            diffCard.addView(cardTitle(ctx, pos ? "✨ Изменения с прошлой синхронизации (До → После)" : "⚠️ Изменения статов (До → После)",
                    pos ? "#69F0AE" : "#FFD54F"));
            diffCard.addView(bodyText(ctx, diff, "#EBF5FF", 12.5f));
            root.addView(diffCard);
        }

        // 1. Быстрый запуск игры + Системные будильники откатов
        LinearLayout alarmCard = makeCard(ctx, "#121D28", "#4FC3F7");
        LinearLayout headerRow = row(ctx);
        TextView hTitle = cardTitle(ctx, "⏰ Откаты и Системный Будильник", "#81D4FA");
        hTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headerRow.addView(hTitle);

        Button openGameBtn = pillButton(ctx, "🎮 В игру", "#1B4D3E", "#69F0AE");
        openGameBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SystemAlarmAndNotifier.openIdleArcGame(ctx);
            }
        });
        headerRow.addView(openGameBtn);
        alarmCard.addView(headerRow);

        // Строка Боя с Боссом (с поддержкой системного будильника и статуса прогресса!)
        if (radar.bossFightReadyAtMs > now) {
            addTimerAlarmRow(ctx, alarmCard, "⚔️ Бой с Боссом",
                    false, radar.bossFightReadyAtMs, now, quietHours,
                    "boss", "⚔️ IdleArc: Откатился Бой с Боссом!", refreshCb);
        } else {
            LinearLayout bossRow = row(ctx);
            bossRow.setPadding(0, dp(ctx, 5), 0, dp(ctx, 5));
            LinearLayout bCol = new LinearLayout(ctx);
            bCol.setOrientation(LinearLayout.VERTICAL);
            bCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            bCol.addView(bodyText(ctx, "⚔️ Бой с Боссом", "#ECEFF1", 13f));
            bCol.addView(bodyText(ctx, radar.bossStatusNote,
                    radar.bossFightReady ? "#69F0AE" : "#90CAF9", 11.5f));
            bossRow.addView(bCol);

            bossRow.addView(badge(ctx, radar.bossFightReady ? "🔥 ГОТОВ" : (radar.bossStatusKnown ? "НЕДОСТУПЕН" : "НЕТ ДАННЫХ"),
                    radar.bossFightReady ? "#1B4332" : "#22303C",
                    radar.bossFightReady ? "#69F0AE" : "#90CAF9"));
            addSpace(ctx, bossRow, 5);
            final boolean autoBoss = SystemAlarmAndNotifier.isAutoAlarmEnabled(ctx, "boss");
            Button autoBossBtn = pillButton(ctx, autoBoss ? "Авто ✅" : "Авто ⬜",
                    autoBoss ? "#1B4332" : "#1C2833",
                    autoBoss ? "#69F0AE" : "#90A4AE");
            autoBossBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    SystemAlarmAndNotifier.setAutoAlarmEnabled(ctx, "boss", !autoBoss);
                    if (refreshCb != null) refreshCb.onRequestRefresh();
                }
            });
            bossRow.addView(autoBossBtn);
            alarmCard.addView(bossRow);
        }

        if (radar.bossFightReadyAtMs > now && radar.bossStatusNote.startsWith("Ручной")) {
            alarmCard.addView(bodyText(ctx, radar.bossStatusNote, "#90CAF9", 11f));
        }
        if (!bossHasServerDeadline) {
        Button startBossTimer = pillButton(ctx, "⏱ После боя: запустить 3 часа", "#1C2833", "#81D4FA");
        startBossTimer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                long started = System.currentTimeMillis();
                SystemAlarmAndNotifier.startManualBossTimer(ctx, started);
                if (SystemAlarmAndNotifier.isAutoAlarmEnabled(ctx, "boss")) {
                    RadarTimeParser.AlarmTarget target = RadarTimeParser.buildAlarmTarget(
                            "boss", "⚔️ IdleArc: Откатился Бой с Боссом!",
                            started + 3 * 60 * 60 * 1000L, started,
                            SystemAlarmAndNotifier.isQuietHoursEnabled(ctx));
                    SystemAlarmAndNotifier.scheduleSystemClockAlarm(ctx, target, false);
                }
                if (refreshCb != null) refreshCb.onRequestRefresh();
            }
        });
        alarmCard.addView(startBossTimer);
        }

        // Строка Подземелья
        addTimerAlarmRow(ctx, alarmCard, "🏰 Подземелье",
                radar.dungeonReady, radar.dungeonReadyAtMs, now, quietHours,
                "dungeon", "🏰 IdleArc: Подземелье откатилось!", refreshCb);

        // Строка Босса гильдии
        if (radar.guildBossReadyAtMs > now) {
            addTimerAlarmRow(ctx, alarmCard, "👹 Босс гильдии",
                    false, radar.guildBossReadyAtMs, now, quietHours,
                    "guild_boss", "👹 IdleArc: Откатился Босс гильдии!", refreshCb);
        } else {
            LinearLayout gbRow = row(ctx);
            gbRow.setPadding(0, dp(ctx, 5), 0, dp(ctx, 5));
            TextView gbLbl = bodyText(ctx, "👹 Босс гильдии", "#DCE7F0", 13f);
            gbLbl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            gbRow.addView(gbLbl);
            gbRow.addView(badge(ctx, radar.guildBossReady ? "🔥 БОЙ ОТКРЫТ" : "Откат",
                    radar.guildBossReady ? "#1B4332" : "#22303C",
                    radar.guildBossReady ? "#69F0AE" : "#90A4AE"));
            alarmCard.addView(gbRow);
        }

        // Строка Яйца питомца
        if (radar.petHatchReady || radar.petHatchAtMs > now) {
            addTimerAlarmRow(ctx, alarmCard, "🥚 Инкубатор питомца",
                    radar.petHatchReady, radar.petHatchAtMs, now, quietHours,
                    "hatch", "🥚 IdleArc: Яйцо питомца созрело!", refreshCb);
        }

        // Строка Временного буста
        if (radar.earliestBoostEndMs > now) {
            addTimerAlarmRow(ctx, alarmCard, "⚡ " + radar.earliestBoostName,
                    false, radar.earliestBoostEndMs, now, quietHours,
                    "boost", "⚡ IdleArc: Истекает " + radar.earliestBoostName, refreshCb);
        }

        // Тумблеры «Тихие часы» и «Пуш в шторку»
        LinearLayout togglesRow = row(ctx);
        togglesRow.setPadding(0, dp(ctx, 8), 0, 0);
        Button quietBtn = pillButton(ctx,
                quietHours ? "🌙 Тихие часы 00–08: ВКЛ" : "🌙 Тихие часы: ВЫКЛ",
                quietHours ? "#24364B" : "#1A242D",
                quietHours ? "#B3E5FC" : "#78909C");
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        qLp.rightMargin = dp(ctx, 6);
        quietBtn.setLayoutParams(qLp);
        quietBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SystemAlarmAndNotifier.setQuietHoursEnabled(ctx, !quietHours);
                if (refreshCb != null) refreshCb.onRequestRefresh();
            }
        });
        togglesRow.addView(quietBtn);

        final boolean smartPush = SystemAlarmAndNotifier.isSmartNotifsEnabled(ctx);
        Button pushBtn = pillButton(ctx,
                smartPush ? "🔔 Уведомления: ВКЛ" : "🔔 Уведомления: ВЫКЛ",
                smartPush ? "#1B3B2F" : "#1A242D",
                smartPush ? "#A5D6A7" : "#78909C");
        pushBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pushBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SystemAlarmAndNotifier.setSmartNotifsEnabled(ctx, !smartPush);
                if (refreshCb != null) refreshCb.onRequestRefresh();
            }
        });
        togglesRow.addView(pushBtn);
        alarmCard.addView(togglesRow);
        root.addView(alarmCard);

        // Вероятностный ресурс: сервер не отдаёт индивидуальный прогресс гаранта.
        CatalystTracker.State catalyst = CatalystTracker.analyze(dash,
                IdleArcApiClient.getCachedGameConfig(), CatalystTracker.getRate(ctx, now));
        LinearLayout catalystCard = makeCard(ctx, "#191B2D", "#AB80FF");
        catalystCard.addView(cardTitle(ctx, "✨ Астральный катализатор", "#D1B3FF"));
        catalystCard.addView(bodyText(ctx, "В наличии: "
                + (catalyst.count >= 0 ? catalyst.count + " шт." : "нет данных")
                + " · по последней синхронизации", "#FFFFFF", 13f));
        if (!catalyst.configKnown) {
            catalystCard.addView(bodyText(ctx, "Настройки шанса не загружены. Обнови API.", "#FFD54F", 12f));
        } else if (!catalyst.enabled) {
            catalystCard.addView(bodyText(ctx, "Механика катализатора отключена в настройках игры.", "#FFD54F", 12f));
        } else {
            catalystCard.addView(bodyText(ctx, String.format(Locale.US,
                    "Базовый шанс: %.4f%% за убийство подходящего монстра.", catalyst.baseChance * 100.0),
                    "#E1BEE7", 12.5f));
            if (catalyst.minLevel > 0) {
                catalystCard.addView(bodyText(ctx, "Минимальный уровень монстра: " + catalyst.minLevel
                        + (catalyst.currentLevel > 0 ? " · текущий эффективный: " + catalyst.currentLevel : " · текущий уровень неизвестен"),
                        "#B0BEC5", 12f));
            }
            if (catalyst.currentLevel > 0 && catalyst.minLevel > 0 && catalyst.currentLevel < catalyst.minLevel) {
                catalystCard.addView(bodyText(ctx, "Текущий уровень ниже необходимого для выпадения с монстров.", "#FFCC80", 12f));
            } else if (catalyst.meanHours > 0 && Double.isFinite(catalyst.meanHours)) {
                long estimatedMs = (long) Math.min(Long.MAX_VALUE, catalyst.meanHours * 3_600_000.0);
                catalystCard.addView(bodyText(ctx, "Ориентировочное среднее время: "
                        + RadarTimeParser.formatRemaining(estimatedMs) + ".", "#80DEEA", 12.5f));
                catalystCard.addView(bodyText(ctx, String.format(Locale.US,
                        "Темп прогресса этажа: %.0f/ч · базовая вероятность за час: %.1f%%",
                        catalyst.progressPerHour, catalyst.oneHourChance * 100.0), "#B3E5FC", 12f));
                catalystCard.addView(bodyText(ctx, "Это статистическая оценка при прежнем темпе, без учёта гаранта и неизвестных бонусов, а не точный таймер.", "#B0BEC5", 11.5f));
            } else if (catalyst.baseChance > 0) {
                catalystCard.addView(bodyText(ctx, "Для оценки времени обнови API, затем пофарми на том же этаже и обнови снова через 1–2 минуты.", "#B0BEC5", 12f));
            }
            if (catalyst.pityKills > 0) {
                catalystCard.addView(bodyText(ctx, "Порог гаранта в настройках: " + catalyst.pityKills
                        + " убийств. Текущий прогресс гаранта сервер не сообщает.", "#B0BEC5", 11.5f));
            }
            catalystCard.addView(bodyText(ctx, "Выпадение случайно: точное время следующего катализатора неизвестно.", "#90A4AE", 11.5f));
        }
        root.addView(catalystCard);

        // 2. Живой монитор текущего боя и этажа
        ApiFeatureAnalyzers.CombatStatus cs = ApiFeatureAnalyzers.analyzeCombat(dash, profile);
        if (cs != null) {
            LinearLayout combatCard = makeCard(ctx, "#121C24", cs.isRespawning ? "#FF5252" : "#2E4C63");
            combatCard.addView(cardTitle(ctx, "⚔️ Монитор текущего боя и этажа", "#90CAF9"));

            LinearLayout chips = row(ctx);
            chips.addView(badge(ctx, "🗺️ " + cs.biomeName, "#1B2A36", "#B3E5FC"));
            addSpace(ctx, chips, 6);
            chips.addView(badge(ctx, "Этаж " + cs.selectedMonsterLevel + " / " + cs.monsterLevel,
                    "#1E352F", "#80CBC4"));
            if (cs.floorPaused) {
                addSpace(ctx, chips, 6);
                chips.addView(badge(ctx, "⏸ ПАУЗА", "#3E2723", "#FFCC80"));
            }
            combatCard.addView(chips);

            combatCard.addView(labeledProgressBar(ctx, "Здоровье героя",
                    String.format(Locale.US, "%.0f%%", cs.heroHpPct),
                    cs.heroHpPct, cs.heroHpPct > 60 ? "#4CAF50" : "#FF7043"));
            combatCard.addView(labeledProgressBar(ctx, "Прогресс волны этажа",
                    String.format(Locale.US, "%.0f%%", cs.floorProgressPct),
                    cs.floorProgressPct, "#29B6F6"));

            if (cs.monsterMaxHp > 0) {
                combatCard.addView(bodyText(ctx, String.format(Locale.US,
                        "👾 Моб: %s (HP %s · ~%.1f ударов питомца)",
                        cs.monsterName, RuGameDictionary.fmtBig(cs.monsterMaxHp), cs.estimatedPetHitsToKill),
                        "#B0BEC5", 12f));
            }
            combatCard.addView(bodyText(ctx, cs.recommendation, "#E1F5FE", 12.5f));
            root.addView(combatCard);
        }

        // Считаем изменение баланса только между двумя успешно полученными слепками.
        SnapshotHistoryTracker.SessionRates rates = SnapshotHistoryTracker.getSessionRates(ctx, dash);
        LinearLayout farmCard = makeCard(ctx, "#121C24", "#2C4237");
        LinearLayout fHead = row(ctx);
        TextView fTitle = cardTitle(ctx, "📈 Фарм-сессия: изменение баланса /ч", "#A5D6A7");
        fTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        fHead.addView(fTitle);
        Button resetBtn = pillButton(ctx, "🔄 Сброс замера", "#1E2D38", "#B0BEC5");
        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SnapshotHistoryTracker.requestFarmSessionReset(ctx);
                if (refreshCb != null) {
                    refreshCb.onRequestRefresh();
                    refreshCb.onRequestSync();
                }
            }
        });
        fHead.addView(resetBtn);
        farmCard.addView(fHead);

        SnapshotHistoryTracker.Observation observed = SnapshotHistoryTracker.getObservation(IdleArcApiClient.getLastSyncTimeMs());
        if (observed != null) {
            farmCard.addView(bodyText(ctx, String.format(Locale.US,
                    "Последнее изменение за %.1f с: 🪙 %s · ✨ %s · 🐾 %s · 💎 %s",
                    (observed.toMs - observed.fromMs) / 1000.0, observedDelta(observed.gold),
                    observedDelta(observed.dust), observedDelta(observed.shards), observedDelta(observed.rubies)), "#80CBC4", 11.5f));
        } else {
            farmCard.addView(bodyText(ctx, "Для последнего изменения нужны два полученных ответа API.", "#90A4AE", 11.5f));
        }
        if (SnapshotHistoryTracker.isFarmResetPending(ctx)) {
            farmCard.addView(bodyText(ctx, "Ожидаю свежий ответ API для начала нового замера.", "#90A4AE", 12f));
        } else if (!rates.active) {
            farmCard.addView(bodyText(ctx,
                    "Среднее появится после двух полученных ответов с интервалом не менее 30 секунд. Для стабильности подожди 1–2 минуты.",
                    "#90A4AE", 12f));
        } else {
            long syncTime = IdleArcApiClient.getLastSyncTimeMs();
            String syncLabel = syncTime > 0 ? new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date(syncTime)) : "—";
            farmCard.addView(bodyText(ctx, String.format(Locale.US, "Между снимками: %.1f мин · последнее обновление: %s", rates.elapsedMinutes, syncLabel), "#80CBC4", 11.5f));
            LinearLayout g1 = row(ctx);
            g1.addView(statTile(ctx, "🪙 Золото/ч", farmRateText(rates.goldPerHour, false), "#FFD54F"));
            addSpace(ctx, g1, 6);
            g1.addView(statTile(ctx, "✨ Пыль/ч", farmRateText(rates.dustPerHour, false), "#81D4FA"));
            farmCard.addView(g1);
            LinearLayout g2 = row(ctx);
            g2.setPadding(0, dp(ctx, 6), 0, 0);
            g2.addView(statTile(ctx, "🐾 Осколки/ч", farmRateText(rates.shardsPerHour, false), "#CE93D8"));
            addSpace(ctx, g2, 6);
            g2.addView(statTile(ctx, "💎 Рубины/ч", farmRateText(rates.rubiesPerHour, true), "#FF8A80"));
            addSpace(ctx, g2, 6);
            g2.addView(statTile(ctx, "🆙 Уровней/ч", farmRateText(rates.levelsPerHour, true), "#69F0AE"));
            farmCard.addView(g2);
            farmCard.addView(bodyText(ctx, "Изменения — разница ответов API, не игровые тики и не чистый дроп: включены траты, покупки и награды. Минус — уменьшение запаса. Уровни/ч приблизительны из-за округления.", "#B0BEC5", 11.5f));
            farmCard.addView(bodyText(ctx, "Значения пересчитываются только после обновления API; — означает, что данных недостаточно.", "#90A4AE", 11f));
        }
        root.addView(farmCard);

        // 4. Активные сигналы радара аккаунта
        LinearLayout sigCard = makeCard(ctx, "#121C24", radar.activeAlerts.isEmpty() ? "#263238" : "#FFB74D");
        sigCard.addView(cardTitle(ctx, "📡 Сигналы аккаунта и талисманов", "#FFE082"));
        sigCard.addView(bodyText(ctx, String.format(Locale.US,
                "🧿 Попыток талисмана сегодня: %d · Счётчик гаранта (Pity): %d",
                radar.talismanDailyRemaining, radar.talismanPity), "#CFD8DC", 12f));
        if (radar.activeAlerts.isEmpty()) {
            sigCard.addView(bodyText(ctx, "✅ Забытых очков, писем и несобранных наград нет.", "#81C784", 12.5f));
        } else {
            for (String al : radar.activeAlerts) {
                sigCard.addView(bulletLine(ctx, al, "#FFF59D"));
            }
        }
        root.addView(sigCard);
    }

    private static String observedDelta(java.math.BigDecimal value) {
        if (value == null) return "—";
        String text = value.stripTrailingZeros().toPlainString();
        return value.signum() > 0 ? "+" + text : text;
    }

    private static String farmRateText(double value, boolean precise) {
        if (!Double.isFinite(value)) return "—";
        String sign = value > 0 ? "+" : "";
        if (value != 0 && Math.abs(value) < 0.01) return sign + String.format(Locale.US, "%.3g", value);
        if ((precise && Math.abs(value) < 1000) || (value != 0 && Math.abs(value) < 1))
            return sign + String.format(Locale.US, "%.2f", value);
        return sign + RuGameDictionary.fmtBig(value);
    }

    private static void addTimerAlarmRow(final Context ctx,
                                         LinearLayout card,
                                         String title,
                                         boolean isReady,
                                         long deadlineMs,
                                         long nowMs,
                                         boolean quietHours,
                                         final String eventKey,
                                         final String alarmLabel,
                                         final UiRefreshCallback refreshCb) {
        LinearLayout r = row(ctx);
        r.setPadding(0, dp(ctx, 5), 0, dp(ctx, 5));

        LinearLayout leftCol = new LinearLayout(ctx);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        leftCol.addView(bodyText(ctx, title, "#ECEFF1", 13f));

        final RadarTimeParser.AlarmTarget target = (!isReady && deadlineMs > nowMs)
                ? RadarTimeParser.buildAlarmTarget(eventKey, alarmLabel, deadlineMs, nowMs, quietHours)
                : null;

        if (isReady) {
            leftCol.addView(bodyText(ctx, "✅ Готово прямо сейчас!", "#69F0AE", 11.5f));
        } else if (target != null) {
            leftCol.addView(bodyText(ctx, "Через " + target.remainingText + " (звонок в " + target.formattedTime + ")",
                    "#90CAF9", 11.5f));
        } else {
            leftCol.addView(bodyText(ctx, "На откате", "#90A4AE", 11.5f));
        }
        r.addView(leftCol);

        if (target != null) {
            Button setBtn = pillButton(ctx, "⏰ " + target.formattedTime, "#2A3B1E", "#FFD54F");
            setBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    SystemAlarmAndNotifier.scheduleSystemClockAlarm(ctx, target, true);
                }
            });
            r.addView(setBtn);
            addSpace(ctx, r, 5);
        }

        final boolean autoOn = SystemAlarmAndNotifier.isAutoAlarmEnabled(ctx, eventKey);
        Button autoBtn = pillButton(ctx, autoOn ? "Авто ✅" : "Авто ⬜",
                autoOn ? "#1B4332" : "#1C2833",
                autoOn ? "#69F0AE" : "#90A4AE");
        autoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SystemAlarmAndNotifier.setAutoAlarmEnabled(ctx, eventKey, !autoOn);
                if (!autoOn && target != null) {
                    SystemAlarmAndNotifier.scheduleSystemClockAlarm(ctx, target, true);
                }
                if (refreshCb != null) refreshCb.onRequestRefresh();
            }
        });
        r.addView(autoBtn);
        card.addView(r);
    }

    // =========================================================================
    // ВКЛАДКА 1: ГЕРОЙ, ГРАФИЧЕСКИЕ КАПЫ, ОВЕРКАП КРИТА, 16 КОРЗИН И ПЛАН
    // =========================================================================
    public static void renderHeroSummaryTab(Context ctx,
                                            LinearLayout root,
                                            Map<String, Object> dash,
                                            DruidProfile p) {
        root.removeAllViews();
        if (dash == null || p == null) {
            root.addView(emptySyncCard(ctx));
            return;
        }
        HeroClassProfile.HeroClass hc = HeroClassProfile.resolveHeroClass(dash, p);
        Map<String, Object> bs = SimpleJson.asMap(dash.get("bonusStats"));
        double petAvg = bs != null ? SimpleJson.num(bs, "petDamageFinalAvg", 0.0) : 0.0;
        double trueMultM = bs != null ? SimpleJson.num(bs, "petDamageTrueMultiplier", 0.0) / 1_000_000.0 : 0.0;
        double heroAvgAtk = 0.5 * (p.minAtk + p.maxAtk);
        int gs = SimpleJson.intVal(dash, "gear_score", 0);
        double lvlProg = SimpleJson.num(dash, "levelProgress", 0.0);
        String stance = RuGameDictionary.stanceRu(SimpleJson.str(dash, "activeCombatStance", ""));

        // 1. Паспорт персонажа (Все 5 классов)
        LinearLayout heroCard = makeCard(ctx, "#121E29", "#4DD0E1");
        LinearLayout topRow = row(ctx);
        String modeMark = (p.overrideClassId >= 1 && p.overrideClassId <= 5) ? (" [просмотр: " + hc.ruName + "]") : "";
        TextView nameTv = cardTitle(ctx, hc.icon + " " + SimpleJson.str(dash, "name", hc.ruName)
                + " · " + hc.ruName + " Ур. " + p.charLevel + modeMark, "#80DEEA");
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topRow.addView(nameTv);
        topRow.addView(badge(ctx, "GS " + gs, "#263849", "#FFD54F"));
        heroCard.addView(topRow);

        heroCard.addView(bodyText(ctx, "Стойка: " + stance + " · Все навыки: +" + p.bonusAllClassSkills, "#B0BEC5", 12f));
        heroCard.addView(labeledProgressBar(ctx, "Опыт до ур. " + (p.charLevel + 1),
                String.format(Locale.US, "%.1f%%", lvlProg), lvlProg, "#26C6DA"));

        LinearLayout kpiRow = row(ctx);
        kpiRow.setPadding(0, dp(ctx, 6), 0, 0);
        if (hc == HeroClassProfile.HeroClass.DRUID) {
            kpiRow.addView(statTile(ctx, "🐾 Ср. удар пета", RuGameDictionary.fmtBig(petAvg), "#69F0AE"));
            addSpace(ctx, kpiRow, 6);
            kpiRow.addView(statTile(ctx, "✖ Множитель", String.format(Locale.US, "×%.2fM", trueMultM), "#FFD54F"));
            addSpace(ctx, kpiRow, 6);
            kpiRow.addView(statTile(ctx, "🧠 Инт / Ловк",
                    Math.round(p.intelligence) + " / " + Math.round(p.agility), "#81D4FA"));
        } else {
            kpiRow.addView(statTile(ctx, "⚔️ Атака героя", RuGameDictionary.fmtBig(heroAvgAtk), "#69F0AE"));
            addSpace(ctx, kpiRow, 6);
            kpiRow.addView(statTile(ctx, "💪 Сил / Инт",
                    Math.round(p.strength) + " / " + Math.round(p.intelligence), "#FFD54F"));
            addSpace(ctx, kpiRow, 6);
            kpiRow.addView(statTile(ctx, "⚡ Дв.уд / Дв.ур",
                    Math.round(p.doubleHitChance) + "% / " + Math.round(p.doubleDamageChance) + "%", "#81D4FA"));
        }
        heroCard.addView(kpiRow);
        root.addView(heroCard);

        root.addView(stanceGuideCard(ctx, dash, "hero"));

        LinearLayout clsCard = makeCard(ctx, "#141D2B", "#7E57C2");
        clsCard.addView(cardTitle(ctx, hc.icon + " Профиль класса «" + hc.ruName + "» и главный Бонус дропа", "#D1C4E9"));
        clsCard.addView(bodyText(ctx, HeroClassProfile.buildClassGuide(hc, p.mode), "#EDE7F6", 12f));
        root.addView(clsCard);

        // 2. Графические прогресс-бары капов + Конверсия оверкапа крита
        LinearLayout capsCard = makeCard(ctx, "#121C24", "#37474F");
        capsCard.addView(cardTitle(ctx, "🎯 Ключевые капы и насыщение статов", "#B3E5FC"));

        capsCard.addView(labeledProgressBar(ctx, "Шанс крита (кап 100%)",
                String.format(Locale.US, "%.1f%% / 100%%", p.critChance),
                Math.min(100.0, p.critChance), p.critChance > 102.0 ? "#FFB74D" : "#4CAF50"));

        capsCard.addView(labeledProgressBar(ctx, "Крит. урон (насыщение >1500%)",
                String.format(Locale.US, "%.0f%% / 1500%%", p.critDamage),
                Math.min(100.0, p.critDamage / 15.0), p.critDamage > 1500.0 ? "#FFCA28" : "#4FC3F7"));

        capsCard.addView(labeledProgressBar(ctx, "Снижение урона (кап 95%)",
                String.format(Locale.US, "%.1f%% (сырое %.1f%%)", p.damageReductionEff, p.damageReductionRaw),
                (p.damageReductionEff / 95.0) * 100.0,
                p.damageReductionEff >= 94.9 ? "#4CAF50" : "#FF5252"));

        capsCard.addView(labeledProgressBar(ctx, "Уклонение (кап 95%)",
                String.format(Locale.US, "%.1f%% (сырое %.1f%%)", p.dodgeEff, p.dodgeRaw),
                (p.dodgeEff / 95.0) * 100.0,
                p.dodgeEff >= 94.9 ? "#4CAF50" : "#FFA726"));

        capsCard.addView(labeledProgressBar(ctx, "Двойной удар (до 100%)",
                String.format(Locale.US, "%.1f%%", p.doubleHitChance), Math.min(100.0, p.doubleHitChance), "#AB47BC"));

        capsCard.addView(labeledProgressBar(ctx, "Двойной урон (до 100%)",
                String.format(Locale.US, "%.1f%%", p.doubleDamageChance), Math.min(100.0, p.doubleDamageChance), "#EC407A"));

        String critNote = ApiFeatureAnalyzers.buildCritOvercapExplanation(dash, p);
        if (!critNote.isEmpty()) {
            capsCard.addView(bodyText(ctx, "💡 " + critNote, "#FFE082", 11.5f));
        }
        root.addView(capsCard);

        // 3. Сворачиваемая карточка: 16 серверных корзин урона питомца
        if (bs != null) {
            List<Object> bd = SimpleJson.asList(bs.get("petDamageBreakdown"));
            if (bd != null && !bd.isEmpty()) {
                LinearLayout bucketsBody = new LinearLayout(ctx);
                bucketsBody.setOrientation(LinearLayout.VERTICAL);
                for (Object bo : bd) {
                    Map<String, Object> bm = SimpleJson.asMap(bo);
                    if (bm == null) continue;
                    String bKey = SimpleJson.str(bm, "key", "");
                    String ruLabel = RuGameDictionary.damageBucketRu(bKey, SimpleJson.str(bm, "label", bKey));
                    double pct = SimpleJson.num(bm, "pct", 0.0);
                    double mult = 1.0 + pct / 100.0;
                    double barFill = Math.min(100.0, Math.log10(Math.max(1.0, mult)) * 45.0);
                    bucketsBody.addView(labeledProgressBar(ctx, ruLabel,
                            String.format(Locale.US, "+%.0f%% (×%.2f)", pct, mult),
                            barFill, pct < 80 ? "#FFD54F" : "#4DD0E1"));
                }
                root.addView(collapsibleCard(ctx, "🧮 Все 16 множителей урона пета (нажми, чтобы развернуть)",
                        bucketsBody, false));
            }
        }

        // 4. Приоритетный план действий под выбранный режим
        LinearLayout planCard = makeCard(ctx, "#13221C", "#4CAF50");
        planCard.addView(cardTitle(ctx, "🚀 Главные точки роста под «" + p.mode.title + "»", "#81C784"));
        List<ApiDashboardEngine.ServerItemAudit> eqItems = ApiDashboardEngine.analyzeAllEquippedItems(dash, p);
        int shown = 0;
        for (ApiDashboardEngine.ServerItemAudit it : eqItems) {
            if (it.totalPotentialGainPct > 0.25 && shown < 5) {
                shown++;
                planCard.addView(bulletLine(ctx, String.format(Locale.US,
                        "%s (%s, T%d +%d): потенциал +%.1f%% (%d поз. на перековку)",
                        it.slotLabel, it.itemName, it.tier, it.tierLevel,
                        it.totalPotentialGainPct, it.rerollsNeeded), "#E8F5E9"));
            }
        }
        List<String> freeSwaps = ApiFeatureAnalyzers.findFreeGemSwaps(dash, p);
        for (String sw : freeSwaps) {
            if (shown >= 6) break;
            shown++;
            planCard.addView(bulletLine(ctx, sw, "#B2FF59"));
        }
        if (shown == 0) {
            planCard.addView(bodyText(ctx, "✅ Все 10 надетых предметов и камни отлично сбалансированы под текущий режим!", "#A5D6A7", 12.5f));
        }
        root.addView(planCard);
    }

    // =========================================================================
    // ВКЛАДКА 2: 10 НАДЕТЫХ ПРЕДМЕТОВ + «ПРИМЕРОЧНАЯ СТАТОВ» (WHAT-IF)
    // =========================================================================
    public static void renderEquipmentTab(final Context ctx,
                                          LinearLayout root,
                                          final Map<String, Object> dash,
                                          final DruidProfile p,
                                          ApiDashboardEngine.EquippedSlotKey filterKey) {
        root.removeAllViews();
        if (dash == null || p == null) {
            root.addView(emptySyncCard(ctx));
            return;
        }
        root.addView(stanceGuideCard(ctx, dash, "equipment"));
        final boolean uncertainHarmony = "harmony".equals(StanceAdvisor.stance(dash));
        List<ApiDashboardEngine.ServerItemAudit> audits = ApiDashboardEngine.analyzeAllEquippedItems(dash, p);
        for (final ApiDashboardEngine.ServerItemAudit ia : audits) {
            if (filterKey != null && filterKey != ApiDashboardEngine.EquippedSlotKey.ALL && ia.slotKey != filterKey) {
                continue;
            }
            String tierHex = RuGameDictionary.tierColorHex(ia.tier);
            LinearLayout card = makeCard(ctx, "#121B24", tierHex);

            // Шапка карточки предмета
            LinearLayout top = row(ctx);
            TextView title = cardTitle(ctx, ia.slotLabel + " — " + ia.itemName, "#ECEFF1");
            title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(title);
            top.addView(badge(ctx, "T" + ia.tier + " +" + ia.tierLevel + "/" + ia.maxTierLevel, "#1F2E3D", tierHex));
            addSpace(ctx, top, 4);
            top.addView(badge(ctx, "A" + ia.awakenLevel, "#2B213A", "#CE93D8"));
            card.addView(top);

            String subMeta = "Ур. предмета " + ia.itemLevel + " · " + RuGameDictionary.tierTitleRu(ia.tier)
                    + (ia.gildLevel > 0 ? (" · 🏅 Позолота " + ia.gildLevel) : "");
            card.addView(bodyText(ctx, subMeta, "#90A4AE", 11.5f));

            if (!ia.implicitText.isEmpty() || !ia.dropBonusText.isEmpty()) {
                String impLine = ia.implicitText + (!ia.dropBonusText.isEmpty() ? (" · 🎁 " + ia.dropBonusText) : "");
                card.addView(bodyText(ctx, "База: " + impLine, "#80DEEA", 12f));
            }

            // 5 визуальных строк аффиксов с прогресс-барами качества ролла
            for (ApiDashboardEngine.ServerPositionEval pe : ia.positions) {
                LinearLayout posBox = makeCard(ctx, uncertainHarmony ? "#15232B" : pe.keepStat ? "#10211B" : "#241B15",
                        uncertainHarmony ? "#546E7A" : pe.keepStat ? "#2E7D32" : "#EF6C00");
                posBox.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));

                LinearLayout pTop = row(ctx);
                String lockMark = pe.isLocked ? " 🔒" : "";
                String awkMark = pe.boostCount > 0 ? (" ★×" + pe.boostCount) : "";
                TextView pStat = bodyText(ctx,
                        "#" + pe.index + " " + pe.currentType.taggedName() + " +"
                                + Math.round(pe.currentValue) + (pe.currentType.isPercent ? "%" : "")
                                + lockMark + awkMark,
                        "#FFFFFF", 12.5f);
                pStat.setTypeface(Typeface.DEFAULT_BOLD);
                pStat.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                pTop.addView(pStat);

                if (uncertainHarmony) {
                    pTop.addView(badge(ctx, "⚖ СРАВНИТЬ", "#243A42", "#B0BEC5"));
                } else if (pe.keepStat) {
                    pTop.addView(badge(ctx, "✅ ОСТАВИТЬ", "#1B4332", "#69F0AE"));
                } else {
                    pTop.addView(badge(ctx, String.format(Locale.US, "🔄 +%.1f%%", Math.max(0.1, pe.netUpgradePct)),
                            "#4E2A14", "#FFCC80"));
                }
                posBox.addView(pTop);

                int qPct = (int) Math.round(pe.rollQualityPct);
                String rangeInfo = String.format(Locale.US, "[%.0f..%.0f] · Ролл %d%%",
                        pe.rangeMin, pe.rangeMax, qPct);
                posBox.addView(labeledProgressBar(ctx, "Качество ролла", rangeInfo, qPct,
                        qPct >= 80 ? "#4CAF50" : (qPct >= 50 ? "#29B6F6" : "#FFA726")));

                if (!uncertainHarmony && !pe.keepStat && pe.recommendedType != null) {
                    posBox.addView(bodyText(ctx, "👉 Лучшая замена: " + pe.recommendedType.taggedName(),
                            "#FFD54F", 11.5f));
                }
                card.addView(posBox);
            }

            if (uncertainHarmony) {
                String suggestion = HarmonyAffixGuide.candidate(ia, stanceScenario(ctx, dash));
                if (!suggestion.isEmpty()) card.addView(bodyText(ctx, suggestion, "#FFE082", 12f));
            }

            if (!uncertainHarmony && ia.awakenAdvice != null && !ia.awakenAdvice.isEmpty()) {
                card.addView(bodyText(ctx, ia.awakenAdvice, "#CE93D8", 11.5f));
            }

            // Кнопка интерактивной «Примерочной статов» (What-If)
            LinearLayout btnRow = row(ctx);
            btnRow.setPadding(0, dp(ctx, 6), 0, 0);
            Button whatIfBtn = pillButton(ctx, "🧪 Примерочная статов (What-If)", "#1E3348", "#81D4FA");
            whatIfBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showWhatIfDialog(ctx, dash, p, ia);
                }
            });
            btnRow.addView(whatIfBtn);
            if (!uncertainHarmony) card.addView(btnRow);
            root.addView(card);
        }
    }

    private static void showWhatIfDialog(final Context ctx,
                                         final Map<String, Object> dash,
                                         final DruidProfile p,
                                         final ApiDashboardEngine.ServerItemAudit item) {
        if (ctx == null || item == null || item.positions.isEmpty()) return;
        try {
            final String[] posLabels = new String[item.positions.size()];
            for (int i = 0; i < item.positions.size(); i++) {
                ApiDashboardEngine.ServerPositionEval pe = item.positions.get(i);
                posLabels[i] = "Позиция #" + pe.index + ": " + pe.currentType.ruName
                        + " (+" + Math.round(pe.currentValue) + (pe.currentType.isPercent ? "%" : "") + ")";
            }
                AlertDialog dlg = new AlertDialog.Builder(ctx)
                    .setTitle("🧪 Примерочная: выбери строку на «" + item.slotLabel + "»")
                    .setItems(posLabels, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int whichPos) {
                            final int posIdx = item.positions.get(whichPos).index;
                            showWhatIfStatPicker(ctx, dash, p, item, posIdx);
                        }
                    })
                    .setNegativeButton("Закрыть", null)
                    .create();
            showSafeDialog(ctx, dlg);
        } catch (Exception ignored) {
        }
    }

    private static void showWhatIfStatPicker(final Context ctx,
                                             final Map<String, Object> dash,
                                             final DruidProfile p,
                                             final ApiDashboardEngine.ServerItemAudit item,
                                             final int posOneBased) {
        final AffixPositionOptimizer.AffixType[] candidates = {
            AffixPositionOptimizer.AffixType.STRENGTH,
            AffixPositionOptimizer.AffixType.INTELLIGENCE,
            AffixPositionOptimizer.AffixType.DEXTERITY,
            AffixPositionOptimizer.AffixType.ADDED_DAMAGE,
            AffixPositionOptimizer.AffixType.PET_DAMAGE,
            AffixPositionOptimizer.AffixType.DODGE_CHANCE,
            AffixPositionOptimizer.AffixType.DAMAGE_REDUCTION,
            AffixPositionOptimizer.AffixType.DOUBLE_HIT,
            AffixPositionOptimizer.AffixType.DOUBLE_DAMAGE,
            AffixPositionOptimizer.AffixType.CRIT_DAMAGE,
            AffixPositionOptimizer.AffixType.CRIT_CHANCE,
            AffixPositionOptimizer.AffixType.MAX_HEALTH,
            AffixPositionOptimizer.AffixType.DEFENSE_PERCENT,
            AffixPositionOptimizer.AffixType.EXP_GAIN,
            AffixPositionOptimizer.AffixType.GOLD_GAIN
        };
        String[] labels = new String[candidates.length];
        for (int i = 0; i < candidates.length; i++) {
            labels[i] = candidates[i].taggedName() + " (топ-ролл 90%)";
        }
        AlertDialog picker = new AlertDialog.Builder(ctx)
                .setTitle("Какой стат примерить в Позицию #" + posOneBased + "?")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int idx) {
                        String res85 = ApiFeatureAnalyzers.simulateWhatIfChange(
                                dash, p, item, posOneBased, candidates[idx], 0.90);
                        String res60 = ApiFeatureAnalyzers.simulateWhatIfChange(
                                dash, p, item, posOneBased, candidates[idx], 0.60);
                        AlertDialog resDlg = new AlertDialog.Builder(ctx)
                                .setTitle("📊 Результат примерки")
                                .setMessage(res85 + "\n\n— При среднем ролле 60% —\n" + res60)
                                .setPositiveButton("Отлично", null)
                                .create();
                        showSafeDialog(ctx, resDlg);
                    }
                })
                .setNegativeButton("Назад", null)
                .create();
        showSafeDialog(ctx, picker);
    }

    private static void showSafeDialog(Context ctx, AlertDialog dlg) {
        if (dlg == null) return;
        if (!(ctx instanceof android.app.Activity) && dlg.getWindow() != null) {
            int type = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                    ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : android.view.WindowManager.LayoutParams.TYPE_PHONE;
            dlg.getWindow().setType(type);
        }
        dlg.show();
    }

    // =========================================================================
    // ВКЛАДКА 3: ПОЛНАЯ СУМКА (ПРЕДМЕТЫ ПО БОНУСУ ДРОПА + САМОЦВЕТЫ) И ROI
    // =========================================================================
    public static void renderBagAndResourcesTab(final Context ctx,
                                                final LinearLayout root,
                                                final Map<String, Object> dash,
                                                final DruidProfile p) {
        root.removeAllViews();
        if (dash == null || p == null) {
            root.addView(emptySyncCard(ctx));
            return;
        }
        root.addView(stanceGuideCard(ctx, dash, "bag"));

        // 1. ПОЛНЫЙ РАЗБОР ИНВЕНТАРЯ ПРЕДМЕТОВ (/api/inventory) СТРОГО ПО БОНУСУ ДРОПА И КЛАССУ
        final BagAndPetAdvisor.InventoryAuditSummary invSum = BagAndPetAdvisor.evaluateInventoryItems(
                dash, IdleArcApiClient.getCachedInventoryItems(), p);
        LinearLayout invCard = makeCard(ctx, "#101B25", "#29B6F6");
        String countHdr = invSum.serverTotalCount > invSum.totalItems
                ? ("показано " + invSum.totalItems + " уникальных из " + invSum.serverTotalCount + " в рюкзаке")
                : (invSum.totalItems + " шт. в рюкзаке");
        invCard.addView(cardTitle(ctx,
                "🎒 Сумка предметов (" + countHdr + ") — класс «" + invSum.heroClass.ruName + "»",
                "#81D4FA"));
        invCard.addView(bodyText(ctx,
                "Оценка ориентировочная: не учитывает полный вклад предмета в баланс и DPS. Перед разбором обязательно проверь предмет и пресеты в игре.",
                "#B0BEC5", 11.5f));
        if (!invSum.dropBonusSummaryLine.isEmpty()) {
            invCard.addView(bodyText(ctx, "📊 Бонусы дропа в сумке: " + invSum.dropBonusSummaryLine, "#FFE082", 11.5f));
        }

        LinearLayout invKpi = row(ctx);
        invKpi.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6));
        invKpi.addView(statTile(ctx, "📦 В рюкзаке", String.valueOf(invSum.serverTotalCount), "#B3E5FC"));
        addSpace(ctx, invKpi, 6);
        invKpi.addView(statTile(ctx, "🔥 Оставить", String.valueOf(invSum.keepCount), "#69F0AE"));
        addSpace(ctx, invKpi, 6);
        invKpi.addView(statTile(ctx, "🗑️ Уничтожить", String.valueOf(invSum.salvageCount), "#FF8A80"));
        invCard.addView(invKpi);

        // Кнопки быстрого фильтра Сумки предметов (включая фильтр Неистовства и Удачи!)
        LinearLayout invFilterRow = row(ctx);
        invFilterRow.setPadding(0, 0, 0, dp(ctx, 6));
        String[] fTitles = {
            "Все (" + invSum.totalItems + ")",
            "🍀 Неист./Удача",
            "🔥 Оставить (" + invSum.keepCount + ")",
            "🗑️ Сломать (" + invSum.salvageCount + ")"
        };
        for (int fi = 0; fi < 4; fi++) {
            final int fIdx = fi;
            boolean sel = (activeItemBagFilter == fi);
            Button fb = pillButton(ctx, fTitles[fi],
                    sel ? "#1E4660" : "#16222E",
                    sel ? "#FFFFFF" : "#90A4AE");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (fi < 3) lp.rightMargin = dp(ctx, 4);
            fb.setLayoutParams(lp);
            fb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activeItemBagFilter = fIdx;
                    itemBagPage = 0;
                    renderBagAndResourcesTab(ctx, root, dash, p);
                }
            });
            invFilterRow.addView(fb);
        }
        invCard.addView(invFilterRow);

        if (IdleArcApiClient.getCachedInventoryTotalCount() > IdleArcApiClient.getCachedInventoryItems().size()) {
            invCard.addView(bodyText(ctx,
                    "⚠️ Сервер вернул не все предметы: загружено " + IdleArcApiClient.getCachedInventoryItems().size()
                    + " из " + IdleArcApiClient.getCachedInventoryTotalCount()
                    + ". Остальные страницы пока недоступны; рекомендации основаны только на загруженных вещах.",
                    "#FFD54F", 12f));
        }

        if (invSum.entries.isEmpty()) {
            invCard.addView(bodyText(ctx,
                    "Нажми «🔄 Обновить API», чтобы загрузить твой инвентарь (/api/inventory).",
                    "#FFD54F", 12f));
        } else {
            final List<BagAndPetAdvisor.BagItemEntry> filteredItems = new ArrayList<>();
            for (BagAndPetAdvisor.BagItemEntry ie : invSum.entries) {
                if (activeItemBagFilter == 1 && !("frenzy".equals(ie.dropBonusSlug) || "lucky".equals(ie.dropBonusSlug)
                        || "scavengers".equals(ie.dropBonusSlug) || ie.action == BagAndPetAdvisor.ItemBagAction.KEEP_FARM_PRESET)) continue;
                if (activeItemBagFilter == 2 && ie.action == BagAndPetAdvisor.ItemBagAction.SALVAGE_DESTROY) continue;
                if (activeItemBagFilter == 3 && ie.action != BagAndPetAdvisor.ItemBagAction.SALVAGE_DESTROY) continue;
                filteredItems.add(ie);
            }
            final int pageCount = Math.max(1, (filteredItems.size() + ITEM_BAG_PAGE_SIZE - 1) / ITEM_BAG_PAGE_SIZE);
            itemBagPage = Math.max(0, Math.min(itemBagPage, pageCount - 1));
            int startIndex = itemBagPage * ITEM_BAG_PAGE_SIZE;
            int endIndex = Math.min(startIndex + ITEM_BAG_PAGE_SIZE, filteredItems.size());
            for (int i = startIndex; i < endIndex; i++) {
                BagAndPetAdvisor.BagItemEntry ie = filteredItems.get(i);
                LinearLayout iBox = makeCard(ctx, "#0C151D", ie.action.fgHex);
                iBox.setPadding(dp(ctx, 9), dp(ctx, 7), dp(ctx, 9), dp(ctx, 7));

                LinearLayout iTop = row(ctx);
                TextView iTitle = bodyText(ctx, ie.slotRu + " — " + ie.itemName + (ie.locked ? " 🔒" : ""), "#FFFFFF", 12.5f);
                iTitle.setTypeface(Typeface.DEFAULT_BOLD);
                iTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                iTop.addView(iTitle);
                iTop.addView(badge(ctx, "T" + ie.tier + (ie.tierLevel > 0 ? " +" + ie.tierLevel : ""),
                        "#1C2A38", RuGameDictionary.tierColorHex(ie.tier)));
                iBox.addView(iTop);

                LinearLayout dropRow = row(ctx);
                dropRow.setPadding(0, dp(ctx, 3), 0, dp(ctx, 3));
                String dropTxt = ie.dropBonusValue > 0
                        ? ("🎁 Бонус дропа: " + ie.dropBonusRu + " +" + String.format(Locale.US, "%.1f%%", ie.dropBonusValue))
                        : "🎁 Без бонуса дропа";
                TextView dropTv = bodyText(ctx, dropTxt, "#80DEEA", 12f);
                dropTv.setTypeface(Typeface.DEFAULT_BOLD);
                dropTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                dropRow.addView(dropTv);
                dropRow.addView(badge(ctx, ie.action.badgeText, ie.action.bgHex, ie.action.fgHex));
                iBox.addView(dropRow);

                if (!ie.implicitSummary.isEmpty()) {
                    iBox.addView(bodyText(ctx, "База: " + ie.implicitSummary + " · Ур. " + ie.itemLevel, "#90A4AE", 11f));
                }
                iBox.addView(bodyText(ctx, ie.reasonRu, "#ECEFF1", 11.5f));
                invCard.addView(iBox);
            }
            if (filteredItems.isEmpty()) {
                invCard.addView(bodyText(ctx, "В выбранном фильтре предметов нет.", "#90A4AE", 12f));
            } else {
                LinearLayout pageRow = row(ctx);
                Button prev = pillButton(ctx, "◀ Назад", "#16222E", "#FFFFFF");
                prev.setEnabled(itemBagPage > 0);
                prev.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { itemBagPage--; renderBagAndResourcesTab(ctx, root, dash, p); }
                });
                pageRow.addView(prev);
                TextView pageLabel = bodyText(ctx, "  " + (itemBagPage + 1) + " / " + pageCount
                        + " · " + (startIndex + 1) + "–" + endIndex + " из " + filteredItems.size() + "  ", "#ECEFF1", 12f);
                pageLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                pageRow.addView(pageLabel);
                Button next = pillButton(ctx, "Далее ▶", "#16222E", "#FFFFFF");
                next.setEnabled(itemBagPage + 1 < pageCount);
                next.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { itemBagPage++; renderBagAndResourcesTab(ctx, root, dash, p); }
                });
                pageRow.addView(next);
                invCard.addView(pageRow);
            }
        }
        root.addView(invCard);

        // 2. ПОЛНЫЙ МЕШОК САМОЦВЕТОВ (/api/gems bag)
        final BagAndPetAdvisor.GemBagAuditSummary gemSum = BagAndPetAdvisor.evaluateGemBag(
                dash, IdleArcApiClient.getCachedGemsData(), p);
        LinearLayout gemBagCard = makeCard(ctx, "#111B24", "#AB47BC");
        gemBagCard.addView(cardTitle(ctx,
                "💎 Мешок самоцветов (" + gemSum.bagCount + " / " + gemSum.bagCap + ")",
                "#E1BEE7"));
        gemBagCard.addView(labeledProgressBar(ctx, "Заполненность мешка камней",
                gemSum.bagCount + " / " + gemSum.bagCap,
                gemSum.bagCap > 0 ? (gemSum.bagCount * 100.0 / gemSum.bagCap) : 0.0, "#BA68C8"));

        LinearLayout famRow = row(ctx);
        famRow.setPadding(0, dp(ctx, 4), 0, dp(ctx, 6));
        famRow.addView(statTile(ctx, "🟢 Нефрит", String.valueOf(gemSum.countJade), "#69F0AE"));
        addSpace(ctx, famRow, 5);
        famRow.addView(statTile(ctx, "🔵 Лазурит", String.valueOf(gemSum.countLapis), "#4FC3F7"));
        addSpace(ctx, famRow, 5);
        famRow.addView(statTile(ctx, "🔴 Гранат", String.valueOf(gemSum.countGarnet), "#FF8A80"));
        addSpace(ctx, famRow, 5);
        famRow.addView(statTile(ctx, "🟡 Янтарь", String.valueOf(gemSum.countAmber), "#FFD54F"));
        gemBagCard.addView(famRow);

        if (gemSum.salvageGemsCount > 0 || gemSum.upgradeSocketsCount > 0) {
            gemBagCard.addView(bodyText(ctx, String.format(Locale.US,
                    "💡 Апгрейдов в твои оправы: %d шт. · Ненужных T4/T5 камней под разбор: %d шт. (дадут +%s Пыли!)",
                    gemSum.upgradeSocketsCount, gemSum.salvageGemsCount, RuGameDictionary.fmtBig(gemSum.totalSalvageDust)),
                    "#FFE082", 12f));
        }

        LinearLayout gemFilterRow = row(ctx);
        gemFilterRow.setPadding(0, dp(ctx, 4), 0, dp(ctx, 6));
        String[] gfTitles = {
            "Все (" + gemSum.bagCount + ")",
            "💎 В слот / Слить",
            "🔨 В пыль (" + gemSum.salvageGemsCount + ")"
        };
        for (int gfi = 0; gfi < 3; gfi++) {
            final int gfIdx = gfi;
            boolean sel = (activeGemBagFilter == gfi);
            Button gfb = pillButton(ctx, gfTitles[gfi],
                    sel ? "#3A234B" : "#181E29",
                    sel ? "#FFFFFF" : "#90A4AE");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (gfi < 2) lp.rightMargin = dp(ctx, 5);
            gfb.setLayoutParams(lp);
            gfb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activeGemBagFilter = gfIdx;
                    renderBagAndResourcesTab(ctx, root, dash, p);
                }
            });
            gemFilterRow.addView(gfb);
        }
        gemBagCard.addView(gemFilterRow);

        if (gemSum.entries.isEmpty()) {
            gemBagCard.addView(bodyText(ctx,
                    "Мешок самоцветов пуст (или нажми «🔄 Обновить API» для подгрузки /api/gems).",
                    "#B0BEC5", 12f));
        } else {
            for (BagAndPetAdvisor.BagGemEntry ge : gemSum.entries) {
                if (activeGemBagFilter == 1 && (ge.action == BagAndPetAdvisor.GemBagAction.SALVAGE_DUST
                        || ge.action == BagAndPetAdvisor.GemBagAction.KEEP_RESERVE)) continue;
                if (activeGemBagFilter == 2 && ge.action != BagAndPetAdvisor.GemBagAction.SALVAGE_DUST) continue;

                LinearLayout gBox = makeCard(ctx, "#0D151D", ge.familyHex);
                gBox.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
                LinearLayout gTop = row(ctx);
                TextView gt = bodyText(ctx, ge.titleRu + (ge.locked ? " 🔒" : ""), ge.familyHex, 12.5f);
                gt.setTypeface(Typeface.DEFAULT_BOLD);
                gt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                gTop.addView(gt);
                gTop.addView(badge(ctx, ge.action.badgeText, ge.action.bgHex, ge.action.fgHex));
                gBox.addView(gTop);
                gBox.addView(bodyText(ctx, "Доп. статы: " + ge.secondariesRu, "#CFD8DC", 11.5f));
                gBox.addView(bodyText(ctx, ge.reasonRu, "#ECEFF1", 11.5f));
                gemBagCard.addView(gBox);
            }
        }
        root.addView(gemBagCard);

        // 3. Калькулятор ROI ресурсов и кошелёк героя
        ApiFeatureAnalyzers.ResourceRoiReport roi = ApiFeatureAnalyzers.analyzeResourcesAndRoi(dash, p);
        if (roi != null) {
            LinearLayout walletCard = makeCard(ctx, "#121D26", "#FFD54F");
            walletCard.addView(cardTitle(ctx, "💰 Кошелёк и Лучшее вложение ресурсов", "#FFE082"));

            LinearLayout r1 = row(ctx);
            r1.addView(statTile(ctx, "🪙 Золото", RuGameDictionary.fmtBig(roi.gold), "#FFD54F"));
            addSpace(ctx, r1, 6);
            r1.addView(statTile(ctx, "✨ Пыль камней", RuGameDictionary.fmtBig(roi.gemDust), "#81D4FA"));
            addSpace(ctx, r1, 6);
            r1.addView(statTile(ctx, "🐾 Осколки", RuGameDictionary.fmtBig(roi.petShards), "#CE93D8"));
            walletCard.addView(r1);

            LinearLayout r2 = row(ctx);
            r2.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6));
            r2.addView(statTile(ctx, "💎 Рубины", RuGameDictionary.fmtBig(roi.rubies), "#FF8A80"));
            addSpace(ctx, r2, 6);
            r2.addView(statTile(ctx, "🥚 Яйца петов", String.valueOf(roi.petEggs), "#A5D6A7"));
            addSpace(ctx, r2, 6);
            r2.addView(statTile(ctx, "🔥 Стих. осколки", RuGameDictionary.fmtBig(roi.elementalShards), "#FFAB91"));
            walletCard.addView(r2);

            if (!roi.dustStepPlan.isEmpty()) {
                walletCard.addView(subHeader(ctx, "🪜 План вложения Пыли самоцветов (доступно апгрейдов: " + roi.affordableSocketUpgrades + "):"));
                for (String s : roi.dustStepPlan) walletCard.addView(bulletLine(ctx, s, "#E1F5FE"));
            }
            if (!roi.petShardPlan.isEmpty()) {
                walletCard.addView(subHeader(ctx, "🐾 Приоритет Осколков питомцев и Эволюции:"));
                for (String s : roi.petShardPlan) walletCard.addView(bulletLine(ctx, s, "#F3E5F5"));
            }
            for (String g : roi.goldUpgradeHints) {
                walletCard.addView(bulletLine(ctx, g, "#FFF9C4"));
            }
            root.addView(walletCard);

            if (!roi.warehouseLines.isEmpty()) {
                LinearLayout whBody = new LinearLayout(ctx);
                whBody.setOrientation(LinearLayout.VERTICAL);
                for (String w : roi.warehouseLines) whBody.addView(bulletLine(ctx, w, "#CFD8DC"));
                root.addView(collapsibleCard(ctx, "🧱 Склад материалов кузницы (" + roi.warehouseLines.size() + ")", whBody, false));
            }
        }

        // 4. Сеты, Новые руны, Пресеты и Автоматизация
        LinearLayout metaCard = makeCard(ctx, "#121C24", "#7E57C2");
        metaCard.addView(cardTitle(ctx, "🧩 Комплекты (Сеты), Новые Руны и Автоматика", "#D1C4E9"));
        for (String s : ApiFeatureAnalyzers.analyzeGearSetsAndElements(dash)) {
            metaCard.addView(bulletLine(ctx, s, "#E8EAF6"));
        }
        for (String s : ApiFeatureAnalyzers.analyzeNewRunesAndAutomation(dash, p)) {
            metaCard.addView(bulletLine(ctx, s, "#E0F2F1"));
        }
        root.addView(metaCard);
    }

    // =========================================================================
    // ВКЛАДКА 4: ВСЕ 20 ГНЁЗД САМОЦВЕТОВ + БЕСПЛАТНЫЙ ПЕРЕСТАНОВЩИК
    // =========================================================================
    public static void renderGemsTab(Context ctx,
                                     LinearLayout root,
                                     Map<String, Object> dash,
                                     DruidProfile p) {
        root.removeAllViews();
        if (dash == null || p == null) {
            root.addView(emptySyncCard(ctx));
            return;
        }
        root.addView(stanceGuideCard(ctx, dash, "gems"));

        // 1. Баннер бесплатной перестановки камней (0 ресурсов!)
        List<String> swaps = ApiFeatureAnalyzers.findFreeGemSwaps(dash, p);
        LinearLayout swapCard = makeCard(ctx, swaps.isEmpty() ? "#11221B" : "#1F2612",
                swaps.isEmpty() ? "#4CAF50" : "#C6FF00");
        swapCard.addView(cardTitle(ctx, "🔀 Бесплатная перестановка камней (0 затрат)", "#EEFF41"));
        if ("harmony".equals(StanceAdvisor.stance(dash))) {
            swapCard.addView(bodyText(ctx, "Сравни перестановки вручную: старый расчёт не учитывает изменение баланса Гармонии.", "#FFE082", 12.5f));
        } else if (swaps.isEmpty()) {
            swapCard.addView(bodyText(ctx,
                    "Перестановок по текущей эвристике не найдено. Это не доказательство оптимальности.",
                    "#A5D6A7", 12.5f));
        } else {
            for (String sw : swaps) {
                swapCard.addView(bulletLine(ctx, sw, "#F4FF81"));
            }
        }
        root.addView(swapCard);

        // 2. Визуальные карточки всех 20 гнёзд по слотам
        Map<String, Object> tracks = SimpleJson.asMap(dash.get("gemSockets"));
        if (tracks == null) return;
        String[][] order = {
            {"weapon_1", "⚔️ Оружие"},
            {"torch", "🔥 Факел"},
            {"helmet", "🪖 Шлем"},
            {"chest_armor", "🛡️ Торс"},
            {"gloves", "🧤 Перчатки"},
            {"boots", "🥾 Сапоги"},
            {"belt", "🎗️ Пояс"},
            {"amulet", "📿 Амулет"},
            {"ring_1", "💍 Кольцо #1"},
            {"ring_2", "💍 Кольцо #2"}
        };

        for (String[] pair : order) {
            Map<String, Object> tr = SimpleJson.asMap(tracks.get(pair[0]));
            if (tr == null) continue;
            List<Object> socks = SimpleJson.asList(tr.get("sockets"));
            if (socks == null || socks.isEmpty()) continue;

            LinearLayout slotCard = makeCard(ctx, "#121C24", "#2E4354");
            slotCard.addView(cardTitle(ctx, pair[1], "#B3E5FC"));

            for (Object so : socks) {
                Map<String, Object> sk = SimpleJson.asMap(so);
                if (sk == null) continue;
                int idx = SimpleJson.intVal(sk, "index", 1);
                boolean unlocked = SimpleJson.bool(sk, "unlocked", false);
                int sLvl = SimpleJson.intVal(sk, "setting_level", 0);
                double mult = SimpleJson.num(sk, "multiplier", 1.0);
                double nextCost = SimpleJson.num(sk, "next_cost", 0.0);

                if (!unlocked) {
                    slotCard.addView(bodyText(ctx, "🔒 Гнездо #" + idx + " — закрыто", "#78909C", 12f));
                    continue;
                }
                Map<String, Object> gem = SimpleJson.asMap(sk.get("gem"));
                if (gem == null) {
                    slotCard.addView(bodyText(ctx, "⚠️ Гнездо #" + idx + " (оправа ур." + sLvl + ") — ПУСТО!", "#FF8A80", 12.5f));
                    continue;
                }

                String famSlug = SimpleJson.str(gem, "family", "");
                String famRu = RuGameDictionary.gemFamilyRu(famSlug);
                String famHex = RuGameDictionary.gemFamilyColorHex(famSlug);
                String rarRu = RuGameDictionary.gemRarityRu(SimpleJson.str(gem, "rarity", ""));
                int qual = SimpleJson.intVal(gem, "quality", 100);
                boolean pristine = SimpleJson.bool(gem, "pristine", false);

                LinearLayout gBox = makeCard(ctx, "#0E171E", famHex);
                gBox.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));

                LinearLayout gTop = row(ctx);
                TextView gTitle = bodyText(ctx,
                        "#" + idx + " " + rarRu + " " + famRu + (pristine ? " ★ Первозд." : ""),
                        famHex, 12.5f);
                gTitle.setTypeface(Typeface.DEFAULT_BOLD);
                gTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                gTop.addView(gTitle);
                gTop.addView(badge(ctx, String.format(Locale.US, "Ур.%d · ×%.2f", sLvl, mult), "#1E2D3B", "#B0BEC5"));
                gBox.addView(gTop);

                gBox.addView(labeledProgressBar(ctx, "Качество огранки", qual + "%", qual, famHex));

                List<Object> secs = SimpleJson.asList(gem.get("secondaries"));
                if (secs != null && !secs.isEmpty()) {
                    List<String> secParts = new ArrayList<>();
                    for (Object secObj : secs) {
                        Map<String, Object> sm = SimpleJson.asMap(secObj);
                        if (sm == null) continue;
                        String st = SimpleJson.str(sm, "stat", "");
                        double sv = SimpleJson.num(sm, "value", 0.0);
                        secParts.add(RuGameDictionary.statName(st) + " " + RuGameDictionary.formatStatValue(st, sv, null));
                    }
                    if (!secParts.isEmpty()) {
                        gBox.addView(bodyText(ctx, "Доп. свойства: " + join(secParts, " · "), "#CFD8DC", 11.5f));
                    }
                }
                if (nextCost > 0) {
                    gBox.addView(bodyText(ctx, "След. уровень оправы: " + RuGameDictionary.fmtBig(nextCost) + " пыли",
                            "#78909C", 11f));
                }
                slotCard.addView(gBox);
            }
            root.addView(slotCard);
        }
    }

    // =========================================================================
    // ВКЛАДКА 5: ПИТОМЦЫ (ЧЕТВЁРКА + ЭВОЛЮЦИЯ) И ТАЛИСМАНЫ (100% РУССКИЙ)
    // =========================================================================
    public static void renderPetsAndTalismansTab(Context ctx,
                                                 LinearLayout root,
                                                 Map<String, Object> dash,
                                                 DruidProfile p) {
        root.removeAllViews();
        if (dash == null || p == null) {
            root.addView(emptySyncCard(ctx));
            return;
        }
        root.addView(stanceGuideCard(ctx, dash, "pets"));

        // 0. Глубокий оптимизатор аффиксов питомцев (Уровень + Капы + Самоцветы + Класс)
        BagAndPetAdvisor.PetDeepAdvice petAdv = BagAndPetAdvisor.analyzePetsDeep(
                dash, IdleArcApiClient.getCachedGemsData(), p);
        LinearLayout advCard = makeCard(ctx, "#132029", "#26C6DA");
        advCard.addView(cardTitle(ctx, "🧠 Оптимизатор аффиксов питомцев (под твой уровень, капы и камни)", "#80DEEA"));
        advCard.addView(bodyText(ctx, petAdv.mechanicsExplainer, "#CFD8DC", 11.5f));
        advCard.addView(subHeader(ctx, petAdv.levelStageTitle + " — твоя лучшая 4-ка аффиксов сейчас:"));
        for (String rLine : petAdv.recommended4AffixesNow) {
            advCard.addView(bulletLine(ctx, rLine, "#E0F7FA"));
        }
        advCard.addView(subHeader(ctx, "💎 Капы и связка с твоими Самоцветами:"));
        for (String gNote : petAdv.gemSynergyNotes) {
            advCard.addView(bulletLine(ctx, gNote, "#FFF59D"));
        }
        if (!petAdv.perPetActionPlan.isEmpty()) {
            advCard.addView(subHeader(ctx, "🔧 Что перекрутить / докачать на твоих 4 петах:"));
            for (String pAct : petAdv.perPetActionPlan) {
                advCard.addView(bulletLine(ctx, pAct, "#C8E6C9"));
            }
        }
        if (!"harmony".equals(StanceAdvisor.stance(dash))) root.addView(advCard);

        // 1. Карточки 4 боевых питомцев
        LinearLayout petsHeader = makeCard(ctx, "#121C24", "#66BB6A");
        petsHeader.addView(cardTitle(ctx,
                "🐾 Боевой отряд из 4 питомцев (Мастерство ур. " + SimpleJson.intVal(dash, "effectivePetMasteryLevel", 0) + ")",
                "#A5D6A7"));

        List<Object> pets = SimpleJson.asList(dash.get("characterPets"));
        if (pets != null) {
            for (Object po : pets) {
                Map<String, Object> pm = SimpleJson.asMap(po);
                if (pm == null || !SimpleJson.bool(pm, "is_equipped", false)) continue;

                String pName = SimpleJson.str(pm, "name", "Питомец");
                String rarRaw = SimpleJson.str(pm, "rarity", "");
                String rarRu = RuGameDictionary.itemOrPetRarityRu(rarRaw);
                String rarHex = RuGameDictionary.rarityColorHex(rarRaw);
                int cLvl = SimpleJson.intVal(pm, "compound_level", SimpleJson.intVal(pm, "level", 1));
                int cCap = Math.max(cLvl, SimpleJson.intVal(pm, "compound_effective_cap", 200));
                String specKey = SimpleJson.str(pm, "special_bonus_type", "");
                double specVal = SimpleJson.num(pm, "special_bonus_active_value", 0.0);
                Map<String, Object> dmg = SimpleJson.asMap(pm.get("damage"));
                double avgD = dmg != null ? 0.5 * (SimpleJson.num(dmg, "min", 0.0) + SimpleJson.num(dmg, "max", 0.0)) : 0.0;
                int evMax = SimpleJson.intVal(pm, "evolve_affixes_at_max", 0);
                int evReq = SimpleJson.intVal(pm, "evolve_affixes_required", 0);

                LinearLayout pCard = makeCard(ctx, "#0F1820", rarHex);
                LinearLayout pTop = row(ctx);
                TextView pTitle = cardTitle(ctx, "🐾 " + pName, "#FFFFFF");
                pTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                pTop.addView(pTitle);
                pTop.addView(badge(ctx, rarRu, "#1F2933", rarHex));
                pCard.addView(pTop);

                pCard.addView(bodyText(ctx, "Базовый урон: " + RuGameDictionary.fmtBig(avgD)
                        + " · Спец-бонус: " + RuGameDictionary.statName(specKey) + " "
                        + RuGameDictionary.formatStatValue(specKey, specVal, null), "#80DEEA", 12f));

                pCard.addView(labeledProgressBar(ctx, "Составной уровень", cLvl + " / " + cCap,
                        cCap > 0 ? (cLvl * 100.0 / cCap) : 100.0, "#66BB6A"));

                if (evReq > 0) {
                    pCard.addView(labeledProgressBar(ctx, "Готовность к эволюции", evMax + " / " + evReq + " макс-статов",
                            (evMax * 100.0 / evReq), "#CE93D8"));
                }

                List<Object> affs = SimpleJson.asList(pm.get("affixes"));
                if (affs != null && !affs.isEmpty()) {
                    List<String> affStrings = new ArrayList<>();
                    for (Object ao : affs) {
                        Map<String, Object> am = SimpleJson.asMap(ao);
                        if (am == null) continue;
                        String ak = SimpleJson.str(am, "name", "");
                        double av = SimpleJson.num(am, "value", 0.0);
                        int al = SimpleJson.intVal(am, "level", 1);
                        affStrings.add(RuGameDictionary.statName(ak) + " "
                                + RuGameDictionary.formatStatValue(ak, av, null) + " (ур." + al + ")");
                    }
                    pCard.addView(bodyText(ctx, join(affStrings, " · "), "#CFD8DC", 11.5f));
                }
                petsHeader.addView(pCard);
            }
        }
        root.addView(petsHeader);

        // 2. Карточки Талисманов (100% русский перевод всех надписей!)
        LinearLayout talCard = makeCard(ctx, "#121C24", "#FFB74D");
        talCard.addView(cardTitle(ctx, "✒️ Надетые Талисманы и Надписи", "#FFE082"));
        List<Object> tals = SimpleJson.asList(dash.get("equippedTalismans"));
        if (tals != null) {
            for (Object to : tals) {
                Map<String, Object> tm = SimpleJson.asMap(to);
                if (tm == null) continue;
                String tSlug = SimpleJson.str(tm, "slug", "");
                String tName = RuGameDictionary.talismanNameRu(tSlug, SimpleJson.str(tm, "name", "Талисман"));
                int tSlot = SimpleJson.intVal(tm, "slot", 1);
                int tLvl = SimpleJson.intVal(tm, "level", 1);

                LinearLayout tBox = makeCard(ctx, "#1A1712", "#FFA726");
                LinearLayout tHead = row(ctx);
                TextView tTitle = cardTitle(ctx, "🧿 Слот #" + tSlot + ": " + tName, "#FFCC80");
                tTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                tHead.addView(tTitle);
                tHead.addView(badge(ctx, "Ур. " + tLvl, "#2D2418", "#FFD54F"));
                tBox.addView(tHead);

                Map<String, Object> totals = SimpleJson.asMap(tm.get("inscription_totals"));
                if (totals != null && !totals.isEmpty()) {
                    for (Map.Entry<String, Object> e : totals.entrySet()) {
                        Map<String, Object> em = SimpleJson.asMap(e.getValue());
                        double tot = em != null ? SimpleJson.num(em, "total", 0.0) : SimpleJson.num(totals, e.getKey(), 0.0);
                        String suf = em != null ? SimpleJson.str(em, "suffix", null) : null;
                        if (Math.abs(tot) > 0.01) {
                            tBox.addView(bulletLine(ctx, RuGameDictionary.statName(e.getKey()) + ": "
                                    + RuGameDictionary.formatStatValue(e.getKey(), tot, suf), "#FFF8E1"));
                        }
                    }
                }
                talCard.addView(tBox);
            }
        }
        talCard.addView(bodyText(ctx, DruidKnowledgeBase.buildInscriptionsAndPetsGuide(p), "#CFD8DC", 11.5f));
        root.addView(talCard);
    }

    // =========================================================================
    // ВКЛАДКА 6: НАВЫКИ ДРУИДА, ДЕРЕВО, ВОЗНЕСЕНИЕ, ГИЛЬДИЯ И ДНЕВНИК
    // =========================================================================
    public static void renderSkillsAndWorldTab(Context ctx,
                                               LinearLayout root,
                                               Map<String, Object> dash,
                                               DruidProfile p,
                                               Map<String, Object> gameCfg) {
        root.removeAllViews();
        if (dash != null) root.addView(stanceGuideCard(ctx, dash, "skills"));
        root.addView(passiveTreeCard(ctx, dash, p, gameCfg));
        if (dash == null || p == null) {
            root.addView(emptySyncCard(ctx));
            return;
        }

        // 1. Аудит дерева навыков активного класса (все 5 классов, IDs 1..90) с детектором оверкапа
        HeroClassProfile.HeroClass hc = HeroClassProfile.resolveHeroClass(dash, p);
        LinearLayout skillCard = makeCard(ctx, "#121C24", "#4DB6AC");
        skillCard.addView(cardTitle(ctx, hc.icon + " Классовые навыки: " + hc.ruName + " (+Все навыки: +" + p.bonusAllClassSkills + ")", "#80CBC4"));
        skillCard.addView(bodyText(ctx, HeroClassProfile.buildMultiClassSkillsReport(dash, p, gameCfg), "#E0F2F1", 12f));
        root.addView(skillCard);

        // 2. Пассивки, Вознесение, Звёздные вехи, Гильдия и Зоны мира
        LinearLayout worldCard = makeCard(ctx, "#121C24", "#7986CB");
        worldCard.addView(cardTitle(ctx, "🌟 Пассивки, Вознесение, Гильдия и Мир", "#C5CAE9"));
        for (String line : ApiFeatureAnalyzers.analyzePassivesAscentAndGuild(dash)) {
            worldCard.addView(bulletLine(ctx, line, "#E8EAF6"));
        }
        root.addView(worldCard);

        // 3. 30-дневный Дневник прогресса героя
        List<SnapshotHistoryTracker.DailyPoint> journal = SnapshotHistoryTracker.getDailyJournal(ctx);
        LinearLayout journalCard = makeCard(ctx, "#121C24", "#64B5F6");
        journalCard.addView(cardTitle(ctx, "📅 Дневник роста персонажа (по дням)", "#90CAF9"));
        if (journal.isEmpty()) {
            journalCard.addView(bodyText(ctx, "Первая запись появится автоматически при синхронизации.", "#90A4AE", 12f));
        } else {
            for (int i = 0; i < Math.min(14, journal.size()); i++) {
                SnapshotHistoryTracker.DailyPoint dp = journal.get(i);
                journalCard.addView(bulletLine(ctx, String.format(Locale.US,
                        "%s — Ур. %d · GS %d · Ср. удар пета %s (×%.2fM)",
                        dp.dateStr, dp.level, dp.gearScore, RuGameDictionary.fmtBig(dp.petAvgDmg), dp.trueMultM),
                        "#E3F2FD"));
            }
        }
        root.addView(journalCard);
    }

    private static String stanceScenarioKey(Map<String, Object> dash) {
        String id = SimpleJson.str(dash, "characterId", "").trim();
        return id.isEmpty() || id.equals("0") ? "" : "character:" + id;
    }

    private static int stanceManualScenario(Context ctx, Map<String, Object> dash) {
        if (!"harmony".equals(StanceAdvisor.stance(dash))) return StanceAdvisor.UNKNOWN;
        String key = stanceScenarioKey(dash);
        if (key.isEmpty()) return StanceAdvisor.UNKNOWN;
        int scenario = ctx.getSharedPreferences("stance_scenarios", Context.MODE_PRIVATE).getInt(key, 0);
        return scenario >= 0 && scenario <= 3 ? scenario : 0;
    }

    private static int effectiveStanceScenario(Map<String, Object> dash, int manual) {
        if (!"harmony".equals(StanceAdvisor.stance(dash))) return StanceAdvisor.UNKNOWN;
        if (manual != StanceAdvisor.UNKNOWN) return manual;
        HarmonyBalance comparison = HarmonyBalance.fromDashboard(dash);
        return comparison != null ? comparison.estimatedScenario() : StanceAdvisor.UNKNOWN;
    }

    private static int stanceScenario(Context ctx, Map<String, Object> dash) {
        return effectiveStanceScenario(dash, stanceManualScenario(ctx, dash));
    }

    private static View stanceGuideCard(final Context ctx, final Map<String, Object> dash, final String section) {
        final LinearLayout card = makeCard(ctx, "#11252A", "#4DB6AC");
        card.addView(cardTitle(ctx, "⚖ Стойка и баланс сборки", "#80CBC4"));
        final LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        card.addView(text);
        final String key = stanceScenarioKey(dash);
        final int[] selected = {stanceManualScenario(ctx, dash)};
        final Button scenarioButton;
        if ("hero".equals(section) && "harmony".equals(StanceAdvisor.stance(dash)) && !key.isEmpty()) {
            scenarioButton = new Button(ctx);
            card.addView(scenarioButton);
        } else scenarioButton = null;
        final Runnable update = new Runnable() {
            @Override public void run() {
                text.removeAllViews();
                int effective = effectiveStanceScenario(dash, selected[0]);
                List<String> advice = StanceAdvisor.advice(dash, effective);
                for (int i = 0; i < advice.size(); i++) {
                    String line = advice.get(i);
                    boolean visible = "hero".equals(section) || i == 0
                            || line.startsWith("Сценарий:") || line.startsWith("Предметы")
                            || line.startsWith("Руны") || line.startsWith("Бонусы")
                            || ("equipment".equals(section) && line.startsWith("Проверить слот"))
                            || ("equipment".equals(section) && line.startsWith("Надетые"));
                    if (visible) text.addView(bulletLine(ctx, line, "#E0F2F1"));
                }
                if (!"hero".equals(section) && "harmony".equals(StanceAdvisor.stance(dash)))
                    text.addView(bodyText(ctx, "Сценарий сравнения меняется на вкладке «Герой». Старые оценки оптимизаторов не рассчитывают баланс Гармонии.", "#FFE082", 11f));
                if ("harmony".equals(StanceAdvisor.stance(dash)) && selected[0] == StanceAdvisor.UNKNOWN) {
                    text.addView(bodyText(ctx, HarmonyBalance.fromDashboard(dash) == null
                            ? "Для автоматического предположения нет данных. Можно выбрать условный сценарий на вкладке «Герой»."
                            : "Сценарий автоматически предполагается по средним ударам из снимка, не по реальному DPS. Вручную можно выбрать другой на вкладке «Герой».", "#FFE082", 11f));
                }
                if (scenarioButton != null) scenarioButton.setText(selected[0] == 0
                        ? "Сценарий: авто по средним ударам (сменить)"
                        : "Сценарий: " + StanceAdvisor.scenarioName(selected[0]) + " (сменить)");
                if ("hero".equals(section) && "harmony".equals(StanceAdvisor.stance(dash)) && key.isEmpty())
                    text.addView(bodyText(ctx, "ID персонажа отсутствует — выбор сценария отключён, чтобы не переносить его на другой аккаунт.", "#FFE082", 11f));
            }
        };
        if (scenarioButton != null) scenarioButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                selected[0] = (selected[0] + 1) % 4;
                ctx.getSharedPreferences("stance_scenarios", Context.MODE_PRIVATE).edit().putInt(key, selected[0]).apply();
                update.run();
            }
        });
        update.run();
        return card;
    }

    private static View passiveTreeCard(final Context ctx, final Map<String, Object> dash,
                                        final DruidProfile p, final Map<String, Object> config) {
        LinearLayout card = makeCard(ctx, "#17221E", "#66BB6A");
        card.addView(cardTitle(ctx, "🌳 Помощник общего пассивного дерева", "#A5D6A7"));
        final android.content.SharedPreferences prefs = ctx.getSharedPreferences("passive_tree_guide", Context.MODE_PRIVATE);
        final int autoClass = PassiveTreeAdvisor.detectedClass(dash, p == null ? 0 : p.overrideClassId);
        String mode = p == null || p.mode == null ? "PUSH" : p.mode.name();
        final String activeMode = mode;
        card.addView(bodyText(ctx, "Режим: " + (p == null || p.mode == null ? "Пуш" : p.mode.title)
                + " · Стойка: " + (StanceAdvisor.stance(dash).isEmpty() ? "неизвестна" : RuGameDictionary.stanceRu(StanceAdvisor.stance(dash)))
                + " · Это ориентиры, а не расчёт оптимума.", "#CFD8DC", 12f));
        card.addView(bodyText(ctx, "Вложения, остаток очков, капы и доступность узлов неизвестны. "
                + "Строки ниже — варианты для сравнения, не готовое распределение: проверь открытие и свободные места в игре. Автоматических действий нет.", "#FFE082", 12f));
        final int tierCount = PassiveTreeAdvisor.tierCount(config);
        final int[] selection = {prefs.getInt("class", 0), prefs.getInt("tier", 0)};
        if (selection[0] < 0 || selection[0] > 5) selection[0] = 0;
        if (selection[1] < 0 || selection[1] >= tierCount) selection[1] = 0;
        final String[] classNames = {"Авто: " + (autoClass == 0 ? "неизвестен" : HeroClassProfile.HeroClass.fromIdOrName(autoClass, "").ruName),
                "Воин", "Лучник", "Маг", "Разбойник", "Друид"};
        final Button classButton = new Button(ctx);
        card.addView(classButton);
        LinearLayout tierControls = new LinearLayout(ctx);
        tierControls.setOrientation(LinearLayout.HORIZONTAL);
        final Button previous = new Button(ctx);
        final Button next = new Button(ctx);
        final TextView tierLabel = bodyText(ctx, "", "#E8F5E9", 13f);
        previous.setText("◀"); next.setText("▶");
        tierControls.addView(previous);
        tierControls.addView(tierLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tierControls.addView(next);
        card.addView(tierControls);
        final LinearLayout lines = new LinearLayout(ctx);
        lines.setOrientation(LinearLayout.VERTICAL);
        card.addView(lines);
        final Runnable update = new Runnable() {
            @Override public void run() {
                classButton.setText("Класс: " + classNames[selection[0]] + " (сменить)");
                tierLabel.setText(tierCount == 0 ? "Нет данных ярусов" : "Ярус " + (selection[1] + 1) + " / " + tierCount);
                previous.setEnabled(tierCount > 0 && selection[1] > 0);
                next.setEnabled(tierCount > 0 && selection[1] + 1 < tierCount);
                lines.removeAllViews();
                int cls = selection[0] == 0 ? autoClass : selection[0];
                for (String line : PassiveTreeAdvisor.recommendations(config, selection[1], cls, activeMode,
                        StanceAdvisor.stance(dash), stanceScenario(ctx, dash))) {
                    lines.addView(bulletLine(ctx, line, "#E8F5E9"));
                }
            }
        };
        classButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                selection[0] = (selection[0] + 1) % classNames.length;
                prefs.edit().putInt("class", selection[0]).apply(); update.run();
            }
        });
        previous.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (selection[1] > 0) selection[1]--;
                prefs.edit().putInt("tier", selection[1]).apply(); update.run();
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (selection[1] + 1 < tierCount) selection[1]++;
                prefs.edit().putInt("tier", selection[1]).apply(); update.run();
            }
        });
        update.run();
        return card;
    }

    // =========================================================================
    // БАЗОВЫЕ НАТИВНЫЕ ANDROID UI-КОМПОНЕНТЫ КАРТОЧЕК
    // =========================================================================
    private static LinearLayout emptySyncCard(Context ctx) {
        LinearLayout c = makeCard(ctx, "#16202A", "#4FC3F7");
        c.addView(cardTitle(ctx, "🌐 Ожидание синхронизации с сервером IdleArc", "#81D4FA"));
        c.addView(bodyText(ctx,
                "Нажми «🔑 Аккаунт API», введи почту и пароль от игры, затем нажми «🔄 Обновить API» — помощник мгновенно разложит весь аккаунт по визуальным карточкам без захвата экрана!",
                "#CFD8DC", 13f));
        return c;
    }

    public static LinearLayout makeCard(Context ctx, String bgHex, String borderHex) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 11);
        box.setPadding(pad, pad, pad, pad);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        gd.setCornerRadius(dp(ctx, 11));
        gd.setStroke(dp(ctx, 1), Color.parseColor(borderHex));
        box.setBackground(gd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 9);
        box.setLayoutParams(lp);
        return box;
    }

    public static View collapsibleCard(Context ctx, final String title, final View contentBody, boolean expandedByDefault) {
        LinearLayout card = makeCard(ctx, "#121C24", "#37474F");
        Map<String, Boolean> states = expandedCards.get(ctx);
        if (states == null) {
            states = new java.util.HashMap<>();
            expandedCards.put(ctx, states);
        }
        final Map<String, Boolean> cardStates = states;
        Boolean prior = states.get(title);
        boolean expanded = prior != null ? prior : expandedByDefault;
        final TextView header = cardTitle(ctx, (expanded ? "▾ " : "▸ ") + title, "#80DEEA");
        contentBody.setVisibility(expanded ? View.VISIBLE : View.GONE);
        header.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean nowVis = contentBody.getVisibility() != View.VISIBLE;
                contentBody.setVisibility(nowVis ? View.VISIBLE : View.GONE);
                cardStates.put(title, nowVis);
                header.setText((nowVis ? "▾ " : "▸ ") + title);
            }
        });
        card.addView(header);
        card.addView(contentBody);
        return card;
    }

    public static LinearLayout labeledProgressBar(Context ctx, String leftLabel, String rightValue, double pct0to100, String fillHex) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(ctx, 3), 0, dp(ctx, 4));

        LinearLayout top = row(ctx);
        TextView lTv = bodyText(ctx, leftLabel, "#CFD8DC", 11.5f);
        lTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(lTv);
        TextView rTv = bodyText(ctx, rightValue, "#FFFFFF", 11.5f);
        rTv.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(rTv);
        wrap.addView(top);

        ProgressBar pb = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(1000);
        int clamped = (int) Math.round(Math.max(0.0, Math.min(100.0, pct0to100)) * 10.0);
        pb.setProgress(clamped);

        GradientDrawable track = new GradientDrawable();
        track.setColor(Color.parseColor("#1D2B36"));
        track.setCornerRadius(dp(ctx, 5));

        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Color.parseColor(fillHex));
        fill.setCornerRadius(dp(ctx, 5));
        ClipDrawable clip = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{track, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        pb.setProgressDrawable(layers);

        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 7));
        pbLp.topMargin = dp(ctx, 2);
        pb.setLayoutParams(pbLp);
        wrap.addView(pb);
        return wrap;
    }

    public static LinearLayout statTile(Context ctx, String label, String value, String valHex) {
        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(LinearLayout.VERTICAL);
        int p = dp(ctx, 8);
        tile.setPadding(p, dp(ctx, 6), p, dp(ctx, 6));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#0D151C"));
        gd.setCornerRadius(dp(ctx, 8));
        gd.setStroke(dp(ctx, 1), Color.parseColor("#223444"));
        tile.setBackground(gd);
        tile.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView l = bodyText(ctx, label, "#90A4AE", 10.5f);
        TextView v = bodyText(ctx, value, valHex, 13.5f);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        tile.addView(l);
        tile.addView(v);
        return tile;
    }

    public static TextView badge(Context ctx, String text, String bgHex, String fgHex) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.parseColor(fgHex));
        tv.setTextSize(10.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(ctx, 7), dp(ctx, 3), dp(ctx, 7), dp(ctx, 3));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        gd.setCornerRadius(dp(ctx, 6));
        gd.setStroke(dp(ctx, 1), Color.parseColor(fgHex));
        tv.setBackground(gd);
        return tv;
    }

    public static Button pillButton(Context ctx, String text, String bgHex, String fgHex) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(11.5f);
        b.setTextColor(Color.parseColor(fgHex));
        b.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        b.setMinimumHeight(0);
        b.setMinHeight(0);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        gd.setCornerRadius(dp(ctx, 8));
        gd.setStroke(dp(ctx, 1), Color.parseColor(fgHex));
        b.setBackground(gd);
        return b;
    }

    private static TextView cardTitle(Context ctx, String text, String colorHex) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.parseColor(colorHex));
        tv.setTextSize(14f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, dp(ctx, 4));
        return tv;
    }

    private static TextView subHeader(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#80DEEA"));
        tv.setTextSize(12f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(ctx, 6), 0, dp(ctx, 2));
        return tv;
    }

    private static TextView bodyText(Context ctx, String text, String colorHex, float sizeSp) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.parseColor(colorHex));
        tv.setTextSize(sizeSp);
        tv.setLineSpacing(0f, 1.15f);
        return tv;
    }

    private static TextView bulletLine(Context ctx, String text, String colorHex) {
        TextView tv = bodyText(ctx, "• " + text, colorHex, 12f);
        tv.setPadding(0, dp(ctx, 2), 0, dp(ctx, 2));
        return tv;
    }

    private static LinearLayout row(Context ctx) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private static void addSpace(Context ctx, LinearLayout row, int dpWidth) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, dpWidth), 1));
        row.addView(v);
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static int dp(Context ctx, int val) {
        return Math.round(val * ctx.getResources().getDisplayMetrics().density);
    }
}
