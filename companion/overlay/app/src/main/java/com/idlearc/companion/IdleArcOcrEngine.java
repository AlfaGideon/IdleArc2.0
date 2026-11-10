package com.idlearc.companion;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Быстрый офлайн OCR-движок под шрифт интерфейса IdleArc (Roboto RU + EN + цифры/символы).
 * Не требует нативных .so библиотек и интернета, работает на любом Android 7..16 (4KB/16KB pages).
 */
public class IdleArcOcrEngine {

    private final int count;
    private final int gw;
    private final int gh;
    private final char[] chars;
    private final float[] aspects;
    private final float[] relHeights;
    private final float[] relYs;
    private final float[][] vectors; // [count][gw * gh] в диапазоне 0..1

    private static final Pattern DIGIT_ONE_LEFT = Pattern.compile("(?<=[0-9+×.,(])[ТTІl|](?=[0-9.,%оОoO])");
    private static final Pattern DIGIT_Z_LEFT = Pattern.compile("(?<=[0-9+×.,(])[зЗ]");
    private static final Pattern DIGIT_Z_RIGHT = Pattern.compile("[зЗ](?=[0-9.%])");
    private static final Pattern DIGIT_O_LEFT = Pattern.compile("(?<=[0-9+×.,(])[оОoO]");
    private static final Pattern DIGIT_O_RIGHT = Pattern.compile("[оОoO](?=[0-9%])");

    private IdleArcOcrEngine(int count, int gw, int gh, char[] chars,
                             float[] aspects, float[] relHeights, float[] relYs, float[][] vectors) {
        this.count = count;
        this.gw = gw;
        this.gh = gh;
        this.chars = chars;
        this.aspects = aspects;
        this.relHeights = relHeights;
        this.relYs = relYs;
        this.vectors = vectors;
    }

