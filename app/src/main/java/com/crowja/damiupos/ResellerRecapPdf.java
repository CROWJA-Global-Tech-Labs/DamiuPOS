package com.crowja.damiupos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ResellerKomisiCalculator;
import com.crowja.damiupos.db.ResellerWithdrawalDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.Customer;

import java.io.File;
import java.io.FileOutputStream;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * PDF "Rekap Pendapatan" seorang reseller: ringkasan komisi + detail riwayat
 * transaksi komisi sampai {@code days} hari terakhir (default 30). A4 portrait.
 */
public final class ResellerRecapPdf {

    private ResellerRecapPdf() {}

    private static final NumberFormat NF = NumberFormat.getInstance(new Locale("id", "ID"));
    private static final SimpleDateFormat SDF_DB =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat SDF_DISPLAY =
            new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID"));
    private static final SimpleDateFormat SDF_ROW =
            new SimpleDateFormat("dd/MM/yy HH:mm", new Locale("id", "ID"));

    private static final float W = 595f, H = 842f;       // A4 portrait
    private static final float ML = 30f, MR = 30f, MT = 30f, MB = 30f;
    private static final float ROW = 20f, HDR = 22f;
    private static final float[] COLW = {26f, 92f, 240f, 50f, 127f}; // sum = 535
    private static final String[] COLH = {"No", "Tanggal", "Detail Komisi", "Galon", "Komisi"};
    private static final char[] COLA = {'C', 'L', 'L', 'R', 'R'};

