package com.crowja.damiupos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;

import com.crowja.damiupos.db.DatabaseHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Slip gaji PDF (A4 portrait) mengikuti format SLIP GAJI depot. */
public final class PayslipPdf {

    private PayslipPdf() {}

    private static final NumberFormat NF = NumberFormat.getInstance(new Locale("id", "ID"));
    private static final SimpleDateFormat SDF_DB = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat SDF_DISP =
            new SimpleDateFormat("d MMM yyyy", new Locale("id", "ID"));

    private static final float W = 595f, H = 842f;
    private static final float ML = 36f, MR = 36f, MT = 40f;
    private static final float X_QTY = 320f, X_RATE = 450f, X_AMT = W - MR;

    public static File build(Context ctx, DatabaseHelper db, long userId, String userName,
                             String start, String end) {
        try {
            PayslipData d = PayslipData.build(db, userId, userName, start, end);

            Paint pTitle = paint(15f, Color.BLACK, true);
            Paint pLabel = paint(10f, Color.BLACK, false);
            Paint pBold = paint(10f, Color.BLACK, true);
            Paint pSmall = paint(8.5f, Color.DKGRAY, false);
            Paint rule = new Paint();
            rule.setColor(Color.BLACK);
            rule.setStrokeWidth(1.2f);

            PdfDocument pdf = new PdfDocument();
            PdfDocument.Page page = pdf.startPage(
                    new PdfDocument.PageInfo.Builder((int) W, (int) H, 1).create());
            Canvas c = page.getCanvas();

            float y = MT;
            c.drawText("SLIP GAJI", (W - pTitle.measureText("SLIP GAJI")) / 2f, y, pTitle);
            y += 16f;
            c.drawLine(ML, y, W - MR, y, rule);
            y += 18f;

            // Header kiri (depot) + kanan (periode/karyawan/jabatan/status).
            float yL = y;
            c.drawText(d.depot != null ? d.depot.toUpperCase(Locale.getDefault()) : "DEPOT",
                    ML, yL, pBold); yL += 13f;
            if (d.depotAddress != null) {
                for (String ln : d.depotAddress.split("\\r?\\n")) {
                    if (ln.trim().isEmpty()) continue;
                    c.drawText(ln.trim(), ML, yL, pSmall); yL += 12f;
                }
            }
            float yR = y;
            float xRLabel = 330f;
            kv(c, xRLabel, yR, "Periode", disp(d.periodStart) + " - " + disp(d.periodEnd), pLabel); yR += 14f;
            kv(c, xRLabel, yR, "Karyawan", d.userName, pBold); yR += 14f;
            kv(c, xRLabel, yR, "Jabatan", d.jabatan, pLabel); yR += 14f;
            kv(c, xRLabel, yR, "Status", d.status, pLabel); yR += 14f;

            y = Math.max(yL, yR) + 6f;
            c.drawLine(ML, y, W - MR, y, rule);
            y += 16f;

            // PENERIMAAN
            c.drawText("PENERIMAAN", ML, y, pBold); y += 15f;
            for (PayslipData.Line l : d.incomes) {
                y = drawLine(c, y, l, pLabel, pSmall);
            }
            y += 2f;
            c.drawLine(ML + 4f, y, W - MR, y, thin());
            y += 13f;
            c.drawText("Total Penghasilan Bruto", ML + 4f, y, pBold);
            right(c, X_AMT, y, "Rp " + NF.format(Math.round(d.totalBruto)), pBold);
            y += 20f;

            // PENGURANGAN
            c.drawText("PENGURANGAN", ML, y, pBold); y += 15f;
            if (d.deductions.isEmpty()) {
                c.drawText("-  (tidak ada)", ML + 4f, y, pLabel); y += 14f;
            } else {
                for (PayslipData.Line l : d.deductions) {
                    y = drawLine(c, y, l, pLabel, pSmall);
                }
            }
            y += 2f;
            c.drawLine(ML + 4f, y, W - MR, y, thin());
            y += 13f;
            c.drawText("Total Pengurangan", ML + 4f, y, pBold);
            right(c, X_AMT, y, "Rp " + NF.format(Math.round(d.totalPengurangan)), pBold);
            y += 18f;

            // Catatan jam kerja (kurang/cukup/lebih).
            String hrs = "Jam kerja periode: " + jam(d.totalHours) + " / " + jam(d.requiredHours)
                    + (d.hoursMet ? " (terpenuhi)." : " (kurang " + jam(d.shortfallHours) + ").");
            if (d.overtimeHours > 0.01) hrs += " Lembur harian: " + jam(d.overtimeHours) + ".";
            c.drawText(hrs, ML + 4f, y, pSmall); y += 16f;

            // TOTAL DITERIMA (kotak)
            c.drawLine(ML, y, W - MR, y, rule);
            y += 15f;
            c.drawText("TOTAL DITERIMA KARYAWAN", ML, y, pBold);
            right(c, X_AMT, y, "Rp " + NF.format(Math.round(d.totalDiterima)), pTitle);
            y += 8f;
            c.drawLine(ML, y, W - MR, y, rule);
            y += 18f;

            // Transfer
            String bank = (d.bankName != null && !d.bankName.isEmpty() ? d.bankName + " " : "")
                    + (d.bankNo != null ? d.bankNo : "");
            String holder = d.bankHolder != null && !d.bankHolder.isEmpty()
                    ? d.bankHolder : d.userName;
            if (!bank.trim().isEmpty()) {
                c.drawText("TRANSFER KE " + bank.trim() + " a/n " + holder, ML, y, pBold);
                y += 24f;
            }

            // Tanda tangan
            float ySign = y + 8f;
            c.drawText("Penerima", ML + 60f, ySign, pLabel);
            c.drawText(d.depot != null ? d.depot : "", W - MR - 160f, ySign, pLabel);
            float yName = ySign + 80f;
            c.drawText(holder, ML + 20f, yName, pLabel);
            c.drawText("( ............................ )", W - MR - 180f, yName, pSmall);

            // Footer timestamp
            c.drawText(new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US).format(new Date()),
                    ML, H - 24f, pSmall);

            pdf.finishPage(page);

            File dir = new File(ctx.getExternalFilesDir(null), "exports");
            if (!dir.exists() && !dir.mkdirs()) { pdf.close(); return null; }
            String safe = userName != null ? userName.replaceAll("[^a-zA-Z0-9]+", "_") : "staf";
            File file = new File(dir, "SlipGaji_" + safe + "_" + end + ".pdf");
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

    private static float drawLine(Canvas c, float y, PayslipData.Line l,
                                  Paint pLabel, Paint pSmall) {
        c.drawText("-  " + l.label, ML + 4f, y, pLabel);
        if (l.showQty) {
            c.drawText(trimQty(l.qty) + " x", X_QTY, y, pLabel);
            right(c, X_RATE, y, "Rp" + NF.format(Math.round(l.rate)), pLabel);
        }
        right(c, X_AMT, y, "Rp" + NF.format(Math.round(l.amount)), pLabel);
        y += 14f;
        if (l.note != null && !l.note.isEmpty()) {
            c.drawText("   " + l.note, ML + 4f, y, pSmall);
            y += 12f;
        }
        return y;
    }

    private static void kv(Canvas c, float x, float y, String k, String v, Paint p) {
        c.drawText(k, x, y, p);
        right(c, W - MR, y, v != null ? v : "-", p);
    }

    private static void right(Canvas c, float xRight, float y, String text, Paint p) {
        c.drawText(text, xRight - p.measureText(text), y, p);
    }

    private static String disp(String yyyyMMdd) {
        try {
            Date d = SDF_DB.parse(yyyyMMdd);
            return d != null ? SDF_DISP.format(d) : yyyyMMdd;
        } catch (Exception e) { return yyyyMMdd; }
    }

    private static String trimQty(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format(Locale.US, "%.2f", v);
    }

    private static String jam(double hours) {
        long h = (long) hours;
        long m = Math.round((hours - h) * 60);
        return h + "j " + m + "m";
    }

    private static Paint paint(float size, int color, boolean bold) {
        Paint p = new Paint();
        p.setColor(color);
        p.setTextSize(size);
        p.setFakeBoldText(bold);
        p.setAntiAlias(true);
        return p;
    }

    private static Paint thin() {
        Paint p = new Paint();
        p.setColor(Color.parseColor("#999999"));
        p.setStrokeWidth(0.6f);
        return p;
    }
}
