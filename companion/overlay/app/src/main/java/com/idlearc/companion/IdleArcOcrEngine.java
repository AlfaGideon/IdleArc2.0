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

        // 3. Находим горизонтальные строки текста
        List<int[]> lineBands = new ArrayList<>();
        boolean inLine = false;
        int lineStart = 0;
        int maxLineH = Math.max(60, imgH / 32);
        for (int y = 0; y < rh; y++) {
            int cnt = 0;
            float[] dRow = diff[y];
            for (int x = 0; x < rw; x++) {
                if (dRow[x] > 0.34f) cnt++;
            }
            if (!inLine && cnt >= 5) {
                inLine = true;
                lineStart = y;
            } else if (inLine && cnt <= 1) {
                inLine = false;
                int h = y - lineStart;
                if (h >= 12 && h <= maxLineH) {
                    lineBands.add(new int[]{lineStart, y});
                }
            }
        }
        if (inLine) {
            int h = rh - lineStart;
            if (h >= 12 && h <= maxLineH) {
                lineBands.add(new int[]{lineStart, rh});
            }
        }

        // 4. Сегментация символов внутри каждой строки и классификация по шаблонам Roboto
        List<OcrLine> resultLines = new ArrayList<>();
        float[] patchBuf = new float[gw * gh];

        for (int[] band : lineBands) {
            int ly0 = band[0];
            int ly1 = band[1];
            int lh = ly1 - ly0;

            // Измеряем цветовой профиль и пиковую яркость активных пикселей строки
            int fgPixels = 0;
            int cyanPixels = 0;
            int purplePixels = 0;
            int peakLum = 0;
            for (int y = ly0; y < ly1; y++) {
                int imgRow = (ry0 + y) * imgW + rx0;
                float[] dRow = diff[y];
                for (int x = 0; x < rw; x++) {
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
            int cx0 = 0;
            for (int x = 0; x < rw; x++) {
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
            if (inChar && rw - cx0 >= 2) spans.add(new int[]{cx0, rw});
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