    /** Bangun PDF rekap komisi reseller untuk {@code days} hari terakhir. */
    public static File build(Context ctx, DatabaseHelper dbHelper, Customer reseller, int days) {
        try {
            if (reseller == null) return null;
            SettingsDao s = new SettingsDao(dbHelper);
            String depot = s.getDepotName();
            if (depot == null || depot.isEmpty()) depot = "DAMIU POS";

            ResellerKomisiCalculator.Result res =
                    ResellerKomisiCalculator.hitung(dbHelper, reseller);
            long cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000;
            List<ResellerKomisiCalculator.Entry> entries = new ArrayList<>();
            double totalKomisi = 0;
            int totalGalon = 0;
            for (ResellerKomisiCalculator.Entry e : res.entries) {
                if (parseMillis(e.tanggal) >= cutoff) {
                    entries.add(e);
                    totalKomisi += e.komisi;
                    totalGalon += e.totalGalon;
                }
            }

            // Pencairan komisi (sepanjang waktu): uang + air (galon).
            double earnedLifetime = res.totalKomisi;
            double wUang = 0, wAirRp = 0;
            int wAirGalon = 0;
            for (ResellerWithdrawalDao.Withdrawal w :
                    new ResellerWithdrawalDao(dbHelper).getByCustomer(reseller.getId())) {
                if (ResellerWithdrawalDao.TYPE_AIR.equals(w.type)) {
                    wAirRp += w.amount;
                    wAirGalon += w.galonQty;
                } else {
                    wUang += w.amount;
                }
            }
            double totalDicairkan = wUang + wAirRp;
            double saldo = earnedLifetime - totalDicairkan;

            Paint pTitle = paint(15f, Color.BLACK, true);
            Paint pSub = paint(10f, Color.DKGRAY, false);
            Paint pSummary = paint(11f, Color.parseColor("#1565C0"), true);
            Paint pSaldo = paint(14f, Color.parseColor("#1565C0"), true);   // Saldo Komisi, lebih besar
            Paint pHeader = paint(10f, Color.WHITE, true);
            Paint pCell = paint(9.5f, Color.BLACK, false);
            Paint pHeaderBg = fill("#1565C0");
            Paint pZebra = fill("#F5F5F5");
            Paint pBorder = new Paint();
            pBorder.setColor(Color.parseColor("#CCCCCC"));
            pBorder.setStyle(Paint.Style.STROKE);
            pBorder.setStrokeWidth(0.5f);

            PdfDocument pdf = new PdfDocument();
            int pageNum = 1;
            PdfDocument.Page page = pdf.startPage(
                    new PdfDocument.PageInfo.Builder((int) W, (int) H, pageNum).create());
            Canvas c = page.getCanvas();

            String start = SDF_DISPLAY.format(new Date(cutoff));
            String end = SDF_DISPLAY.format(new Date());

            float y = MT + 14f;
            c.drawText("REKAP PENDAPATAN RESELLER", ML, y, pTitle); y += 15f;
            c.drawText(depot, ML, y, pSub); y += 13f;
            String resLine = reseller.getName() != null ? reseller.getName() : "-";
            if (reseller.getPhone() != null && !reseller.getPhone().trim().isEmpty()) {
                resLine += "  -  " + reseller.getPhone().trim();
            }
            c.drawText("Reseller: " + resLine, ML, y, pSub); y += 12f;
            c.drawText("Periode: " + start + " s/d " + end + " (" + days + " hari terakhir)",
                    ML, y, pSub); y += 12f;
            c.drawText("Dibuat: " + SDF_DISPLAY.format(new Date()), ML, y, pSub); y += 17f;
            c.drawText("Komisi " + days + " hari: Rp " + NF.format(Math.round(totalKomisi))
                    + "     Galon: " + totalGalon
                    + "     Transaksi: " + entries.size(), ML, y, pSummary);
            y += 14f;
            c.drawText("Total Komisi (semua): Rp " + NF.format(Math.round(earnedLifetime)),
                    ML, y, pSub); y += 12f;
            c.drawText("Sudah Dicairkan: Rp " + NF.format(Math.round(totalDicairkan))
                    + "   (Uang Rp " + NF.format(Math.round(wUang))
                    + "  •  Air " + wAirGalon + " galon = Rp " + NF.format(Math.round(wAirRp)) + ")",
                    ML, y, pSub); y += 14f;
            y += 2f;
            c.drawText("Saldo Komisi: Rp " + NF.format(Math.round(saldo)), ML, y, pSaldo);
            y += 18f;

            // Apresiasi mitra + grafik tren komisi mingguan (hanya halaman pertama).
            y = drawAppreciation(c, y, depot, reseller.getName());
            y = drawWeeklyChart(c, y, entries, cutoff, days);
            y += 4f;

            y = tableHeader(c, y, pHeader, pHeaderBg);
            int rowIdx = 0, no = 1;
            for (ResellerKomisiCalculator.Entry e : entries) {
                if (y + ROW > H - MB) {
                    footer(c, pageNum, pSub);
                    pdf.finishPage(page);
                    pageNum++;
                    page = pdf.startPage(
                            new PdfDocument.PageInfo.Builder((int) W, (int) H, pageNum).create());
                    c = page.getCanvas();
                    y = tableHeader(c, MT, pHeader, pHeaderBg);
                    rowIdx = 0;
                }
                String tgl = e.tanggal != null ? e.tanggal : "";
                try {
                    Date d = SDF_DB.parse(e.tanggal);
                    if (d != null) tgl = SDF_ROW.format(d);
                } catch (Exception ignored) {}
                String[] cells = {
                        String.valueOf(no++), tgl,
                        e.detail != null ? e.detail : "-",
                        String.valueOf(e.totalGalon),
                        "Rp " + NF.format(Math.round(e.komisi))};
                if (rowIdx % 2 == 1) c.drawRect(ML, y, ML + sum(), y + ROW, pZebra);
                row(c, y, cells, pCell, pBorder);
                y += ROW;
                rowIdx++;
            }

            if (entries.isEmpty()) {
                c.drawText("(Tidak ada transaksi komisi dalam " + days + " hari terakhir)",
                        ML, y + 14f, pSub);
            }

            footer(c, pageNum, pSub);
            pdf.finishPage(page);

            File dir = new File(ctx.getExternalFilesDir(null), "exports");
            if (!dir.exists() && !dir.mkdirs()) { pdf.close(); return null; }
            String safe = reseller.getName() != null
                    ? reseller.getName().replaceAll("[^a-zA-Z0-9]+", "_") : "reseller";
            File file = new File(dir, "Rekap_Komisi_" + safe + "_"
                    + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()) + ".pdf");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                pdf.writeTo(fos);
            } catch (Exception ex) {
                pdf.close();
                return null;
            }
            pdf.close();
            return file;
        } catch (Throwable t) {
            return null;
        }
    }

    // ----------------------------------------------------------------- helpers

    private static Paint paint(float size, int color, boolean bold) {
        Paint p = new Paint();
        p.setColor(color);
        p.setTextSize(size);
        p.setFakeBoldText(bold);
        p.setAntiAlias(true);
        return p;
    }

    private static Paint fill(String hex) {
        Paint p = new Paint();
        p.setColor(Color.parseColor(hex));
        return p;
    }

    private static long parseMillis(String ts) {
        if (ts == null) return 0;
        try {
            Date d = SDF_DB.parse(ts);
            return d != null ? d.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static float sum() {
        float s = 0;
        for (float w : COLW) s += w;
        return s;
    }

    private static float tableHeader(Canvas c, float y, Paint pHeader, Paint pHeaderBg) {
        c.drawRect(ML, y, ML + sum(), y + HDR, pHeaderBg);
        float x = ML, textY = y + HDR - 7f;
        for (int i = 0; i < COLH.length; i++) {
            c.drawText(COLH[i], textX(x, COLW[i], COLH[i], COLA[i], pHeader), textY, pHeader);
            x += COLW[i];
        }
        return y + HDR;
    }

    private static void row(Canvas c, float y, String[] cells, Paint pCell, Paint pBorder) {
        float x = ML, textY = y + ROW - 6f;
        for (int i = 0; i < cells.length; i++) {
            String text = ellipsize(cells[i] != null ? cells[i] : "", COLW[i] - 6f, pCell);
            c.drawText(text, textX(x, COLW[i], text, COLA[i], pCell), textY, pCell);
            c.drawLine(x, y, x, y + ROW, pBorder);
            x += COLW[i];
        }
        c.drawLine(x, y, x, y + ROW, pBorder);
        c.drawLine(ML, y + ROW, ML + sum(), y + ROW, pBorder);
    }

    private static void footer(Canvas c, int pageNum, Paint pSub) {
        c.drawText("Halaman " + pageNum + "  •  DAMIU POS by FREZ Tech & Innovations Lab",
                ML, H - MB + 16f, pSub);
    }

    /** Kotak apresiasi mitra reseller (latar hijau lembut + aksen kiri). */
    private static float drawAppreciation(Canvas c, float top, String depot, String resellerName) {
        float boxH = 48f;
        c.drawRect(ML, top, ML + sum(), top + boxH, fill("#E8F5E9"));
        c.drawRect(ML, top, ML + 4f, top + boxH, fill("#2E7D32"));   // aksen kiri
        Paint pT = paint(10.5f, Color.parseColor("#1B5E20"), true);
        Paint pB = paint(9.5f, Color.parseColor("#33691E"), false);
        String name = (resellerName != null && !resellerName.trim().isEmpty())
                ? resellerName.trim() : "Mitra";
        c.drawText("Terima kasih, " + name + "!", ML + 12f, top + 17f, pT);
        String l1 = "Kontribusi Anda sebagai mitra reseller " + depot + " sangat kami hargai.";
        String l2 = "Semoga kerja sama ini terus tumbuh dan menguntungkan kita bersama.";
        c.drawText(ellipsize(l1, sum() - 22f, pB), ML + 12f, top + 30f, pB);
        c.drawText(ellipsize(l2, sum() - 22f, pB), ML + 12f, top + 42f, pB);
        return top + boxH + 12f;
    }

    /** Grafik batang komisi per minggu sepanjang periode rekap. */
    private static float drawWeeklyChart(Canvas c, float top,
                                         List<ResellerKomisiCalculator.Entry> entries,
                                         long cutoff, int days) {
        if (entries.isEmpty()) return top;
        int numBuckets = Math.max(1, (int) Math.ceil(days / 7.0));
        double[] sums = new double[numBuckets];
        long weekMs = 7L * 24 * 60 * 60 * 1000;
        for (ResellerKomisiCalculator.Entry e : entries) {
            int idx = (int) ((parseMillis(e.tanggal) - cutoff) / weekMs);
            if (idx < 0) idx = 0;
            if (idx >= numBuckets) idx = numBuckets - 1;
            sums[idx] += e.komisi;
        }
        double max = 0;
        for (double s : sums) max = Math.max(max, s);
        if (max <= 0) return top;

        c.drawText("Tren Komisi Mingguan", ML, top + 10f,
                paint(11f, Color.parseColor("#1565C0"), true));
        float chartTop = top + 18f, chartH = 80f, baseY = chartTop + chartH;
        Paint axis = new Paint();
        axis.setColor(Color.parseColor("#BDBDBD"));
        axis.setStrokeWidth(1f);
        c.drawLine(ML, baseY, ML + sum(), baseY, axis);

        Paint bar = fill("#42A5F5");
        Paint pVal = paint(7.5f, Color.DKGRAY, false);
        Paint pLbl = paint(8f, Color.parseColor("#616161"), false);
        float slot = sum() / numBuckets, barW = slot * 0.5f;
        for (int i = 0; i < numBuckets; i++) {
            float cx = ML + slot * i + slot / 2f;
            float h = (float) (sums[i] / max * (chartH - 10f));
            if (sums[i] > 0) {
                c.drawRect(cx - barW / 2f, baseY - h, cx + barW / 2f, baseY, bar);
                String val = compactRp(sums[i]);
                c.drawText(val, cx - pVal.measureText(val) / 2f, baseY - h - 3f, pVal);
            }
            String lbl = "M" + (i + 1);
            c.drawText(lbl, cx - pLbl.measureText(lbl) / 2f, baseY + 11f, pLbl);
        }
        return baseY + 20f;
    }

    /** Format Rupiah ringkas untuk label grafik (mis. "Rp 1,2jt"). */
    private static String compactRp(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000) return "Rp " + trimNum(v / 1_000_000) + "jt";
        if (a >= 1_000) return "Rp " + trimNum(v / 1_000) + "rb";
        return "Rp " + Math.round(v);
    }

    private static String trimNum(double v) {
        String s = String.format(Locale.US, "%.1f", v);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s.replace('.', ',');
    }

    private static float textX(float colX, float colW, String text, char align, Paint paint) {
        float pad = 4f;
        if (align == 'R') return colX + colW - pad - paint.measureText(text);
        if (align == 'C') return colX + (colW - paint.measureText(text)) / 2f;
        return colX + pad;
    }

    private static String ellipsize(String text, float maxWidth, Paint paint) {
        if (text == null) return "";
        if (paint.measureText(text) <= maxWidth) return text;
        String ell = "...";
        float ellW = paint.measureText(ell);
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (paint.measureText(text.substring(0, mid)) + ellW <= maxWidth) lo = mid;
            else hi = mid - 1;
        }
        return text.substring(0, lo) + ell;
    }
}
