package com.crowja.damiupos;

import android.content.Context;

import com.crowja.damiupos.db.DatabaseHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Slip gaji dalam bentuk XLSX dengan FORMULA hidup: tiap baris Jumlah = Qty ×
 * Rate, total = SUM(...), dan Total Diterima = Bruto − Pengurangan. Dibuka di
 * Excel/Sheets akan terhitung ulang otomatis.
 */
public final class PayslipXlsx {

    private PayslipXlsx() {}

    private static final SimpleDateFormat SDF_DB = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat SDF_DISP =
            new SimpleDateFormat("d MMM yyyy", new Locale("id", "ID"));

    /** Slip gaji XLSX polos → file. */
    public static File build(Context ctx, DatabaseHelper db, long userId, String userName,
                             String start, String end) {
        try {
            byte[] xlsx = buildBytes(db, userId, userName, start, end);
            File file = newFile(ctx, userName, end, false);
            if (file == null) return null;
            try (FileOutputStream fos = new FileOutputStream(file)) { fos.write(xlsx); }
            return file;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Slip gaji: XLSX BIASA (berisi data lengkap) dibungkus ZIP ber-password
     * (PIN admin) memakai zip4j. Penerima ekstrak dengan PIN → XLSX biasa yang
     * bisa dibuka di penampil apa pun. ZIP juga diterima WhatsApp.
     */
    public static File buildProtectedZip(Context ctx, DatabaseHelper db, long userId,
                                         String userName, String start, String end,
                                         String password) {
        try {
            byte[] plain = buildBytes(db, userId, userName, start, end);
            File dir = new File(ctx.getExternalFilesDir(null), "exports");
            if (!dir.exists() && !dir.mkdirs()) return null;
            String safe = userName != null ? userName.replaceAll("[^a-zA-Z0-9]+", "_") : "staf";
            String xlsxName = "SlipGaji_" + safe + "_" + end + ".xlsx";
            File zip = new File(dir, "SlipGaji_" + safe + "_" + end + ".zip");
            if (zip.exists() && !zip.delete()) return null;   // zip4j append-safe

            net.lingala.zip4j.model.ZipParameters zp =
                    new net.lingala.zip4j.model.ZipParameters();
            zp.setEncryptFiles(true);
            // ZipCrypto standar → paling kompatibel dengan ekstraktor bawaan ponsel.
            zp.setEncryptionMethod(net.lingala.zip4j.model.enums.EncryptionMethod.ZIP_STANDARD);
            zp.setFileNameInZip(xlsxName);
            try (net.lingala.zip4j.io.outputstream.ZipOutputStream zos =
                         new net.lingala.zip4j.io.outputstream.ZipOutputStream(
                                 new FileOutputStream(zip), password.toCharArray())) {
                zos.putNextEntry(zp);
                zos.write(plain);
                zos.closeEntry();
            }
            return zip;
        } catch (Throwable t) {
            return null;
        }
    }

    private static File newFile(Context ctx, String userName, String end, boolean locked) {
        File dir = new File(ctx.getExternalFilesDir(null), "exports");
        if (!dir.exists() && !dir.mkdirs()) return null;
        String safe = userName != null ? userName.replaceAll("[^a-zA-Z0-9]+", "_") : "staf";
        return new File(dir, "SlipGaji_" + safe + "_" + end
                + (locked ? "_terkunci" : "") + ".xlsx");
    }

    /** Bangun byte XLSX polos slip gaji (formula hidup). */
    private static byte[] buildBytes(DatabaseHelper db, long userId, String userName,
                                     String start, String end) throws java.io.IOException {
        {
            PayslipData d = PayslipData.build(db, userId, userName, start, end);
            List<Object[]> rows = new ArrayList<>();

            rows.add(new Object[]{cell("SLIP GAJI — " + d.depot, XlsxWriter.STYLE_TITLE)});
            rows.add(new Object[]{"Periode", disp(start) + " - " + disp(end)});
            rows.add(new Object[]{"Karyawan", d.userName});
            rows.add(new Object[]{"Jabatan", d.jabatan});
            rows.add(new Object[]{"Status", d.status});
            rows.add(new Object[]{});

            // PENERIMAAN
            rows.add(new Object[]{cell("PENERIMAAN", XlsxWriter.STYLE_TITLE)});
            rows.add(header("Keterangan", "Qty", "Rate", "Jumlah"));
            int firstInc = rows.size() + 1;
            for (PayslipData.Line l : d.incomes) {
                int r = rows.size() + 1;
                rows.add(new Object[]{
                        cell(l.label, XlsxWriter.STYLE_DATA),
                        cell(l.qty, XlsxWriter.STYLE_DATA),
                        cell(l.rate, XlsxWriter.STYLE_CURRENCY),
                        new XlsxWriter.Formula("B" + r + "*C" + r, l.amount, XlsxWriter.STYLE_CURRENCY)});
            }
            int lastInc = rows.size();
            int brutoRow = rows.size() + 1;
            boolean hasInc = lastInc >= firstInc;
            rows.add(new Object[]{
                    cell("Total Penghasilan Bruto", XlsxWriter.STYLE_TITLE), "", "",
                    hasInc ? new XlsxWriter.Formula("SUM(D" + firstInc + ":D" + lastInc + ")",
                            d.totalBruto, XlsxWriter.STYLE_CURRENCY_BOLD)
                            : cell(d.totalBruto, XlsxWriter.STYLE_CURRENCY_BOLD)});

            rows.add(new Object[]{});

            // PENGURANGAN
            rows.add(new Object[]{cell("PENGURANGAN", XlsxWriter.STYLE_TITLE)});
            rows.add(header("Keterangan", "Qty", "Rate", "Jumlah"));
            int firstDed = rows.size() + 1;
            for (PayslipData.Line l : d.deductions) {
                int r = rows.size() + 1;
                rows.add(new Object[]{
                        cell(l.label, XlsxWriter.STYLE_DATA),
                        cell(l.qty, XlsxWriter.STYLE_DATA),
                        cell(l.rate, XlsxWriter.STYLE_CURRENCY),
                        new XlsxWriter.Formula("B" + r + "*C" + r, l.amount, XlsxWriter.STYLE_CURRENCY)});
            }
            int lastDed = rows.size();
            int pengRow = rows.size() + 1;
            boolean hasDed = lastDed >= firstDed;
            rows.add(new Object[]{
                    cell("Total Pengurangan", XlsxWriter.STYLE_TITLE), "", "",
                    hasDed ? new XlsxWriter.Formula("SUM(D" + firstDed + ":D" + lastDed + ")",
                            d.totalPengurangan, XlsxWriter.STYLE_CURRENCY_BOLD)
                            : cell(d.totalPengurangan, XlsxWriter.STYLE_CURRENCY_BOLD)});

            rows.add(new Object[]{});

            // TOTAL DITERIMA = bruto - pengurangan
            int netRow = rows.size() + 1;
            rows.add(new Object[]{
                    cell("TOTAL DITERIMA KARYAWAN", XlsxWriter.STYLE_TITLE), "", "",
                    new XlsxWriter.Formula("D" + brutoRow + "-D" + pengRow,
                            d.totalDiterima, XlsxWriter.STYLE_CURRENCY_BOLD)});

            rows.add(new Object[]{});
            String jk = jam(d.totalHours) + " / ideal " + jam(d.requiredHours)
                    + (d.hoursMet ? " (cukup)" : " (kurang " + jam(d.shortfallHours) + ")");
            if (d.overtimeHours > 0.01) jk += " • Lembur harian " + jam(d.overtimeHours);
            rows.add(new Object[]{"Jam kerja periode", jk});
            String bank = (d.bankName != null && !d.bankName.isEmpty() ? d.bankName + " " : "")
                    + (d.bankNo != null ? d.bankNo : "");
            String holder = d.bankHolder != null && !d.bankHolder.isEmpty()
                    ? d.bankHolder : d.userName;
            if (!bank.trim().isEmpty()) {
                rows.add(new Object[]{"Transfer ke", bank.trim() + " a/n " + holder});
            }

            return XlsxWriter.toBytes("Slip Gaji", rows, new double[]{34, 8, 14, 16});
        }
    }

    private static XlsxWriter.Cell cell(Object v, int style) {
        return new XlsxWriter.Cell(v, style);
    }

    private static Object[] header(Object... vals) {
        Object[] out = new Object[vals.length];
        for (int i = 0; i < vals.length; i++) out[i] = new XlsxWriter.Cell(vals[i], XlsxWriter.STYLE_HEADER);
        return out;
    }

    private static String disp(String yyyyMMdd) {
        try {
            Date d = SDF_DB.parse(yyyyMMdd);
            return d != null ? SDF_DISP.format(d) : yyyyMMdd;
        } catch (Exception e) { return yyyyMMdd; }
    }

    private static String jam(double hours) {
        long h = (long) hours;
        long m = Math.round((hours - h) * 60);
        return h + "j " + m + "m";
    }
}
