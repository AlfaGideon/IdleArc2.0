package com.idlearc.companion;

import com.idlearc.companion.AffixPositionOptimizer.AffixType;
import com.idlearc.companion.DruidProfile.BuildMode;
import com.idlearc.companion.SlotAffixAdvisor.GearSlot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Живой математический анализатор системы «ГНЁЗДА И САМОЦВЕТЫ» IdleArc 1.3.15 (0% захардкоженных шаблонов).
 *
 * Точные формулы из Кодекса самоцветов игры:
 *   • Масштаб уровня гнезда:  M_ур = 1 + 0.04 × УровеньГнезда  (например, Ур. 46 => ×2.84)
 *   • Основной бонус камня:   БазаСлота × МножРедкости × (Качество% / 100) × M_ур
 *   • Вторичный бонус камня:  БазаТира[О/П/Б/С] × (Качество% / 100) × M_ур
 *
 * Работает в 4 режимах:
 *   1) ОДНОВРЕМЕННО со сканом аффиксов внутри карточки предмета (читает блок «ГНЁЗДА» чуть выше на оружии/броне/факеле).
 *   2) В окне конкретного слота «Гнёзда · Оружие / Факел / Шлем...» (разбирает Гнездо 1..4 и вторичные чипы).
 *   3) В окне «Обзор гнёзд» (аудит всех 10 слотов / 20 гнёзд, качества 80..115%+ и цветов вставленных камней).
 *   4) В «Кодексе самоцветов» и карточке главного экрана / шпаргалки (живой расчёт под текущую сборку).
 */
public class GemSocketOptimizer {

    /** 4 группы ячеек экипировки, определяющие основной бонус самоцвета */
    public enum SocketCategory {
        WEAPON("⚔️ Оружие", "Оружие (3 гнезда)", 3),
        OFFHAND("🔥 Факел", "Факел / Оффхенд (4 гнезда)", 4),
        ARMOR("🛡️ Броня", "Броня: Шлем, Торс, Перчатки, Сапоги (по 2 гнезда = 8)", 2),
        JEWELRY("💍 Украшения", "Украшения: Пояс (2), Амулет (1), Кольца (1+1) (всего 5)", 1);

        public final String shortName;
        public final String fullTitle;
        public final int defaultSockets;

        SocketCategory(String shortName, String fullTitle, int defaultSockets) {
            this.shortName = shortName;
            this.fullTitle = fullTitle;
            this.defaultSockets = defaultSockets;
        }

        public static SocketCategory fromGearSlot(GearSlot slot) {
            if (slot == null) return WEAPON;
            switch (slot) {
                case WEAPON:
                    return WEAPON;
                case TORCH:
                    return OFFHAND;
                case HELMET:
                case CHEST:
                case GLOVES:
                case BOOTS:
                    return ARMOR;
                case BELT:
                case AMULET:
                case RING:
                default:
                    return JEWELRY;
            }
        }

        public static int maxSocketsForSlot(GearSlot slot) {
            if (slot == null) return 3;
            switch (slot) {
                case WEAPON: return 3;
                case TORCH: return 4;
                case HELMET:
                case CHEST:
                case GLOVES:
                case BOOTS:
                case BELT: return 2;
                case AMULET:
                case RING: return 1;
                default: return 0;
            }
        }
    }

    /** 4 семейства самоцветов и их точные серверные формулы основного бонуса по группам слотов */
    public enum GemFamily {
        GARNET("🔴 Гранат", "Гранат"),
        JADE("🟢 Нефрит", "Нефрит"),
        LAPIS("🔵 Лазурит", "Лазурит"),
        AMBER("🟡 Янтарь", "Янтарь");

        public final String badge;
        public final String ruName;

        GemFamily(String badge, String ruName) {
            this.badge = badge;
            this.ruName = ruName;
        }

        /** Применяет (+1) или вычитает (-1) основной эффект самоцвета в состоянии HeroState */
        public void applyPrimaryToState(LiveBuildSimulator.HeroState s, SocketCategory cat, double multFactor, double sign) {
            double f = multFactor * sign;
            switch (this) {
                case GARNET:
                    switch (cat) {
                        case WEAPON:  s.atkDmgAdditivePct += 0.50 * f; break;
                        case OFFHAND: s.maxHpPctBonus += 0.60 * f; break;
                        case ARMOR:   s.defensePctBonus += 0.60 * f; break;
                        case JEWELRY: s.applyAffixDelta(AffixType.STRENGTH, 5.0 * f, 0.0); break;
                    }
                    break;
                case JADE:
                    switch (cat) {
                        case WEAPON:  s.petDmgAdditivePct += 0.50 * f; break;
                        case OFFHAND: s.atkDmgAdditivePct += 0.30 * f; break;
                        case ARMOR:   s.maxHpPctBonus += 0.60 * f; break;
                        case JEWELRY: s.applyAffixDelta(AffixType.DEXTERITY, 5.0 * f, 0.0); break;
                    }
                    break;
                case LAPIS:
                    switch (cat) {
                        case WEAPON:
                            s.atkDmgAdditivePct += 0.25 * f;
                            s.petDmgAdditivePct += 0.25 * f;
                            s.retaliationPct += 0.25 * f;
                            break;
                        case OFFHAND: s.petDmgAdditivePct += 0.30 * f; break;
                        case ARMOR:   s.expBonusPct += 2.00 * f; break;
                        case JEWELRY: s.applyAffixDelta(AffixType.INTELLIGENCE, 5.0 * f, 0.0); break;
                    }
                    break;
                case AMBER:
                    switch (cat) {
                        case WEAPON:  s.goldBonusPct += 2.00 * f; break;
                        case OFFHAND: s.itemDropPct += 1.00 * f; break;
                        case ARMOR:   s.matDropPct += 2.00 * f; break;
                        case JEWELRY:
                            s.goldBonusPct += 0.80 * f;
                            s.expBonusPct += 0.80 * f;
                            s.itemDropPct += 0.40 * f;
                            break;
                    }
                    break;
            }
        }

        /** Базовое значение первой компоненты основного бонуса (для обратного вывода уровня гнезда из скана) */
        public double primaryFirstBaseValue(SocketCategory cat) {
            switch (this) {
                case GARNET:
                    if (cat == SocketCategory.WEAPON) return 0.50;
                    if (cat == SocketCategory.JEWELRY) return 5.0;
                    return 0.60;
                case JADE:
                    if (cat == SocketCategory.WEAPON) return 0.50;
                    if (cat == SocketCategory.OFFHAND) return 0.30;
                    if (cat == SocketCategory.ARMOR) return 0.60;
                    return 5.0;
                case LAPIS:
                    if (cat == SocketCategory.WEAPON) return 0.25;
                    if (cat == SocketCategory.OFFHAND) return 0.30;
                    if (cat == SocketCategory.ARMOR) return 2.00;
                    return 5.0;
                case AMBER:
                    if (cat == SocketCategory.WEAPON) return 2.00;
                    if (cat == SocketCategory.OFFHAND) return 1.00;
                    if (cat == SocketCategory.ARMOR) return 2.00;
                    return 0.80;
            }
            return 1.0;
        }