    public static IdleArcOcrEngine loadFromStream(InputStream rawIn) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(rawIn))) {
            int n = in.readInt();
            int w = in.readInt();
            int h = in.readInt();
            int dim = w * h;
            char[] cs = new char[n];
            float[] asp = new float[n];
            float[] rh = new float[n];
            float[] ry = new float[n];
            float[][] vecs = new float[n][dim];
            byte[] buf = new byte[dim];
            for (int i = 0; i < n; i++) {
                cs[i] = (char) in.readUnsignedShort();
                asp[i] = in.readFloat();
                rh[i] = in.readFloat();
                ry[i] = in.readFloat();
                in.readFully(buf);
                float[] v = vecs[i];
                for (int k = 0; k < dim; k++) {
                    v[k] = (buf[k] & 0xFF) / 255.0f;
                }
            }
            return new IdleArcOcrEngine(n, w, h, cs, asp, rh, ry, vecs);
        }
    }

    /**
     * Строка текста с экранными координатами и цветовыми признаками усиления Пробуждения
     * (в IdleArc усиленные Пробуждением позиции аффиксов рисуются голубым/бирюзовым цветом
     * со значком римской цифры I, II, III, IV, V... = +10%, +20%, +30%... либо фиолетовым акцентом Кузницы).
     */
    public static final class OcrLine {
        public final String text;
        public final int y0;
        public final int y1;
        public final float cyanRatio;
        public final float purpleRatio;
        public final int badgeStrokes;
        public final int maxLum;

        public OcrLine(String text, int y0, int y1, float cyanRatio, float purpleRatio, int badgeStrokes) {
            this(text, y0, y1, cyanRatio, purpleRatio, badgeStrokes, 255);
        }

        public OcrLine(String text, int y0, int y1, float cyanRatio, float purpleRatio, int badgeStrokes, int maxLum) {
            this.text = text;
            this.y0 = y0;
            this.y1 = y1;
            this.cyanRatio = cyanRatio;
            this.purpleRatio = purpleRatio;
            this.badgeStrokes = badgeStrokes;
            this.maxLum = maxLum;
        }

        public boolean hasAwakenHighlight() {
            return cyanRatio >= 0.16f || purpleRatio >= 0.18f;
        }
    }

    /**
     * Отсекает затемнённые строки фонового экрана (над и под открытым модальным окном IdleArc).
     * В IdleArc любое открытое окно предмета/навыка/статов затемняет фон до яркости <= 135,
     * тогда как текст внутри активного попапа имеет яркость 180..255.
     * На полноэкранных вкладках без попапа все строки яркие (>= 180) и возвращаются целиком.
     */
    public static List<OcrLine> isolateActiveModalLines(List<OcrLine> lines) {
        if (lines == null || lines.size() < 2) return lines;
        int brightCount = 0;
        int firstBright = -1;
        int lastBright = -1;
        for (int i = 0; i < lines.size(); i++) {
            int lum = lines.get(i).maxLum;
            if (lum >= 175) {
                brightCount++;
            }
            if (lum >= 158) {
                if (firstBright == -1) firstBright = i;
                lastBright = i;
            }
        }
        if (brightCount >= 2 && firstBright >= 0 && lastBright >= firstBright
                && (firstBright > 0 || lastBright < lines.size() - 1)) {
            return new ArrayList<>(lines.subList(firstBright, lastBright + 1));
        }
        return lines;
    }

    /**
     * Распознаёт строки текста внутри прямоугольника [rx0..rx1) x [ry0..ry1) кадра ARGB_8888.
     */
    public List<String> recognizeRegion(int[] argb, int imgW, int imgH, int rx0, int ry0, int rx1, int ry1) {
        List<OcrLine> meta = recognizeLinesWithMeta(argb, imgW, imgH, rx0, ry0, rx1, ry1);
        List<String> out = new ArrayList<>(meta.size());
        for (OcrLine l : meta) {
            out.add(l.text);
        }
        return out;
    }

    /**
     * Распознаёт строки текста вместе с координатами Y и детекцией голубых/фиолетовых позиций Пробуждения.
     */
    public List<OcrLine> recognizeLinesWithMeta(int[] argb, int imgW, int imgH, int rx0, int ry0, int rx1, int ry1) {
        rx0 = Math.max(0, Math.min(imgW - 1, rx0));
        ry0 = Math.max(0, Math.min(imgH - 1, ry0));
        rx1 = Math.max(rx0 + 1, Math.min(imgW, rx1));
        ry1 = Math.max(ry0 + 1, Math.min(imgH, ry1));

        int rw = rx1 - rx0;
        int rh = ry1 - ry0;
        if (rw < 20 || rh < 16) return new ArrayList<>();

        float[][] diff = new float[rh][rw];
        int[] hist = new int[256];

        // 1. Вычисляем контраст текста над тёмной подложкой карточки по каждой строке
        for (int y = 0; y < rh; y++) {
            Arrays.fill(hist, 0);
            int rowOff = (ry0 + y) * imgW + rx0;
            for (int x = 0; x < rw; x++) {
                int px = argb[rowOff + x];
                int r = (px >> 16) & 0xFF;
                int g = (px >> 8) & 0xFF;
                int b = px & 0xFF;
                int lum = Math.max(r, Math.max(g, b));
                hist[lum]++;
            }
            // 25-й перцентиль яркости строки — фон карточки
            int target = rw / 4;
            int acc = 0;
            int bg = 35;
            for (int l = 0; l < 256; l++) {
                acc += hist[l];
                if (acc >= target) {
                    bg = l;
                    break;
                }
            }
            // Если фон слишком светлый (>175), инвертируем (чёрный текст на светлой кнопке вроде жёлтой вкладки)
            boolean lightBg = (bg > 175);
            float[] dRow = diff[y];
            for (int x = 0; x < rw; x++) {
                int px = argb[rowOff + x];
                int r = (px >> 16) & 0xFF;
                int g = (px >> 8) & 0xFF;
                int b = px & 0xFF;
                int lum = Math.max(r, Math.max(g, b));
                float v;
                if (lightBg) {
                    int minCh = Math.min(r, Math.min(g, b));
                    v = (bg - minCh - 45.0f) / 120.0f;
                } else {
                    v = (lum - bg - 46.0f) / 125.0f;
                }
                dRow[x] = v < 0f ? 0f : (v > 1f ? 1f : v);
            }
        }

        // 2. Подавляем только УЗКИЕ вертикальные скроллбары/рамки (ширина <= 14px, длина >= 60px),
        // и зануляем ТОЛЬКО сам отрезок скроллбара, не трогая текст выше/ниже иконки!
        int maxRunBar = Math.max(56, imgH / 40);
        for (int x = 0; x < rw; x++) {
            int runStart = -1;
            for (int y = 0; y <= rh; y++) {
                boolean active = (y < rh) && (diff[y][x] > 0.30f);
                if (active) {
                    if (runStart == -1) runStart = y;
                } else if (runStart != -1) {
                    int runLen = y - runStart;
                    if (runLen >= maxRunBar) {
                        // Проверяем, что это узкая вертикальная полоска (скроллбар/рамка), а не иконка предмета/скилла
                        int midY = runStart + runLen / 2;
                        int leftW = 0, rightW = 0;
                        while (x - leftW - 1 >= 0 && leftW < 16 && diff[midY][x - leftW - 1] > 0.25f) leftW++;
                        while (x + rightW + 1 < rw && rightW < 16 && diff[midY][x + rightW + 1] > 0.25f) rightW++;
                        if (leftW + rightW + 1 <= 14) {
                            for (int yy = runStart; yy < y; yy++) {
                                diff[yy][x] = 0f;
                            }
                        }
                    }
                    runStart = -1;
                }
            }
        }

        // 3. Находим горизонтальные строки текста: каждый элемент = {ly0, ly1, lx0, lx1}.
        // ВАЖНО: в шапке предмета IdleArc (DetailPopupHeader) слева стоит крупная квадратная иконка
        // предмета (~160..220px высотой), а справа — кнопки [Скин]/[Замок]/[X]. Раньше иконка склеивала
        // 3 строки шапки (Название + Тип слота + GS) в одну полосу > maxLineH, и шапка отбрасывалась!
        List<int[]> lineBands = collectLineBands(diff, rw, rh, Math.max(60, imgH / 32));

        // 4. Сегментация символов внутри каждой строки и классификация по шаблонам Roboto
        List<OcrLine> resultLines = new ArrayList<>();
        float[] patchBuf = new float[gw * gh];

        for (int[] band : lineBands) {
            int ly0 = band[0];
            int ly1 = band[1];
            int lx0 = band[2];
            int lx1 = band[3];
            int lh = ly1 - ly0;
            if (lh < 12 || lx1 - lx0 < 20) continue;

            // Измеряем цветовой профиль и пиковую яркость активных пикселей строки (только в текстовом диапазоне lx0..lx1)
            int fgPixels = 0;
            int cyanPixels = 0;
            int purplePixels = 0;
            int peakLum = 0;
            for (int y = ly0; y < ly1; y++) {
                int imgRow = (ry0 + y) * imgW + rx0;
                float[] dRow = diff[y];
                for (int x = lx0; x < lx1; x++) {
                    if (dRow[x] > 0.36f) {
                        fgPixels++;
                        int px = argb[imgRow + x];
                        int r = (px >> 16) & 0xFF;
                        int g = (px >> 8) & 0xFF;
                        int b = px & 0xFF;
                        int lum = Math.max(r, Math.max(g, b));
                        if (lum > peakLum) peakLum = lum;
                        // Голубой/бирюзовый цвет усиленного аффикса (cyan: высокий G и B, низкий R)
                        if (b >= 165 && g >= 135 && (b - r) >= 50 && (g - r) >= 35 && (g - b) <= 35) {
                            cyanPixels++;
                        } else if (b >= 165 && r >= 120 && (b - g) >= 40 && (r - g) >= 20) {
                            purplePixels++;
                        }
                    }
                }
            }
            float cyanRatio = fgPixels > 0 ? (cyanPixels / (float) fgPixels) : 0f;
            float purpleRatio = fgPixels > 0 ? (purplePixels / (float) fgPixels) : 0f;
            int detectedBadgeStrokes = 0;

            List<int[]> spans = new ArrayList<>();
            boolean inChar = false;
            int cx0 = lx0;
            for (int x = lx0; x < lx1; x++) {
                boolean has = false;
                for (int y = ly0; y < ly1; y++) {
                    if (diff[y][x] > 0.31f) {
                        has = true;
                        break;
                    }
                }
                if (!inChar && has) {
                    inChar = true;
                    cx0 = x;
                } else if (inChar && !has) {
                    inChar = false;
                    if (x - cx0 >= 2) spans.add(new int[]{cx0, x});
                }
            }
            if (inChar && lx1 - cx0 >= 2) spans.add(new int[]{cx0, lx1});
            if (spans.isEmpty()) continue;

            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < spans.size()) {
                int x0 = spans.get(i)[0];
                int x1 = spans.get(i)[1];

                if (i > 0) {
                    int gap = x0 - spans.get(i - 1)[1];
                    if (gap >= Math.max(8, (int) (lh * 0.33f))) {
                        sb.append(' ');
                    }
                }

                // Широкий блок в начале/конце голубой строки может быть плашкой римской цифры [I]..[V]
                if ((x1 - x0) > lh * 1.38f) {
                    if ((i == 0 || i == spans.size() - 1) && (cyanRatio >= 0.12f || purpleRatio >= 0.14f)
                            && (x1 - x0) <= lh * 2.8f) {
                        int midY = ly0 + lh / 2;
                        int peaks = 0;
                        boolean inPeak = false;
                        for (int bx = x0 + 3; bx < x1 - 3; bx++) {
                            boolean on = diff[midY][bx] > 0.38f;
                            if (on && !inPeak) {
                                peaks++;
                                inPeak = true;
                            } else if (!on) {
                                inPeak = false;
                            }
                        }
                        if (peaks >= 1 && peaks <= 6) {
                            detectedBadgeStrokes = Math.max(detectedBadgeStrokes, peaks);
                        }
                    }
                    i++;
                    continue;
                }

                int cy0 = -1, cy1 = -1;
                for (int y = ly0; y < ly1; y++) {
                    for (int x = x0; x < x1; x++) {
                        if (diff[y][x] > 0.25f) {
                            if (cy0 == -1) cy0 = y;
                            cy1 = y + 1;
                        }
                    }
                }
                if (cy0 == -1 || cy1 <= cy0) {
                    i++;
                    continue;
                }

                char bestCh = matchGlyph(diff, x0, x1, cy0, cy1, ly0, lh, patchBuf);

                // Склейка двухкомпонентной русской буквы 'ы' / 'Ы' ('ь' + '|')
                if ((bestCh == 'ь' || bestCh == 'Ь' || bestCh == 'ъ') && (i + 1) < spans.size()) {
                    int nx0 = spans.get(i + 1)[0];
                    int nx1 = spans.get(i + 1)[1];
                    if ((nx0 - x1) <= Math.max(6, (int) (lh * 0.20f))
                            && (nx1 - nx0) <= Math.max(7, (int) (lh * 0.26f))) {
                        int mcy0 = cy0, mcy1 = cy1;
                        for (int y = ly0; y < ly1; y++) {
                            for (int x = nx0; x < nx1; x++) {
                                if (diff[y][x] > 0.25f) {
                                    if (y < mcy0) mcy0 = y;
                                    if (y + 1 > mcy1) mcy1 = y + 1;
                                }
                            }
                        }
                        char merged = matchGlyph(diff, x0, nx1, mcy0, mcy1, ly0, lh, patchBuf);
                        if (merged == 'ы' || merged == 'Ы') {
                            sb.append(merged);
                            i += 2;
                            continue;
                        }
                    }
                }

                sb.append(bestCh);
                i++;
            }

            String line = postProcessLine(sb.toString().trim());
            if (!line.isEmpty()) {
                resultLines.add(new OcrLine(
                        line, ry0 + ly0, ry0 + ly1, cyanRatio, purpleRatio, detectedBadgeStrokes, peakLum));
            }
        }

        return resultLines;
    }

    /**
     * Выделяет горизонтальные полосы текста {ly0, ly1, lx0, lx1}, автоматически разрезая высокие блоки,
     * склеенные левой квадратной иконкой предмета (DetailPopupHeader leading) или правыми кнопками [Скин]/[Замок]/[X].
     */
    private static List<int[]> collectLineBands(float[][] diff, int rw, int rh, int maxLineH) {
        List<int[]> out = new ArrayList<>();
        boolean inLine = false;
        int lineStart = 0;
        for (int y = 0; y <= rh; y++) {
            int cnt = 0;
            if (y < rh) {
                float[] dRow = diff[y];
                for (int x = 0; x < rw; x++) {
                    if (dRow[x] > 0.34f) cnt++;
                }
            }
            if (!inLine && cnt >= 5) {
                inLine = true;
                lineStart = y;
            } else if (inLine && (y == rh || cnt <= 2)) {
                inLine = false;
                int bh = y - lineStart;
                if (bh < 12) continue;
                if (bh <= maxLineH) {
                    // Проверяем, не склеила ли иконка слева 2 невысокие строки внутри bh >= 48
                    if (bh >= 48 && trySplitBandIgnoringSideIcons(diff, lineStart, y, rw, maxLineH, out, true)) {
                        continue;
                    }
                    out.add(new int[]{lineStart, y, 0, rw});
                } else {
                    trySplitBandIgnoringSideIcons(diff, lineStart, y, rw, maxLineH, out, false);
                }
            }
        }
        return out;
    }

    private static boolean trySplitBandIgnoringSideIcons(
            float[][] diff, int by0, int by1, int rw, int maxLineH, List<int[]> out, boolean requireMultiLine) {
        // Шаг 1 (точный геометрический): находим вертикальный пустой зазор между квадратной иконкой слева и текстом шапки!
        // Это сохраняет даже короткие однословные названия у самого края иконки («Факел», «Пояс», «Шлем», «Перчатки», «Сапоги»).
        int[] exactCols = findHeaderTextRangeAfterLeftIcon(diff, by0, by1, rw);
        List<int[]> bestSub = null;
        if (exactCols != null) {
            bestSub = segmentSubLinesInRange(diff, by0, by1, exactCols[0], exactCols[1], maxLineH);
        }

        // Шаг 2 (запасной, если рамка иконки слилась с текстом без зазора): проверяем все окна без раннего выхода,
        // выбирая максимум найденных строк (а при равенстве — самое левое окно, чтобы не потерять короткую 1-ю строку!).
        if (bestSub == null || bestSub.size() < 2) {
            float[][] windows = {
                {0.24f, 0.76f},
                {0.32f, 0.76f},
                {0.39f, 0.76f},
                {0.45f, 0.84f}
            };
            for (float[] win : windows) {
                List<int[]> candidate = splitBandWithProbeWindow(diff, by0, by1, rw, maxLineH, win[0], win[1]);
                if (candidate != null && !candidate.isEmpty()) {
                    if (bestSub == null || candidate.size() > bestSub.size()) {
                        bestSub = candidate;
                    }
                }
            }
        }

        if (bestSub == null || bestSub.isEmpty()) {
            return false;
        }
        if (requireMultiLine && bestSub.size() < 2) {
            return false;
        }
        out.addAll(bestSub);
        return true;
    }

    /**
     * Находит точный горизонтальный диапазон [textX0, textX1) текста шапки карточки предмета:
     * отрезает слева квадратный блок иконки (шириной >= 42px и вертикальным охватом >= 56% полосы),
     * за которым идёт чистый вертикальный зазор >= 6px, а справа — кнопки [Скин]/[Замок]/[X].
     */
    private static int[] findHeaderTextRangeAfterLeftIcon(float[][] diff, int by0, int by1, int rw) {
        int bh = by1 - by0;
        if (rw < 80 || bh < 44) return null;

        int[] colActive = new int[rw];
        int[] colMinY = new int[rw];
        int[] colMaxY = new int[rw];
        Arrays.fill(colMinY, bh);
        Arrays.fill(colMaxY, -1);

        for (int i = 0; i < bh; i++) {
            float[] dRow = diff[by0 + i];
            for (int x = 0; x < rw; x++) {
                if (dRow[x] > 0.32f) {
                    colActive[x]++;
                    if (i < colMinY[x]) colMinY[x] = i;
                    if (i > colMaxY[x]) colMaxY[x] = i;
                }
            }
        }

        int maxLeftScan = (int) (rw * 0.50f);
        int x = 0;
        int iconX0 = -1;
        int iconX1 = -1;
        int iconSpanY = 0;
        int quietAfterIcon = 0;

        // Пропускаем тонкие вертикальные рамки самой карточки (шириной < 24px, напр. x=55..60 на 1080p)
        // и находим сам квадратный блок иконки предмета (шириной >= 42px) + пустой вертикальный зазор справа от неё.
        while (x < maxLeftScan) {
            while (x < maxLeftScan && colActive[x] <= 1) x++;
            if (x >= (int) (rw * 0.30f)) break;

            int cX0 = x;
            int cMinY = bh;
            int cMaxY = -1;
            int quietRun = 0;
            int cX1 = cX0;
            while (x < maxLeftScan) {
                if (colActive[x] >= 2) {
                    quietRun = 0;
                    cX1 = x + 1;
                    if (colMinY[x] < cMinY) cMinY = colMinY[x];
                    if (colMaxY[x] > cMaxY) cMaxY = colMaxY[x];
                } else {
                    quietRun++;
                    if (quietRun >= 6) break;
                }
                x++;
            }
            int cW = cX1 - cX0;
            if (cW < 24) {
                continue; // тонкая внешняя рамка модального окна — идём дальше к самой иконке предмета!
            }
            iconX0 = cX0;
            iconX1 = cX1;
            iconSpanY = (cMaxY >= cMinY) ? (cMaxY - cMinY + 1) : 0;
            quietAfterIcon = quietRun;
            break;
        }

        if (iconX0 < 0 || (iconX1 - iconX0) < 42 || quietAfterIcon < 6
                || iconSpanY < Math.max(44, (int) (bh * 0.54f))) {
            return null;
        }

        int firstTextX = iconX1 + 2;
        while (firstTextX < maxLeftScan && colActive[firstTextX] == 0) {
            firstTextX++;
        }
        int textX0 = Math.max(iconX1 + 2, firstTextX - 5);

        // Отрезаем внешнюю правую рамку (< 12px) и правые иконки-кнопки [Скин]/[Замок]/[X]
        int textX1 = rw;
        int minRightCheck = Math.max(textX0 + 40, (int) (rw * 0.70f));
        int rx = rw - 1;
        while (rx >= minRightCheck) {
            while (rx >= minRightCheck && colActive[rx] <= 1) rx--;
            if (rx < minRightCheck) break;
            int btnEnd = rx + 1;
            int rQuiet = 0;
            int btnStart = rx;
            while (rx >= minRightCheck) {
                if (colActive[rx] >= 2) {
                    rQuiet = 0;
                    btnStart = rx;
                } else {
                    rQuiet++;
                    if (rQuiet >= 8) break;
                }
                rx--;
            }
            int btnW = btnEnd - btnStart;
            if (rQuiet >= 8 && btnW < 12) {
                textX1 = btnStart - 2; // тонкая правая рамка окна
            } else if (rQuiet >= 8 && btnW >= 12 && btnW <= (int) (rw * 0.22f)) {
                textX1 = btnStart - 4; // кнопка [X] / [Замок]
            } else {
                break;
            }
        }

        if (textX1 - textX0 < 36) return null;
        return new int[]{textX0, textX1};
    }

    private static List<int[]> segmentSubLinesInRange(
            float[][] diff, int by0, int by1, int lx0, int lx1, int maxLineH) {
        int bh = by1 - by0;
        int[] rowCnt = new int[bh];
        for (int i = 0; i < bh; i++) {
            float[] dRow = diff[by0 + i];
            int c = 0;
            for (int x = lx0; x < lx1; x++) {
                if (dRow[x] > 0.34f) c++;
            }
            rowCnt[i] = c;
        }

        List<int[]> sub = new ArrayList<>();
        boolean inSub = false;
        int s0 = 0;
        for (int i = 0; i <= bh; i++) {
            int c = (i < bh) ? rowCnt[i] : 0;
            if (!inSub && c >= 4) {
                inSub = true;
                s0 = i;
            } else if (inSub && (i == bh || c <= 1)) {
                inSub = false;
                int sh = i - s0;
                if (sh >= 12 && sh <= maxLineH) {
                    sub.add(new int[]{by0 + s0, by0 + i, lx0, lx1});
                } else if (sh > maxLineH && sh <= maxLineH * 2 + 28) {
                    int searchFrom = s0 + sh / 3;
                    int searchTo = i - sh / 3;
                    int bestIdx = -1;
                    int bestVal = Integer.MAX_VALUE;
                    for (int k = searchFrom; k <= searchTo; k++) {
                        if (rowCnt[k] < bestVal) {
                            bestVal = rowCnt[k];
                            bestIdx = k;
                        }
                    }
                    if (bestIdx > s0 + 11 && i - bestIdx >= 12 && bestVal <= 8) {
                        sub.add(new int[]{by0 + s0, by0 + bestIdx, lx0, lx1});
                        sub.add(new int[]{by0 + bestIdx, by0 + i, lx0, lx1});
                    }
                }
            }
        }
        return sub;
    }

    private static List<int[]> splitBandWithProbeWindow(
            float[][] diff, int by0, int by1, int rw, int maxLineH, float fracX0, float fracX1) {
        int midX0 = Math.max(0, (int) (rw * fracX0));
        int midX1 = Math.min(rw, (int) (rw * fracX1));
        if (midX1 - midX0 < 30) return null;

        int bh = by1 - by0;
        int[] midCnt = new int[bh];
        int gapRows = 0;
        for (int i = 0; i < bh; i++) {
            float[] dRow = diff[by0 + i];
            int c = 0;
            for (int x = midX0; x < midX1; x++) {
                if (dRow[x] > 0.34f) c++;
            }
            midCnt[i] = c;
            if (c <= 1) gapRows++;
        }

        int lx0 = 0;
        int lx1 = rw;
        if (gapRows >= 3) {
            int maxLeftCheck = Math.min(rw, (int) (rw * 0.52f));
            int leftIconEnd = -1;
            for (int x = 0; x < maxLeftCheck; x++) {
                int activeOnGaps = 0;
                for (int i = 0; i < bh; i++) {
                    if (midCnt[i] <= 1 && diff[by0 + i][x] > 0.32f) {
                        activeOnGaps++;
                        if (activeOnGaps >= 2) break;
                    }
                }
                if (activeOnGaps >= 2) leftIconEnd = x + 1;
            }
            if (leftIconEnd > 0) {
                lx0 = Math.min((int) (rw * 0.53f), leftIconEnd + 6);
            }

            int minRightCheck = Math.max(lx0 + 30, (int) (rw * 0.72f));
            int rightIconStart = rw;
            for (int x = rw - 1; x >= minRightCheck; x--) {
                int activeOnGaps = 0;
                for (int i = 0; i < bh; i++) {
                    if (midCnt[i] <= 1 && diff[by0 + i][x] > 0.32f) {
                        activeOnGaps++;
                        if (activeOnGaps >= 2) break;
                    }
                }
                if (activeOnGaps >= 2) rightIconStart = x;
            }
            if (rightIconStart < rw) {
                lx1 = Math.max(lx0 + 30, rightIconStart - 6);
            }
        } else if (bh > maxLineH) {
            lx0 = midX0;
            lx1 = Math.min(rw, (int) (rw * 0.86f));
        }

        return segmentSubLinesInRange(diff, by0, by1, lx0, lx1, maxLineH);
    }

    private char matchGlyph(float[][] diff, int x0, int x1, int cy0, int cy1, int ly0, int lh, float[] patchBuf) {
        int cw = x1 - x0;
        int ch = cy1 - cy0;
        // Билинейный ресайз [x0..x1) x [cy0..cy1) -> gw x gh
        for (int ty = 0; ty < gh; ty++) {
            float sy = cy0 + ((ty + 0.5f) * ch / gh) - 0.5f;
            int yInt = (int) Math.floor(sy);
            float fy = sy - yInt;
            int yA = Math.max(cy0, Math.min(cy1 - 1, yInt));
            int yB = Math.max(cy0, Math.min(cy1 - 1, yInt + 1));

            for (int tx = 0; tx < gw; tx++) {
                float sx = x0 + ((tx + 0.5f) * cw / gw) - 0.5f;
                int xInt = (int) Math.floor(sx);
                float fx = sx - xInt;
                int xA = Math.max(x0, Math.min(x1 - 1, xInt));
                int xB = Math.max(x0, Math.min(x1 - 1, xInt + 1));

                float v00 = diff[yA][xA];
                float v01 = diff[yA][xB];
                float v10 = diff[yB][xA];
                float v11 = diff[yB][xB];
                float top = v00 + fx * (v01 - v00);
                float bot = v10 + fx * (v11 - v10);
                patchBuf[ty * gw + tx] = top + fy * (bot - top);
            }
        }

        float aspect = cw / Math.max(1.0f, (float) ch);
        float relH = ch / (float) lh;
        float relY = (((cy0 - ly0) + (cy1 - ly0)) * 0.5f) / (float) lh;

        int dim = gw * gh;
        float invDim = 1.0f / dim;
        float bestScore = Float.MAX_VALUE;
        int bestIdx = 0;

        for (int t = 0; t < count; t++) {
            float metaPenalty = 0.16f * Math.abs(aspects[t] - aspect)
                    + 0.22f * Math.abs(relHeights[t] - relH)
                    + 0.20f * Math.abs(relYs[t] - relY);
            if (metaPenalty >= bestScore) continue;

            float[] tv = vectors[t];
            float sumDiff = 0f;
            for (int k = 0; k < dim; k++) {
                sumDiff += Math.abs(tv[k] - patchBuf[k]);
            }
            float total = (sumDiff * invDim) + metaPenalty;
            if (total < bestScore) {
                bestScore = total;
                bestIdx = t;
            }
        }
        return chars[bestIdx];
    }

    private static String postProcessLine(String s) {
        if (s.isEmpty()) return s;
        s = DIGIT_ONE_LEFT.matcher(s).replaceAll("1");
        for (int pass = 0; pass < 4; pass++) {
            String prev = s;
            s = DIGIT_Z_LEFT.matcher(s).replaceAll("3");
            s = DIGIT_Z_RIGHT.matcher(s).replaceAll("3");
            s = DIGIT_O_LEFT.matcher(s).replaceAll("0");
            s = DIGIT_O_RIGHT.matcher(s).replaceAll("0");
            if (s.equals(prev)) break;
        }
        return s;
    }
}