        /** Форматирует точный текст основного бонуса при заданном множителе multFactor */
        public String formatPrimaryEffect(SocketCategory cat, double multFactor) {
            switch (this) {
                case GARNET:
                    switch (cat) {
                        case WEAPON:  return String.format(Locale.US, "+%.2f%% Урон атаки", 0.50 * multFactor);
                        case OFFHAND: return String.format(Locale.US, "+%.2f%% Макс. здоровье", 0.60 * multFactor);
                        case ARMOR:   return String.format(Locale.US, "+%.2f%% Защита", 0.60 * multFactor);
                        case JEWELRY: return String.format(Locale.US, "+%.0f Сила", 5.0 * multFactor);
                    }
                    break;
                case JADE:
                    switch (cat) {
                        case WEAPON:  return String.format(Locale.US, "+%.2f%% Урон питомца", 0.50 * multFactor);
                        case OFFHAND: return String.format(Locale.US, "+%.2f%% Урон атаки", 0.30 * multFactor);
                        case ARMOR:   return String.format(Locale.US, "+%.2f%% Макс. здоровье", 0.60 * multFactor);
                        case JEWELRY: return String.format(Locale.US, "+%.0f Ловкость", 5.0 * multFactor);
                    }
                    break;
                case LAPIS:
                    switch (cat) {
                        case WEAPON:
                            double v = 0.25 * multFactor;
                            return String.format(Locale.US, "+%.2f%% Атака / +%.2f%% Пет / +%.2f%% Возм.", v, v, v);
                        case OFFHAND: return String.format(Locale.US, "+%.2f%% Урон питомца", 0.30 * multFactor);
                        case ARMOR:   return String.format(Locale.US, "+%.2f%% Бонус опыта", 2.00 * multFactor);
                        case JEWELRY: return String.format(Locale.US, "+%.0f Интеллект", 5.0 * multFactor);
                    }
                    break;
                case AMBER:
                    switch (cat) {
                        case WEAPON:  return String.format(Locale.US, "+%.2f%% Бонус золота", 2.00 * multFactor);
                        case OFFHAND: return String.format(Locale.US, "+%.2f%% Шанс предметов", 1.00 * multFactor);
                        case ARMOR:   return String.format(Locale.US, "+%.2f%% Шанс материалов", 2.00 * multFactor);
                        case JEWELRY:
                            return String.format(Locale.US, "+%.2f%% Золото / +%.2f%% Опыт / +%.2f%% Предм.",
                                    0.80 * multFactor, 0.80 * multFactor, 0.40 * multFactor);
                    }
                    break;
            }
            return "";
        }
    }

    /** 5 редкостей самоцветов */
    public enum GemRarity {
        RAW("Необработанный", 1.0, 0, 10, -1),
        CUT("Огранённый", 1.6, 1, 25, 0),
        POLISHED("Полированный", 2.5, 1, 50, 1),
        BRILLIANT("Бриллиантовый", 4.0, 2, 100, 2),
        PERFECT("Совершенный", 6.5, 3, Integer.MAX_VALUE, 3);

        public final String ruName;
        public final double primaryMultiplier;
        public final int secondaryCount;
        public final int maxSocketLevel;
        public final int secTierIdx; // 0=О, 1=П, 2=Б, 3=С

        GemRarity(String ruName, double primaryMultiplier, int secondaryCount, int maxSocketLevel, int secTierIdx) {
            this.ruName = ruName;
            this.primaryMultiplier = primaryMultiplier;
            this.secondaryCount = secondaryCount;
            this.maxSocketLevel = maxSocketLevel;
            this.secTierIdx = secTierIdx;
        }
    }

    /** 10 второстепенных характеристик самоцветов из Кодекса самоцветов [О, П, Б, С] */
    public enum GemSecondaryStat {
        CRIT_DAMAGE("Крит. урон", true, new double[]{2.0, 4.0, 6.0, 10.0}),
        DOUBLE_HIT("Шанс двойного удара", true, new double[]{0.5, 1.0, 1.5, 2.5}),
        DOUBLE_DAMAGE("Шанс двойного урона", true, new double[]{0.5, 1.0, 1.5, 2.5}),
        CRIT_CHANCE("Шанс крита", true, new double[]{0.5, 1.0, 1.5, 2.5}),
        MAX_HEALTH("Макс. здоровье", false, new double[]{10.0, 25.0, 60.0, 150.0}),
        DODGE_CHANCE("Шанс уклонения", true, new double[]{0.3, 0.6, 0.9, 1.5}),
        LIFE_ON_HIT("Здоровье за удар", false, new double[]{1.0, 2.0, 4.0, 8.0}),
        LIFE_ON_KILL("Здоровье за убийство", false, new double[]{2.0, 4.0, 8.0, 16.0}),
        GOLD_GAIN("Бонус золота", true, new double[]{1.0, 2.0, 3.0, 5.0}),
        EXP_GAIN("Бонус опыта", true, new double[]{1.0, 2.0, 3.0, 5.0});

        public final String ruName;
        public final boolean isPercent;
        public final double[] tierBase; // индексы 0=О, 1=П, 2=Б, 3=С

        GemSecondaryStat(String ruName, boolean isPercent, double[] tierBase) {
            this.ruName = ruName;
            this.isPercent = isPercent;
            this.tierBase = tierBase;
        }

        public double valueAt(GemRarity rarity, double qualityPct, int socketLevel) {
            int idx = Math.max(0, rarity.secTierIdx);
            double q = (qualityPct > 0 ? qualityPct : 100.0) / 100.0;
            double sockMult = 1.0 + 0.04 * Math.max(0, socketLevel);
            return tierBase[idx] * q * sockMult;
        }

        public void applyToState(LiveBuildSimulator.HeroState s, double val, double sign) {
            double d = val * sign;
            switch (this) {
                case CRIT_DAMAGE:   s.critDamagePct += d; break;
                case DOUBLE_HIT:    s.doubleHitPct += d; break;
                case DOUBLE_DAMAGE: s.doubleDamagePct += d; break;
                case CRIT_CHANCE:   s.critChancePct += d; break;
                case MAX_HEALTH:    s.flatMaxHp += d; break;
                case DODGE_CHANCE:  s.dodgeRawPct += d; break;
                case LIFE_ON_HIT:   s.lifeOnHit += d; break;
                case LIFE_ON_KILL:  s.lifeOnKill += d; break;
                case GOLD_GAIN:     s.goldBonusPct += d; break;
                case EXP_GAIN:      s.expBonusPct += d; break;
            }
        }

        public String formatValue(double v) {
            if (isPercent) return String.format(Locale.US, "+%.2f%% %s", v, ruName);
            return String.format(Locale.US, "+%.1f %s", v, ruName);
        }
    }

    /** Один распознанный вторичный бонус на самоцвете */
    public static class ParsedGemSecondary {
        public final GemSecondaryStat stat;
        public final double value;

        public ParsedGemSecondary(GemSecondaryStat stat, double value) {
            this.stat = stat;
            this.value = value;
        }
    }

    /** Один распознанный самоцвет в гнезде */
    public static class ScannedGem {
        public final int socketIndex; // 1..4
        public final GemRarity rarity;
        public final GemFamily family;
        public final double qualityPct; // 80..115+
        public int socketLevel;         // 0..100+
        public double primaryRolledVal;
        public final boolean partialHeaderOffscreen;
        public final List<ParsedGemSecondary> secondaries = new ArrayList<>();

        public ScannedGem(int socketIndex, GemRarity rarity, GemFamily family,
                          double qualityPct, int socketLevel, boolean partialHeaderOffscreen) {
            this.socketIndex = socketIndex;
            this.rarity = rarity;
            this.family = family;
            this.qualityPct = qualityPct;
            this.socketLevel = socketLevel;
            this.partialHeaderOffscreen = partialHeaderOffscreen;
        }

        public double socketMultiplier() {
            return 1.0 + 0.04 * Math.max(0, socketLevel);
        }

        public double primaryMultiplierFactor() {
            double rMult = rarity != null ? rarity.primaryMultiplier : 4.0;
            double q = (qualityPct > 0 ? qualityPct : 100.0) / 100.0;
            return rMult * q * socketMultiplier();
        }
    }

    /** Оценка одного семейства или вторичного бонуса через LiveBuildSimulator */
    public static class RankedGemChoice<T> {
        public final T item;
        public final double dpsGainPct;
        public final double ehpGainPct;
        public final double farmGainPct;
        public final double totalGainPct;
        public final String summary;

        public RankedGemChoice(T item, double dpsGainPct, double ehpGainPct, double farmGainPct,
                               double totalGainPct, String summary) {
            this.item = item;
            this.dpsGainPct = dpsGainPct;
            this.ehpGainPct = ehpGainPct;
            this.farmGainPct = farmGainPct;
            this.totalGainPct = totalGainPct;
            this.summary = summary;
        }
    }

    private static final Pattern PCT_QUALITY_PAT = Pattern.compile("([0-9]{2,3}(?:\\.[0-9]+)?)\\s*%");
    private static final Pattern SOCKET_LVL_PAT = Pattern.compile("(?:ур\\.?|уровень\\s*гнезда)\\s*:?\\s*([0-9]{1,3})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SOCKET_MULT_PAT = Pattern.compile("[×xх]\\s*([1-9]\\.[0-9]{1,2})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PLUS_CHIP_SPLIT = Pattern.compile("(?=\\+\\s*[0-9ДдИиUuСсЗз])");

    // -------------------------------------------------------------------------
    // 1. ОДНОВРЕМЕННЫЙ СКАН САМОЦВЕТОВ ВНУТРИ КАРТОЧКИ ПРЕДМЕТА (НАД АФФИКСАМИ)
    // -------------------------------------------------------------------------

    /**
     * Извлекает самоцветы из верхней части карточки открытого предмета (строго ВЫШЕ базовой атаки/защиты и «АФФИКСЫ»)
     * и формирует живой математический блок рекомендаций по камням этого предмета!
     */
    public static String buildItemCardGemSection(List<IdleArcOcrEngine.OcrLine> ocrLines,
                                                 GearSlot slot,
                                                 boolean isEquipped,
                                                 LiveBuildSimulator.HeroState currentHeroState,
                                                 DruidProfile profile) {
        if (ocrLines == null || slot == null || slot == GearSlot.TALISMAN || slot == GearSlot.PET) {
            return "";
        }
        SocketCategory cat = SocketCategory.fromGearSlot(slot);
        int expectedMaxSockets = SocketCategory.maxSocketsForSlot(slot);
        if (expectedMaxSockets <= 0) return "";

        List<ScannedGem> gems = parseGemsAboveAffixesOnItemCard(ocrLines, cat);
        int refSocketLevel = 45;
        GemRarity refRarity = GemRarity.PERFECT;
        for (ScannedGem g : gems) {
            if (g.socketLevel > 0) refSocketLevel = g.socketLevel;
            if (g.rarity != null) refRarity = g.rarity;
        }
        if (gems.isEmpty() && !hasCollapsedSocketMention(ocrLines)) {
            return "";
        }

        BuildMode mode = profile != null ? profile.mode : BuildMode.PUSH;
        List<RankedGemChoice<GemFamily>> familyRank = rankFamiliesForCategory(
                currentHeroState, cat, refRarity, 100.0, refSocketLevel, mode);
        List<RankedGemChoice<GemSecondaryStat>> secRank = rankSecondaries(
                currentHeroState, refRarity, 100.0, refSocketLevel, mode);

        GemFamily bestFamily = familyRank.get(0).item;
        StringBuilder sb = new StringBuilder();
        sb.append("\n💎 ГНЁЗДА И КАМНИ НА ПРЕДМЕТЕ (скан выше аффиксов · ")
          .append(cat.shortName).append(", до ").append(expectedMaxSockets).append(" гн.):\n");

        if (gems.isEmpty()) {
            sb.append("• Секция гнёзд свёрнута или прокручена выше. Лучший камень сюда под «")
              .append(mode.title).append("»: ").append(bestFamily.badge)
              .append(" (").append(bestFamily.formatPrimaryEffect(cat, refRarity.primaryMultiplier * (1.0 + 0.04 * refSocketLevel)))
              .append(" → +").append(fmt2(familyRank.get(0).totalGainPct)).append("%).\n");
            sb.append("• Топ-3 вторичных бонуса на камнях: ")
              .append(formatTopSecondariesInline(secRank, 3)).append(".\n");
            return sb.toString();
        }

        // Формируем множество идеальных топ-K вторичных статов для быстрой проверки каждого камня
        for (int idx = 0; idx < gems.size(); idx++) {
            ScannedGem g = gems.get(idx);
            GemRarity effRar = g.rarity != null ? g.rarity : GemRarity.BRILLIANT;
            int neededSec = Math.max(1, effRar.secondaryCount);
            Set<GemSecondaryStat> topKSec = EnumSet.noneOf(GemSecondaryStat.class);
            for (int k = 0; k < Math.min(neededSec, secRank.size()); k++) {
                topKSec.add(secRank.get(k).item);
            }

            if (g.partialHeaderOffscreen) {
                sb.append("• Верхний камень (шапка выше кадра): видны доп. бонусы ");
                appendSecondaryListVerdict(sb, g, topKSec, secRank);
                continue;
            }

            double gemGain = evaluateFullGemContribution(currentHeroState, cat, g, isEquipped, mode);
            String qNote = g.qualityPct >= 100.5 ? " 🌟[БЕЗУПРЕЧНЫЙ >100%!]"
                    : (g.qualityPct > 0 && g.qualityPct < 90.0 ? " ⚠️[качество <90%]" : "");

            sb.append("• Гнездо #").append(g.socketIndex)
              .append(g.socketLevel > 0 ? " (ур." + g.socketLevel + ")" : "").append(": ")
              .append(effRar.ruName).append(" ")
              .append(g.family != null ? g.family.badge : "Самоцвет")
              .append(g.qualityPct > 0 ? String.format(Locale.US, " · %.0f%%", g.qualityPct) : "")
              .append(qNote)
              .append(" → вклад +").append(fmt2(gemGain)).append("%\n");

            if (g.family != null && g.family != bestFamily) {
                RankedGemChoice<GemFamily> curFamEval = findFamilyChoice(familyRank, g.family);
                double curFamPct = curFamEval != null ? curFamEval.totalGainPct : 0.0;
                double bestFamPct = familyRank.get(0).totalGainPct;
                boolean curIsTopDpsGem = (mode == BuildMode.PUSH || mode == BuildMode.BOSS)
                        && curFamEval != null && curFamEval.dpsGainPct > 0.01
                        && curFamEval.dpsGainPct >= familyRank.get(0).dpsGainPct;
                if (curIsTopDpsGem) {
                    sb.append("   ✅ Основной камень (").append(g.family.ruName)
                      .append(") даёт максимум чистого DPS в этом слоте (альтернатива на выживаемость: ")
                      .append(bestFamily.badge).append(").\n");
                } else if (bestFamPct - curFamPct > 0.08) {
                    sb.append("   🔄 По сборке «").append(mode.title).append("» выгоднее камень ")
                      .append(bestFamily.badge).append(" (")
                      .append(bestFamily.formatPrimaryEffect(cat, g.primaryMultiplierFactor()))
                      .append(" → +").append(fmt2(bestFamPct - curFamPct)).append("% к гнезду).\n");
                }
            } else if (g.family == bestFamily) {
                sb.append("   ✅ Основной камень (").append(bestFamily.ruName)
                  .append(") идеален под «").append(mode.title).append("».\n");
            }

            if (!g.secondaries.isEmpty()) {
                sb.append("   🔹 Доп. статы камня: ");
                appendSecondaryListVerdict(sb, g, topKSec, secRank);
            }
        }

        sb.append("• Лучшие доп. статы при переогранке под «").append(mode.title).append("»: ")
          .append(formatTopSecondariesInline(secRank, 3)).append(".\n");
        return sb.toString();
    }

    private static void appendSecondaryListVerdict(StringBuilder sb, ScannedGem g,
                                                   Set<GemSecondaryStat> topKSec,
                                                   List<RankedGemChoice<GemSecondaryStat>> secRank) {
        List<String> kept = new ArrayList<>();
        List<String> weak = new ArrayList<>();
        for (ParsedGemSecondary ps : g.secondaries) {
            // Также считаем стат хорошим, если он даёт >= 65% от K-го лучшего стата (например, Дв. урон при топ-2)
            double threshold = secRank.size() >= 3 ? secRank.get(2).totalGainPct * 0.75 : 0.05;
            RankedGemChoice<GemSecondaryStat> rc = findSecondaryChoice(secRank, ps.stat);
            double gain = rc != null ? rc.totalGainPct : 0.0;
            if (topKSec.contains(ps.stat) || (gain >= threshold && gain > 0.08)) {
                kept.add("✅ " + ps.stat.ruName);
            } else {
                weak.add("🔄 " + ps.stat.ruName);
            }
        }
        List<String> all = new ArrayList<>(kept);
        all.addAll(weak);
        sb.append(joinStrings(all, ", "));
        if (!weak.isEmpty()) {
            sb.append(" → можно переогранить слабые в ").append(secRank.get(0).item.ruName);
        }
        sb.append(".\n");
    }

    private static boolean hasCollapsedSocketMention(List<IdleArcOcrEngine.OcrLine> ocrLines) {
        for (IdleArcOcrEngine.OcrLine ol : ocrLines) {
            String low = DruidKnowledgeBase.normalizeForMatch(ol.text);
            if (low.contains("гнезд") || low.contains("гнlзд") || low.contains("управление гнезд")) {
                return true;
            }
        }
        return false;
    }

    /** Извлекает камни, расположенные в карточке предмета ВЫШЕ базового стата и блока «АФФИКСЫ» */
    public static List<ScannedGem> parseGemsAboveAffixesOnItemCard(List<IdleArcOcrEngine.OcrLine> ocrLines,
                                                                   SocketCategory cat) {
        List<ScannedGem> result = new ArrayList<>();
        int stopIdx = ocrLines.size();
        for (int i = 0; i < ocrLines.size(); i++) {
            String raw = ocrLines.get(i).text;
            String low = DruidKnowledgeBase.normalizeForMatch(raw);
            if (AffixPositionOptimizer.isAffixSectionHeaderLine(ocrLines.get(i), low, raw)
                    || low.contains("все классовые навыки")
                    || low.contains("выпало на") || low.contains("выоало на")
                    || low.startsWith("спутника") || low.contains("бонус дропа") || low.contains("бонус добычи")
                    || low.contains("двуручное") || low.contains("занимает слот")
                    || (i >= 3 && !raw.contains("%") && (low.contains("физический урон") || low.contains("защита"))
                        && extractGemNumber(raw) >= 300)) {
                stopIdx = i;
                break;
            }
        }

        ScannedGem current = null;
        boolean expectPrimaryNext = false;
        List<ParsedGemSecondary> orphanTopSecondaries = new ArrayList<>();

        for (int i = 0; i < stopIdx; i++) {
            String raw = ocrLines.get(i).text.trim();
            if (raw.isEmpty()) continue;
            String low = DruidKnowledgeBase.normalizeForMatch(raw);

            // Проверяем, не заголовок ли это самоцвета («Бриллиантовый Янтарь · 100%», «совершенныйлазурит.94%»)
            GemFamily fam = detectGemFamilyInHeader(low);
            GemRarity rar = detectGemRarityInHeader(low);
            if (fam != null && (rar != null || raw.contains("%"))) {
                if (!orphanTopSecondaries.isEmpty() && result.isEmpty() && current == null) {
                    ScannedGem partial = new ScannedGem(1, rar != null ? rar : GemRarity.PERFECT, null, 0.0, 0, true);
                    partial.secondaries.addAll(orphanTopSecondaries);
                    inferSocketLevelFromSecondaries(partial);
                    result.add(partial);
                    orphanTopSecondaries.clear();
                }
                double q = extractQualityPercent(raw);
                int sLvl = extractSocketLevel(low, raw);
                current = new ScannedGem(result.size() + 1,
                        rar != null ? rar : GemRarity.BRILLIANT, fam, q > 0 ? q : 100.0, sLvl, false);
                result.add(current);
                expectPrimaryNext = true;
                continue;
            }

            // Строки бонусов камня начинаются с «+»
            String trimmed = raw.replaceFirst("^[.•·\\-\\s]+", "");
            if (!trimmed.startsWith("+") && !raw.contains("+")) continue;
            if (i <= 2 && (low.contains("а5") || low.contains("а6") || low.contains("а4") || low.contains("аб +"))) {
                continue; // шапка «Оружие · А6 +30»
            }

            for (String chip : splitSideBySideChips(trimmed)) {
                String chipLow = DruidKnowledgeBase.normalizeForMatch(chip);
                double val = extractGemNumber(chip);
                if (expectPrimaryNext && current != null && isPrimaryGemStatLine(chipLow, current.family, cat)) {
                    current.primaryRolledVal = val;
                    if (current.socketLevel <= 0 && val > 0 && current.family != null) {
                        double base = current.family.primaryFirstBaseValue(cat);
                        double q = (current.qualityPct > 0 ? current.qualityPct : 100.0) / 100.0;
                        double rMult = current.rarity != null ? current.rarity.primaryMultiplier : 4.0;
                        double sockMult = val / Math.max(1e-6, base * rMult * q);
                        if (sockMult >= 1.0 && sockMult <= 15.0) {
                            current.socketLevel = (int) Math.round((sockMult - 1.0) / 0.04);
                        }
                    }
                    expectPrimaryNext = false;
                    continue;
                }
                expectPrimaryNext = false;

                GemSecondaryStat sec = classifyGemSecondaryStat(chipLow);
                if (sec != null) {
                    ParsedGemSecondary ps = new ParsedGemSecondary(sec, val);
                    if (current != null) {
                        current.secondaries.add(ps);
                    } else if (i >= 3) {
                        orphanTopSecondaries.add(ps);
                    }
                }
            }
        }

        if (!orphanTopSecondaries.isEmpty() && result.isEmpty()) {
            ScannedGem partial = new ScannedGem(1, GemRarity.PERFECT, null, 0.0, 0, true);
            partial.secondaries.addAll(orphanTopSecondaries);
            inferSocketLevelFromSecondaries(partial);
            result.add(partial);
        }

        for (ScannedGem g : result) {
            if (g.socketLevel <= 0) {
                inferSocketLevelFromSecondaries(g);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // 2. СПЕЦИАЛЬНОЕ ОКНО КОНКРЕТНОГО СЛОТА «Гнёзда · Оружие / Факел / ...»
    // -------------------------------------------------------------------------

    public static boolean isSlotSocketsModal(String lowAll, List<String> lines) {
        if (lowAll.contains("обзор гнезд") || lowAll.contains("кодекс самоцветов") || lowAll.contains("как это раб")) {
            return false;
        }
        int socketHeaders = 0;
        for (String l : lines) {
            String low = DruidKnowledgeBase.normalizeForMatch(l);
            if (low.startsWith("гнездо 1") || low.startsWith("гнездо 2") || low.startsWith("гнездо 3")
                    || low.startsWith("гнездо з") || low.startsWith("гнездо 4")
                    || low.contains("гнездо ур.") || low.contains("шездо ур.")) {
                socketHeaders++;
            }
        }
        return socketHeaders >= 2;
    }

    public static ScreenAnalyzer.ScanResult analyzeSlotSocketsModal(List<IdleArcOcrEngine.OcrLine> ocrLines,
                                                                    DruidProfile profile) {
        ScreenAnalyzer.ScanResult res = new ScreenAnalyzer.ScanResult();
        GearSlot slot = detectSlotInSocketHeader(ocrLines);
        SocketCategory cat = SocketCategory.fromGearSlot(slot);
        BuildMode mode = profile != null ? profile.mode : BuildMode.PUSH;

        List<ScannedGem> gems = new ArrayList<>();
        ScannedGem current = null;
        int curSocketLvl = 0;

        for (IdleArcOcrEngine.OcrLine ol : ocrLines) {
            String raw = ol.text.trim();
            if (raw.isEmpty()) continue;
            String low = DruidKnowledgeBase.normalizeForMatch(raw);

            if (low.startsWith("гнездо ") && !low.contains("-")) {
                int sLvl = extractSocketLevel(low, raw);
                if (sLvl > 0) curSocketLvl = sLvl;
                continue;
            }
            if (low.contains("гнездо ур.") && raw.contains("-")) {
                continue; // строка стоимости апгрейда «Гнездо ур. 46 -> 47 · 13.82qa»
            }

            GemFamily fam = detectGemFamilyInHeader(low);
            GemRarity rar = detectGemRarityInHeader(low);
            if (fam != null && (rar != null || raw.contains("%"))) {
                double q = extractQualityPercent(raw);
                current = new ScannedGem(gems.size() + 1,
                        rar != null ? rar : GemRarity.PERFECT, fam, q > 0 ? q : 100.0, curSocketLvl, false);
                gems.add(current);
                continue;
            }

            if (current != null && raw.contains("+")) {
                for (String chip : splitSideBySideChips(raw)) {
                    String cLow = DruidKnowledgeBase.normalizeForMatch(chip);
                    double val = extractGemNumber(chip);
                    if (current.primaryRolledVal <= 0 && isPrimaryGemStatLine(cLow, current.family, cat)
                            && classifyGemSecondaryStat(cLow) == null) {
                        current.primaryRolledVal = val;
                        continue;
                    }
                    GemSecondaryStat sec = classifyGemSecondaryStat(cLow);
                    if (sec != null && current.secondaries.size() < Math.max(1, current.rarity.secondaryCount)) {
                        // Не путаем основной бонус с вторичным, если название совпадает (например Макс. здоровье в Факеле/Броне)
                        if (current.primaryRolledVal <= 0 && isPrimaryGemStatLine(cLow, current.family, cat)
                                && current.secondaries.isEmpty() && chip.contains("%") && !sec.isPercent) {
                            current.primaryRolledVal = val;
                            continue;
                        }
                        current.secondaries.add(new ParsedGemSecondary(sec, val));
                    }
                }
            }
        }

        int refSocketLvl = curSocketLvl > 0 ? curSocketLvl : 45;
        double scaleEst = Math.max(10.0, 1.0 + refSocketLvl * 0.85);
        LiveBuildSimulator.HeroState hero = LiveBuildSimulator.buildHeroState(profile, scaleEst, 0.0, 0.0);

        List<RankedGemChoice<GemFamily>> famRank = rankFamiliesForCategory(
                hero, cat, GemRarity.PERFECT, 100.0, refSocketLvl, mode);
        List<RankedGemChoice<GemSecondaryStat>> secRank = rankSecondaries(
                hero, GemRarity.PERFECT, 100.0, refSocketLvl, mode);
        GemFamily bestFamily = famRank.get(0).item;

        int subOptimalGems = 0;
        int weakSecCount = 0;
        Set<GemSecondaryStat> top3Sec = EnumSet.noneOf(GemSecondaryStat.class);
        for (int k = 0; k < Math.min(3, secRank.size()); k++) top3Sec.add(secRank.get(k).item);

        StringBuilder sb = new StringBuilder();
        String slotLabel = slot != null ? slot.shortChip : cat.shortName;
        sb.append("💎 РАЗБОР ГНЁЗД СЛОТА: ").append(slotLabel)
          .append(" · Режим «").append(mode.title).append("»\n");
        sb.append("• Уровень гнёзд: ур. ").append(refSocketLvl)
          .append(String.format(Locale.US, " (множитель гнезда ×%.2f)\n", 1.0 + 0.04 * refSocketLvl));

        for (ScannedGem g : gems) {
            if (g.socketLevel <= 0) g.socketLevel = refSocketLvl;
            double totalGain = evaluateFullGemContribution(hero, cat, g, true, mode);
            boolean familyOk = (g.family == bestFamily);
            if (!familyOk) subOptimalGems++;

            sb.append("\n━━ Гнездо #").append(g.socketIndex)
              .append(" [").append(g.rarity.ruName).append(" ").append(g.family.badge)
              .append(String.format(Locale.US, " · %.0f%%", g.qualityPct)).append("] ━━\n");
            sb.append("  • Основной эффект: ")
              .append(g.family.formatPrimaryEffect(cat, g.primaryMultiplierFactor()))
              .append(" (общий вклад камня: +").append(fmt2(totalGain)).append("%)\n");

            if (g.qualityPct < 90.0) {
                sb.append("  ⚠️ Качество ").append(String.format(Locale.US, "%.0f%%", g.qualityPct))
                  .append(" просело: замена на камень 98–100%+ усилит ВСЕ статы этого гнезда на +")
                  .append(String.format(Locale.US, "%.0f%%", (100.0 / Math.max(50.0, g.qualityPct) - 1.0) * 100.0))
                  .append(".\n");
            } else if (g.qualityPct > 100.0) {
                sb.append("  🌟 Безупречный самоцвет (>100%) — обязательно сохрани и доводи переогранкой!\n");
            }

            if (g.socketLevel >= g.rarity.maxSocketLevel && g.rarity != GemRarity.PERFECT) {
                sb.append("  ⛔ Достигнут потолок уровня гнезда (").append(g.rarity.maxSocketLevel)
                  .append(") для редкости «").append(g.rarity.ruName)
                  .append("» — для дальнейшего роста нужен более редкий камень.\n");
            }

            if (!familyOk) {
                sb.append("  🔄 Под «").append(mode.title).append("» лучший основной камень здесь: ")
                  .append(bestFamily.badge).append(" (")
                  .append(bestFamily.formatPrimaryEffect(cat, g.primaryMultiplierFactor())).append(").\n");
            } else {
                sb.append("  ✅ Семейство камня (").append(g.family.ruName).append(") оптимально для «")
                  .append(mode.title).append("».\n");
            }

            if (!g.secondaries.isEmpty()) {
                int badHere = 0;
                sb.append("  • Вторичные статы (").append(g.secondaries.size()).append("/")
                  .append(g.rarity.secondaryCount).append("):\n");
                for (ParsedGemSecondary ps : g.secondaries) {
                    RankedGemChoice<GemSecondaryStat> rc = findSecondaryChoice(secRank, ps.stat);
                    double gPct = rc != null ? rc.totalGainPct : 0.0;
                    boolean good = top3Sec.contains(ps.stat)
                            || (secRank.size() >= 3 && gPct >= secRank.get(2).totalGainPct * 0.75 && gPct > 0.08);
                    if (!good) {
                        badHere++;
                        weakSecCount++;
                    }
                    double exactVal = ps.stat.valueAt(g.rarity, g.qualityPct, g.socketLevel);
                    sb.append("     ").append(good ? "✅ " : "🔄 ")
                      .append(ps.stat.formatValue(exactVal))
                      .append(" → +").append(fmt2(gPct)).append("%\n");
                }
                if (badHere > 0) {
                    sb.append("  ⚒️ Переогранка: выгодно поймать ")
                      .append(formatTopSecondariesInline(secRank, Math.min(3, g.rarity.secondaryCount))).append(".\n");
                }
            }
        }

        sb.append("\n📐 СРАВНЕНИЕ ВСЕХ 4 КАМНЕЙ В СЛОТЕ «").append(cat.shortName).append("» (ур.").append(refSocketLvl).append("):\n");
        for (int i = 0; i < famRank.size(); i++) {
            RankedGemChoice<GemFamily> fc = famRank.get(i);
            sb.append("  ").append(i + 1).append(") ").append(fc.item.badge).append(": ")
              .append(fc.summary).append(" → итог +").append(fmt2(fc.totalGainPct)).append("%\n");
        }
        sb.append("\n🎯 ТОП ДОП. БОНУСОВ НА КАМНЯХ ПОД «").append(mode.title).append("»:\n  ")
          .append(formatTopSecondariesInline(secRank, 4));

        res.screenType = "Гнёзда слота · " + slotLabel;
        res.detectedSlot = slot;
        if (subOptimalGems == 0 && weakSecCount == 0) {
            res.color = ScreenAnalyzer.VerdictColor.GOOD;
            res.shortBadge = "✅ Гнёзда " + slotLabel + ": ОПТИМУМ";
            res.headline = "💎 " + slotLabel + ": камни и доп. бонусы оптимальны под «" + mode.title + "»";
        } else if (subOptimalGems > 0) {
            res.color = ScreenAnalyzer.VerdictColor.WARN;
            res.shortBadge = "💎 " + slotLabel + ": лучше " + bestFamily.ruName;
            res.headline = "💎 " + slotLabel + ": под «" + mode.title + "» лучше " + bestFamily.badge
                    + " (доп. статов под переогранку: " + weakSecCount + ")";
        } else {
            res.color = ScreenAnalyzer.VerdictColor.WARN;
            res.shortBadge = "💎 " + slotLabel + ": переограни " + weakSecCount + " ст.";
            res.headline = "💎 " + slotLabel + ": камни верные (" + bestFamily.ruName
                    + "), можно усилить " + weakSecCount + " доп. бонус(ов) переогранкой";
        }
        res.details = sb.toString();
        return res;
    }

    // -------------------------------------------------------------------------
    // 3. ОКНО «ОБЗОР ГНЁЗД» (ВСЕ 10 СЛОТОВ / ДО 20 ГНЁЗД)
    // -------------------------------------------------------------------------

    public static ScreenAnalyzer.ScanResult analyzeSocketsOverview(List<IdleArcOcrEngine.OcrLine> ocrLines,
                                                                   int[] argb, int w, int h,
                                                                   DruidProfile profile) {
        ScreenAnalyzer.ScanResult res = new ScreenAnalyzer.ScanResult();
        BuildMode mode = profile != null ? profile.mode : BuildMode.PUSH;

        int totalSocketsFound = 0;
        int maxSocketLevel = 0;
        double sumQuality = 0.0;
        int qualityCount = 0;
        List<String> lowQualityAlerts = new ArrayList<>();
        List<String> flawlessHighlights = new ArrayList<>();

        String pendingSlotName = null;
        for (IdleArcOcrEngine.OcrLine ol : ocrLines) {
            String raw = ol.text.trim();
            String low = DruidKnowledgeBase.normalizeForMatch(raw);
            String slotTitle = matchOverviewSlotTitle(low);
            if (slotTitle != null && !low.contains("уров")) {
                pendingSlotName = slotTitle;
                continue;
            }
            if (pendingSlotName != null && (low.contains("уров") || low.contains("гнезд") || raw.contains("%"))) {
                Matcher pm = PCT_QUALITY_PAT.matcher(raw);
                int idxInSlot = 0;
                while (pm.find()) {
                    double q = Double.parseDouble(pm.group(1));
                    if (q >= 50.0 && q <= 150.0) {
                        idxInSlot++;
                        totalSocketsFound++;
                        sumQuality += q;
                        qualityCount++;
                        if (q < 90.0) {
                            lowQualityAlerts.add(pendingSlotName + " #" + idxInSlot + " (" + String.format(Locale.US, "%.0f%%", q) + ")");
                        } else if (q > 100.0) {
                            flawlessHighlights.add(pendingSlotName + " #" + idxInSlot + " (" + String.format(Locale.US, "%.0f%%", q) + " 🌟)");
                        }
                    }
                }
                int sLvl = extractSocketLevelFromOverviewLine(raw);
                if (sLvl > maxSocketLevel) maxSocketLevel = sLvl;
                pendingSlotName = null;
            }
        }

        int refLvl = maxSocketLevel > 0 ? maxSocketLevel : 45;
        double avgQ = qualityCount > 0 ? (sumQuality / qualityCount) : 95.0;

        StringBuilder sb = new StringBuilder();
        sb.append("💎 СВОДНЫЙ АУДИТ «ОБЗОР ГНЁЗД» (").append(mode.title).append(")\n");
        if (qualityCount > 0) {
            sb.append("• Считано камней на экране: ").append(totalSocketsFound)
              .append(" · Уровень гнёзд: ").append(refLvl)
              .append(String.format(Locale.US, " (×%.2f) · Среднее качество: %.1f%%\n", 1.0 + 0.04 * refLvl, avgQ));
        }
        if (!flawlessHighlights.isEmpty()) {
            sb.append("• 🌟 Безупречные камни (>100% качества — храни навсегда!): ")
              .append(joinStrings(flawlessHighlights, ", ")).append("\n");
        }
        if (!lowQualityAlerts.isEmpty()) {
            sb.append("• ⚠️ Кандидаты на замену в первую очередь (качество <90%): ")
              .append(joinStrings(lowQualityAlerts, ", ")).append("\n");
        }
        sb.append("\n").append(buildOptimalGemBlueprint(profile, refLvl));

        res.screenType = "Обзор гнёзд";
        res.color = lowQualityAlerts.size() > 4 ? ScreenAnalyzer.VerdictColor.WARN : ScreenAnalyzer.VerdictColor.GOOD;
        res.shortBadge = String.format(Locale.US, "💎 Гнёзда ур.%d · ср.%.0f%%", refLvl, avgQ);
        res.headline = String.format(Locale.US,
                "💎 Обзор гнёзд (ур.%d · ср. кач-во %.1f%%) — раскладка под «%s»",
                refLvl, avgQ, mode.title);
        res.details = sb.toString();
        return res;
    }

    // -------------------------------------------------------------------------
    // 4. ЖИВОЙ СПРАВОЧНИК ДЛЯ КОДЕКСА САМОЦВЕТОВ, MAINACTIVITY И ОВЕРЛЕЯ
    // -------------------------------------------------------------------------

    public static String buildOptimalGemBlueprint(DruidProfile profile, int refSocketLevel) {
        BuildMode mode = profile != null ? profile.mode : BuildMode.PUSH;
        int sLvl = refSocketLevel > 0 ? refSocketLevel : 46;
        LiveBuildSimulator.HeroState hero = LiveBuildSimulator.buildHeroState(
                profile, Math.max(10.0, 1.0 + sLvl * 0.85), 0.0, 0.0);

        StringBuilder sb = new StringBuilder();
        sb.append("🏆 ЛУЧШИЕ КАМНИ ПО СЛОТАМ ПОД «").append(mode.title).append("» (расчёт на ур.").append(sLvl).append("):\n");

        for (SocketCategory cat : SocketCategory.values()) {
            List<RankedGemChoice<GemFamily>> ranked = rankFamiliesForCategory(
                    hero, cat, GemRarity.PERFECT, 100.0, sLvl, mode);
            RankedGemChoice<GemFamily> best = ranked.get(0);
            RankedGemChoice<GemFamily> alt = ranked.get(1);
            sb.append("• ").append(cat.shortName).append(": ")
              .append(best.item.badge).append(" (").append(best.summary)
              .append(" → +").append(fmt2(best.totalGainPct)).append("%)");
            if (alt.totalGainPct > 0.05) {
                sb.append(" · альт: ").append(alt.item.badge)
                  .append(" (+").append(fmt2(alt.totalGainPct)).append("%)");
            }
            sb.append("\n");
        }

        List<RankedGemChoice<GemSecondaryStat>> secRank = rankSecondaries(
                hero, GemRarity.PERFECT, 100.0, sLvl, mode);
        sb.append("\n🎯 ЛУЧШИЕ ДОП. БОНУСЫ НА КАМНЯХ (3 слота на Совершенном, без дублей):\n");
        int topShow = Math.min(5, secRank.size());
        for (int i = 0; i < topShow; i++) {
            RankedGemChoice<GemSecondaryStat> sc = secRank.get(i);
            double val = sc.item.valueAt(GemRarity.PERFECT, 100.0, sLvl);
            sb.append("  ").append(i + 1).append(") ").append(sc.item.formatValue(val))
              .append(" → +").append(fmt2(sc.totalGainPct)).append("%\n");
        }

        if (profile != null && profile.hasScannedStats && profile.dodgeRaw >= 95.0) {
            sb.append("⚠️ Уклонение уже закрыто (").append(String.format(Locale.US, "%.1f%%", profile.dodgeRaw))
              .append(" >= 95%): доп. бонус «Шанс уклонения» на камнях даёт 0% выживаемости, а в Украшениях 🔵 Лазурит (Интеллект) сильнее 🟢 Нефрита (Ловкость)!\n");
        }
        if (profile != null && profile.hasScannedStats && profile.critChance >= 100.0) {
            sb.append("💡 При Шансе крита >= 100% доп. стат «Крит. урон» на камнях имеет базу +10% против +2.5% у «Шанса крита» (в 4 раза выгоднее на камне!), а «Шанс двойного удара» на камнях работает во ВСЕХ 20 гнёздах, включая Броню и Факел!");
        } else {
            sb.append("💡 Важная фишка гнёзд: «Шанс двойного удара» на вторичных статах самоцветов работает во ВСЕХ 20 гнёздах (даже в Шлеме, Торсе, Перчатках, Сапогах и Факеле, где его нет в обычных аффиксах)!");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // МАТЕМАТИЧЕСКОЕ ЯДРО ОЦЕНКИ ЧЕРЕЗ LiveBuildSimulator
    // -------------------------------------------------------------------------

    public static List<RankedGemChoice<GemFamily>> rankFamiliesForCategory(
            LiveBuildSimulator.HeroState baseState,
            SocketCategory cat,
            GemRarity rarity,
            double qualityPct,
            int socketLevel,
            BuildMode mode) {

        GemRarity rar = rarity != null ? rarity : GemRarity.PERFECT;
        double q = (qualityPct > 0 ? qualityPct : 100.0) / 100.0;
        double multFactor = rar.primaryMultiplier * q * (1.0 + 0.04 * Math.max(0, socketLevel));

        double baseDps = LiveBuildSimulator.computeDps(baseState, mode);
        double baseEhp = LiveBuildSimulator.computeEhp(baseState);
        double baseFarm = LiveBuildSimulator.computeFarmMultiplier(baseState, mode);
        double baseObj = LiveBuildSimulator.computeTotalObjective(baseState, mode);

        List<RankedGemChoice<GemFamily>> out = new ArrayList<>();
        for (GemFamily fam : GemFamily.values()) {
            LiveBuildSimulator.HeroState after = baseState.copy();
            fam.applyPrimaryToState(after, cat, multFactor, +1.0);
            double dDps = ((LiveBuildSimulator.computeDps(after, mode) / baseDps) - 1.0) * 100.0;
            double dEhp = ((LiveBuildSimulator.computeEhp(after) / baseEhp) - 1.0) * 100.0;
            double dFarm = ((LiveBuildSimulator.computeFarmMultiplier(after, mode) / baseFarm) - 1.0) * 100.0;
            double dObj = ((LiveBuildSimulator.computeTotalObjective(after, mode) / baseObj) - 1.0) * 100.0;
            out.add(new RankedGemChoice<>(fam, dDps, dEhp, dFarm, dObj,
                    fam.formatPrimaryEffect(cat, multFactor)));
        }
        Collections.sort(out, new Comparator<RankedGemChoice<GemFamily>>() {
            @Override
            public int compare(RankedGemChoice<GemFamily> a, RankedGemChoice<GemFamily> b) {
                return Double.compare(b.totalGainPct, a.totalGainPct);
            }
        });
        return out;
    }

    public static List<RankedGemChoice<GemSecondaryStat>> rankSecondaries(
            LiveBuildSimulator.HeroState baseState,
            GemRarity rarity,
            double qualityPct,
            int socketLevel,
            BuildMode mode) {

        GemRarity rar = (rarity != null && rarity.secTierIdx >= 0) ? rarity : GemRarity.PERFECT;
        double baseDps = LiveBuildSimulator.computeDps(baseState, mode);
        double baseEhp = LiveBuildSimulator.computeEhp(baseState);
        double baseFarm = LiveBuildSimulator.computeFarmMultiplier(baseState, mode);
        double baseObj = LiveBuildSimulator.computeTotalObjective(baseState, mode);

        List<RankedGemChoice<GemSecondaryStat>> out = new ArrayList<>();
        for (GemSecondaryStat st : GemSecondaryStat.values()) {
            double val = st.valueAt(rar, qualityPct, socketLevel);
            LiveBuildSimulator.HeroState after = baseState.copy();
            st.applyToState(after, val, +1.0);
            double dDps = ((LiveBuildSimulator.computeDps(after, mode) / baseDps) - 1.0) * 100.0;
            double dEhp = ((LiveBuildSimulator.computeEhp(after) / baseEhp) - 1.0) * 100.0;
            double dFarm = ((LiveBuildSimulator.computeFarmMultiplier(after, mode) / baseFarm) - 1.0) * 100.0;
            double dObj = ((LiveBuildSimulator.computeTotalObjective(after, mode) / baseObj) - 1.0) * 100.0;
            out.add(new RankedGemChoice<>(st, dDps, dEhp, dFarm, dObj, st.formatValue(val)));
        }
        Collections.sort(out, new Comparator<RankedGemChoice<GemSecondaryStat>>() {
            @Override
            public int compare(RankedGemChoice<GemSecondaryStat> a, RankedGemChoice<GemSecondaryStat> b) {
                return Double.compare(b.totalGainPct, a.totalGainPct);
            }
        });
        return out;
    }

    private static double evaluateFullGemContribution(LiveBuildSimulator.HeroState currentState,
                                                      SocketCategory cat,
                                                      ScannedGem gem,
                                                      boolean isEquipped,
                                                      BuildMode mode) {
        LiveBuildSimulator.HeroState without = currentState.copy();
        double mult = gem.primaryMultiplierFactor();
        GemRarity effRar = gem.rarity != null ? gem.rarity : GemRarity.BRILLIANT;
        if (isEquipped) {
            if (gem.family != null) {
                gem.family.applyPrimaryToState(without, cat, mult, -1.0);
            }
            for (ParsedGemSecondary ps : gem.secondaries) {
                double v = ps.value > 0 ? ps.value : ps.stat.valueAt(effRar, gem.qualityPct, gem.socketLevel);
                ps.stat.applyToState(without, v, -1.0);
            }
        }
        LiveBuildSimulator.HeroState with = without.copy();
        if (gem.family != null) {
            gem.family.applyPrimaryToState(with, cat, mult, +1.0);
        }
        for (ParsedGemSecondary ps : gem.secondaries) {
            double v = ps.value > 0 ? ps.value : ps.stat.valueAt(effRar, gem.qualityPct, gem.socketLevel);
            ps.stat.applyToState(with, v, +1.0);
        }
        double o0 = LiveBuildSimulator.computeTotalObjective(without, mode);
        double o1 = LiveBuildSimulator.computeTotalObjective(with, mode);
        return Math.max(0.0, ((o1 / Math.max(1e-9, o0)) - 1.0) * 100.0);
    }

    // -------------------------------------------------------------------------
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ КЛАССИФИКАЦИИ СТРОК САМОЦВЕТОВ
    // -------------------------------------------------------------------------

    static GemFamily detectGemFamilyInHeader(String low) {
        if (low == null) return null;
        if (low.contains("гранат")) return GemFamily.GARNET;
        if (low.contains("нефрит")) return GemFamily.JADE;
        if (low.contains("лазурит")) return GemFamily.LAPIS;
        if (low.contains("янтар")) return GemFamily.AMBER;
        return null;
    }

    static GemRarity detectGemRarityInHeader(String low) {
        if (low == null) return null;
        if (low.contains("совершен")) return GemRarity.PERFECT;
        if (low.contains("бриллиант") || low.contains("ллиантов")) return GemRarity.BRILLIANT;
        if (low.contains("полирован") || low.contains("олирован")) return GemRarity.POLISHED;
        if (low.contains("огранен") || low.contains("огранён")) return GemRarity.CUT;
        if (low.contains("необработ") || low.contains("работан")) return GemRarity.RAW;
        return null;
    }

    static GemSecondaryStat classifyGemSecondaryStat(String low) {
        if (low == null) return null;
        if (low.contains("выпадени") || low.contains("атаки") || low.contains("питомц")
                || low.contains("возмезд") || low.contains("сила") || low.contains("ловкост")
                || low.contains("интеллект") || low.contains("инталлект") || low.contains("защит")) {
            return null; // это основные статы камней, а не вторичные
        }
        if (low.contains("двойного удар") || low.contains("двсйного удар")) return GemSecondaryStat.DOUBLE_HIT;
        if (low.contains("двойного урон") || low.contains("двсйного урон")) return GemSecondaryStat.DOUBLE_DAMAGE;
        if (low.contains("крит. урон") || low.contains("крит урон") || low.contains("критический урон")) {
            return GemSecondaryStat.CRIT_DAMAGE;
        }
        if (low.contains("шанс крит")) return GemSecondaryStat.CRIT_CHANCE;
        if (low.contains("за убийств") || low.contains("за убииств")) return GemSecondaryStat.LIFE_ON_KILL;
        if (low.contains("за удар")) return GemSecondaryStat.LIFE_ON_HIT;
        if (low.contains("уклонен")) return GemSecondaryStat.DODGE_CHANCE;
        if (low.contains("здоров")) return GemSecondaryStat.MAX_HEALTH;
        if (low.contains("золот") || low.contains("зслст")) return GemSecondaryStat.GOLD_GAIN;
        if (low.contains("опыт")) return GemSecondaryStat.EXP_GAIN;
        return null;
    }

    private static boolean isPrimaryGemStatLine(String low, GemFamily fam, SocketCategory cat) {
        if (fam == null || low == null) return false;
        switch (fam) {
            case GARNET:
                if (cat == SocketCategory.WEAPON) return low.contains("атак");
                if (cat == SocketCategory.OFFHAND) return low.contains("здоров");
                if (cat == SocketCategory.ARMOR) return low.contains("защит");
                return low.contains("сил");
            case JADE:
                if (cat == SocketCategory.WEAPON) return low.contains("питомц");
                if (cat == SocketCategory.OFFHAND) return low.contains("атак");
                if (cat == SocketCategory.ARMOR) return low.contains("здоров");
                return low.contains("ловкост");
            case LAPIS:
                if (cat == SocketCategory.WEAPON) return low.contains("атак") || low.contains("питомц") || low.contains("возмезд");
                if (cat == SocketCategory.OFFHAND) return low.contains("питомц");
                if (cat == SocketCategory.ARMOR) return low.contains("опыт");
                return low.contains("интеллект") || low.contains("инталлект");
            case AMBER:
                if (cat == SocketCategory.WEAPON) return low.contains("золот");
                if (cat == SocketCategory.OFFHAND) return low.contains("предмет");
                if (cat == SocketCategory.ARMOR) return low.contains("материал");
                return low.contains("золот") || low.contains("предмет");
        }
        return false;
    }

    private static List<String> splitSideBySideChips(String raw) {
        String[] parts = PLUS_CHIP_SPLIT.split(raw);
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (t.startsWith("+") && t.length() >= 4) {
                out.add(t);
            }
        }
        if (out.isEmpty() && raw.contains("+")) {
            out.add(raw);
        }
        return out;
    }

    private static double extractQualityPercent(String raw) {
        Matcher m = PCT_QUALITY_PAT.matcher(raw);
        double found = 0.0;
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1));
                if (v >= 50.0 && v <= 160.0) found = v;
            } catch (Exception ignored) {
            }
        }
        return found;
    }

    private static int extractSocketLevel(String low, String raw) {
        Matcher m = SOCKET_LVL_PAT.matcher(low);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        Matcher mm = SOCKET_MULT_PAT.matcher(raw);
        if (mm.find()) {
            try {
                double mult = Double.parseDouble(mm.group(1));
                if (mult >= 1.0) return (int) Math.round((mult - 1.0) / 0.04);
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static int extractSocketLevelFromOverviewLine(String raw) {
        String noPct = raw.replaceAll("[0-9]{2,3}\\s*%", " ").replaceAll("[0-9]/[0-9]", " ");
        Matcher m = Pattern.compile("\\b([1-9][0-9]{0,2})\\b").matcher(noPct);
        int best = 0;
        while (m.find()) {
            int v = Integer.parseInt(m.group(1));
            if (v >= 1 && v <= 200) {
                best = v;
                break;
            }
        }
        return best;
    }

    private static double extractGemNumber(String raw) {
        if (raw == null) return 0.0;
        // Убираем пробелы внутри разрядов мелкого шрифта камней («+1 3.1 6%» -> «+13.16%», «+Д.26%» -> «+4.26%»)
        String cleaned = raw.replace("+Д.", "+4.").replace("+д.", "+4.");
        cleaned = cleaned.replaceAll("(?<=\\d)\\s+(?=\\d)", "");
        cleaned = cleaned.replaceAll("(?<=\\d)\\s*\\.\\s*(?=\\d)", ".");
        return DruidKnowledgeBase.extractFirstNumber(cleaned);
    }

    private static void inferSocketLevelFromSecondaries(ScannedGem gem) {
        if (gem.secondaries.isEmpty()) return;
        GemRarity rar = (gem.rarity != null && gem.rarity.secTierIdx >= 0) ? gem.rarity : GemRarity.PERFECT;
        double q = (gem.qualityPct > 0 ? gem.qualityPct : 95.0) / 100.0;
        for (ParsedGemSecondary ps : gem.secondaries) {
            if (ps.value <= 0) continue;
            double base = ps.stat.tierBase[rar.secTierIdx];
            double mult = ps.value / Math.max(1e-6, base * q);
            if (mult >= 1.0 && mult <= 12.0) {
                gem.socketLevel = (int) Math.round((mult - 1.0) / 0.04);
                return;
            }
        }
    }

    private static GearSlot detectSlotInSocketHeader(List<IdleArcOcrEngine.OcrLine> ocrLines) {
        int lim = Math.min(3, ocrLines.size());
        for (int i = 0; i < lim; i++) {
            String low = DruidKnowledgeBase.normalizeForMatch(ocrLines.get(i).text);
            if (low.contains("оружи") || low.contains("сружи")) return GearSlot.WEAPON;
            if (low.contains("факел") || low.contains("щит") || low.contains("оффхенд")) return GearSlot.TORCH;
            if (low.contains("шлем")) return GearSlot.HELMET;
            if (low.contains("брон") || low.contains("нагруд")) return GearSlot.CHEST;
            if (low.contains("перчат")) return GearSlot.GLOVES;
            if (low.contains("сапог")) return GearSlot.BOOTS;
            if (low.contains("пояс")) return GearSlot.BELT;
            if (low.contains("амулет")) return GearSlot.AMULET;
            if (low.contains("кольц")) return GearSlot.RING;
        }
        return GearSlot.WEAPON;
    }

    private static String matchOverviewSlotTitle(String low) {
        if (low.startsWith("оружи")) return "⚔️ Оружие";
        if (low.startsWith("факел")) return "🔥 Факел";
        if (low.startsWith("шлем")) return "🪖 Шлем";
        if (low.startsWith("брон")) return "🛡️ Броня";
        if (low.startsWith("сапог")) return "🥾 Сапоги";
        if (low.startsWith("перчат")) return "🧤 Перчатки";
        if (low.startsWith("пояс")) return "🎗️ Пояс";
        if (low.startsWith("амулет")) return "📿 Амулет";
        if (low.startsWith("кольц") && (low.contains("1") || low.contains("01"))) return "💍 Кольцо 1";
        if (low.startsWith("кольц") && (low.contains("2") || low.contains("02"))) return "💍 Кольцо 2";
        if (low.startsWith("кольц")) return "💍 Кольцо";
        return null;
    }

    private static RankedGemChoice<GemFamily> findFamilyChoice(List<RankedGemChoice<GemFamily>> list, GemFamily f) {
        for (RankedGemChoice<GemFamily> c : list) {
            if (c.item == f) return c;
        }
        return null;
    }

    private static RankedGemChoice<GemSecondaryStat> findSecondaryChoice(List<RankedGemChoice<GemSecondaryStat>> list,
                                                                         GemSecondaryStat s) {
        for (RankedGemChoice<GemSecondaryStat> c : list) {
            if (c.item == s) return c;
        }
        return null;
    }

    private static String formatTopSecondariesInline(List<RankedGemChoice<GemSecondaryStat>> secRank, int k) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < Math.min(k, secRank.size()); i++) {
            RankedGemChoice<GemSecondaryStat> sc = secRank.get(i);
            names.add((i + 1) + ") " + sc.item.ruName + " (+" + fmt2(sc.totalGainPct) + "%)");
        }
        return joinStrings(names, ", ");
    }

    private static String joinStrings(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static String fmt2(double v) {
        return String.format(Locale.US, "%.2f", v);
    }
}
