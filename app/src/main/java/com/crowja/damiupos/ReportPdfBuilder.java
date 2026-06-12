package com.crowja.damiupos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ExpenseDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Expense;
import com.crowja.damiupos.model.Transaction;

import java.io.File;
import java.io.FileOutputStream;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pembuat PDF laporan (penjualan + pengeluaran + omset) — dipisah dari
 * ExportActivity supaya bisa dipakai ulang (mis. Rekap Pekanan otomatis).
 * Tata letak A4 landscape, sama dengan export manual.
 */
public final class ReportPdfBuilder {

    private ReportPdfBuilder() {}

    private static final NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
    private static final SimpleDateFormat sdfDb = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat sdfDisplay =
            new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID"));

    private static final float PAGE_WIDTH = 842f;   // A4 landscape
    private static final float PAGE_HEIGHT = 595f;
    private static final float MARGIN_LEFT = 30f;
    private static final float MARGIN_RIGHT = 30f;
    private static final float MARGIN_TOP = 30f;
    private static final float MARGIN_BOTTOM = 30f;
    private static final float ROW_HEIGHT = 18f;
    private static final float HEADER_HEIGHT = 22f;

    private static final float[] COL_WIDTHS = {30f, 95f, 155f, 130f, 60f, 40f, 110f, 130f};
    private static final String[] COL_HEADERS = {
            "No", "Tanggal", "Pelanggan", "Jenis Air", "Tipe", "Qty", "Harga/Galon", "Total"};
    private static final char[] COL_ALIGN = {'C', 'L', 'L', 'L', 'C', 'R', 'R', 'R'};

    private static final float[] EXPENSE_COL_WIDTHS = {110f, 470f, 170f};
    private static final String[] EXPENSE_COL_HEADERS = {"Tanggal", "Nama Pengeluaran", "Nominal"};
    private static final char[] EXPENSE_COL_ALIGN = {'L', 'L', 'R'};

    /**
     * Bangun PDF untuk periode [startDate, endDate] (format "yyyy-MM-dd"), tulis
     * ke folder exports, return File. Null kalau tidak ada data / gagal.
     */
    public static File build(Context ctx, String startDate, String endDate,
                             boolean wantPenjualan, boolean wantPengeluaran, boolean wantOmset) {
        try {
            DatabaseHelper dbHelper = DatabaseHelper.getInstance(ctx);
            TransactionDao transactionDao = new TransactionDao(dbHelper);
            ExpenseDao expenseDao = new ExpenseDao(dbHelper);
            SettingsDao settingsDao = new SettingsDao(dbHelper);
            String depotName = settingsDao.getDepotName();
            if (depotName == null || depotName.isEmpty()) depotName = "DAMIU POS";

            List<Transaction> transactions = wantPenjualan || wantOmset
                    ? transactionDao.getByDateRange(startDate, endDate)
                    : Collections.emptyList();
            List<Expense> expenses = wantPengeluaran || wantOmset
                    ? expenseDao.getByDateRange(startDate, endDate)
                    : Collections.emptyList();
            if (transactions.isEmpty() && expenses.isEmpty()) return null;

            PdfDocument pdf = new PdfDocument();
            Paint pTitle = new Paint();
            pTitle.setColor(Color.BLACK); pTitle.setTextSize(16f);
            pTitle.setFakeBoldText(true); pTitle.setAntiAlias(true);
            Paint pSub = new Paint();
            pSub.setColor(Color.DKGRAY); pSub.setTextSize(10f); pSub.setAntiAlias(true);
            Paint pHeader = new Paint();
            pHeader.setColor(Color.WHITE); pHeader.setTextSize(10f);
            pHeader.setFakeBoldText(true); pHeader.setAntiAlias(true);
            Paint pHeaderBg = new Paint(); pHeaderBg.setColor(Color.parseColor("#1565C0"));
            Paint pCell = new Paint();
            pCell.setColor(Color.BLACK); pCell.setTextSize(9f); pCell.setAntiAlias(true);
            Paint pZebra = new Paint(); pZebra.setColor(Color.parseColor("#F5F5F5"));
            Paint pBorder = new Paint();
            pBorder.setColor(Color.parseColor("#CCCCCC"));
            pBorder.setStyle(Paint.Style.STROKE); pBorder.setStrokeWidth(0.5f);

            double[] summary = transactionDao.getSummaryByDateRange(startDate, endDate);
            double totalExpense = 0;
            for (Expense e : expenses) totalExpense += e.getAmount();

            int pageNum = 1;
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                    (int) PAGE_WIDTH, (int) PAGE_HEIGHT, pageNum).create();
            PdfDocument.Page page = pdf.startPage(info);
            Canvas c = page.getCanvas();

            float y = drawPdfHeader(c, depotName, startDate, endDate, summary, totalExpense,
                    wantOmset, pTitle, pSub);

            if (!wantPenjualan) {
                if (wantPengeluaran && !expenses.isEmpty()) {
                    Object[] state = renderExpensesTable(pdf, page, c, expenses,
                            pageNum, pTitle, pSub, pHeader, pHeaderBg, pCell, pBorder, pZebra);
                    page = (PdfDocument.Page) state[0];
                    c = page.getCanvas();
                    pageNum = (int) state[1];
                }
                drawPdfFooter(c, pageNum, pSub);
                pdf.finishPage(page);
                return write(ctx, pdf, startDate, endDate);
            }

            y = drawPdfTableHeader(c, y, pHeader, pHeaderBg);
            int rowIdx = 0, no = 1;
            for (Transaction trx : transactions) {
                if (y + ROW_HEIGHT > PAGE_HEIGHT - MARGIN_BOTTOM) {
                    drawPdfFooter(c, pageNum, pSub);
                    pdf.finishPage(page);
                    pageNum++;
                    info = new PdfDocument.PageInfo.Builder(
                            (int) PAGE_WIDTH, (int) PAGE_HEIGHT, pageNum).create();
                    page = pdf.startPage(info);
                    c = page.getCanvas();
                    y = drawPdfTableHeader(c, MARGIN_TOP, pHeader, pHeaderBg);
                    rowIdx = 0;
                }
                String tipe = Transaction.TYPE_JUAL.equals(trx.getType()) ? "Jual" : "Kembali";
                String prodName = trx.getProductName() != null ? trx.getProductName() : "-";
                if (trx.getItems() != null && trx.getItems().size() > 1) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < trx.getItems().size(); i++) {
                        if (i > 0) sb.append(" + ");
                        sb.append(trx.getItems().get(i).productName);
                    }
                    prodName = sb.toString();
                }
                if (trx.getCatatan() != null && trx.getCatatan().contains("[JUAL BOTOL KOSONG]")) {
                    prodName = "Botol Galon Kosong";
                }
                String tanggal = trx.getTanggal() != null ? trx.getTanggal() : "";
                if (tanggal.length() >= 16) tanggal = tanggal.substring(0, 16);
                String[] cells = new String[]{
                        String.valueOf(no++), tanggal,
                        formatCustomer(trx.getCustomerName(), trx.getCustomerPhone()),
                        prodName, tipe, String.valueOf(trx.getJumlahGalon()),
                        Transaction.TYPE_JUAL.equals(trx.getType())
                                ? "Rp " + nf.format(trx.getHargaPerGalon()) : "-",
                        Transaction.TYPE_JUAL.equals(trx.getType()) || trx.getTotalHarga() > 0
                                ? "Rp " + nf.format(trx.getTotalHarga()) : "-"};
                if (rowIdx % 2 == 1) {
                    c.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + sumWidths(), y + ROW_HEIGHT, pZebra);
                }
                drawPdfRow(c, y, cells, pCell, pBorder);
                y += ROW_HEIGHT;
                rowIdx++;
            }

            if (wantPengeluaran && !expenses.isEmpty()) {
                Object[] state = renderExpensesTable(pdf, page, c, expenses,
                        pageNum, pTitle, pSub, pHeader, pHeaderBg, pCell, pBorder, pZebra);
                page = (PdfDocument.Page) state[0];
                c = page.getCanvas();
                pageNum = (int) state[1];
            }

            drawPdfFooter(c, pageNum, pSub);
            pdf.finishPage(page);
            return write(ctx, pdf, startDate, endDate);
        } catch (Throwable t) {
            return null;
        }
    }

    private static File write(Context ctx, PdfDocument pdf, String startDate, String endDate) {
        File dir = new File(ctx.getExternalFilesDir(null), "exports");
        if (!dir.exists() && !dir.mkdirs()) { pdf.close(); return null; }
        File file = new File(dir, "Laporan_DAMIU_" + startDate + "_" + endDate + ".pdf");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            pdf.writeTo(fos);
        } catch (Exception e) {
            pdf.close();
            return null;
        }
        pdf.close();
        return file;
    }

    private static float sumExpenseWidths() {
        float s = 0;
        for (float w : EXPENSE_COL_WIDTHS) s += w;
        return s;
    }

    private static Object[] renderExpensesTable(PdfDocument pdf, PdfDocument.Page page,
                                                Canvas c, List<Expense> expenses, int pageNum,
                                                Paint pTitle, Paint pSub, Paint pHeader,
                                                Paint pHeaderBg, Paint pCell, Paint pBorder,
                                                Paint pZebra) {
        drawPdfFooter(c, pageNum, pSub);
        pdf.finishPage(page);
        pageNum++;
        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                (int) PAGE_WIDTH, (int) PAGE_HEIGHT, pageNum).create();
        page = pdf.startPage(info);
        c = page.getCanvas();
        float y = MARGIN_TOP + 14f;
        c.drawText("PENGELUARAN OPERASIONAL", MARGIN_LEFT, y, pTitle);
        y += 18f;

        c.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + sumExpenseWidths(), y + HEADER_HEIGHT, pHeaderBg);
        float x = MARGIN_LEFT;
        float textY = y + HEADER_HEIGHT - 7f;
        for (int i = 0; i < EXPENSE_COL_HEADERS.length; i++) {
            float colW = EXPENSE_COL_WIDTHS[i];
            c.drawText(EXPENSE_COL_HEADERS[i],
                    textX(x, colW, EXPENSE_COL_HEADERS[i], EXPENSE_COL_ALIGN[i], pHeader), textY, pHeader);
            x += colW;
        }
        y += HEADER_HEIGHT;

        int rowIdx = 0;
        double total = 0;
        for (Expense e : expenses) {
            if (y + ROW_HEIGHT > PAGE_HEIGHT - MARGIN_BOTTOM) {
                drawPdfFooter(c, pageNum, pSub);
                pdf.finishPage(page);
                pageNum++;
                info = new PdfDocument.PageInfo.Builder(
                        (int) PAGE_WIDTH, (int) PAGE_HEIGHT, pageNum).create();
                page = pdf.startPage(info);
                c = page.getCanvas();
                y = MARGIN_TOP;
                c.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + sumExpenseWidths(), y + HEADER_HEIGHT, pHeaderBg);
                x = MARGIN_LEFT;
                textY = y + HEADER_HEIGHT - 7f;
                for (int i = 0; i < EXPENSE_COL_HEADERS.length; i++) {
                    float colW = EXPENSE_COL_WIDTHS[i];
                    c.drawText(EXPENSE_COL_HEADERS[i],
                            textX(x, colW, EXPENSE_COL_HEADERS[i], EXPENSE_COL_ALIGN[i], pHeader), textY, pHeader);
                    x += colW;
                }
                y += HEADER_HEIGHT;
                rowIdx = 0;
            }
            String tgl = e.getCreatedAt() != null ? e.getCreatedAt() : "";
            if (tgl.length() >= 16) tgl = tgl.substring(0, 16);
            String[] cells = new String[]{
                    tgl, e.getName() != null ? e.getName() : "(tanpa nama)",
                    "Rp " + nf.format(e.getAmount())};
            if (rowIdx % 2 == 1) {
                c.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + sumExpenseWidths(), y + ROW_HEIGHT, pZebra);
            }
            x = MARGIN_LEFT;
            textY = y + ROW_HEIGHT - 6f;
            for (int i = 0; i < cells.length; i++) {
                float colW = EXPENSE_COL_WIDTHS[i];
                String text = ellipsize(cells[i], colW - 6f, pCell);
                c.drawText(text, textX(x, colW, text, EXPENSE_COL_ALIGN[i], pCell), textY, pCell);
                c.drawLine(x, y, x, y + ROW_HEIGHT, pBorder);
                x += colW;
            }
            c.drawLine(x, y, x, y + ROW_HEIGHT, pBorder);
            c.drawLine(MARGIN_LEFT, y + ROW_HEIGHT, MARGIN_LEFT + sumExpenseWidths(), y + ROW_HEIGHT, pBorder);
            total += e.getAmount();
            y += ROW_HEIGHT;
            rowIdx++;
        }
        y += 8f;
        c.drawText("Total Pengeluaran: Rp " + nf.format(total), MARGIN_LEFT, y, pTitle);
        return new Object[]{page, pageNum};
    }

    private static float sumWidths() {
        float s = 0;
        for (float w : COL_WIDTHS) s += w;
        return s;
    }

    private static float drawPdfHeader(Canvas c, String depotName, String startDate, String endDate,
                                       double[] summary, double totalExpense, boolean wantOmset,
                                       Paint pTitle, Paint pSub) {
        float y = MARGIN_TOP + 14f;
        c.drawText("LAPORAN — " + depotName.toUpperCase(Locale.getDefault()), MARGIN_LEFT, y, pTitle);
        y += 14f;
        String periode;
        try {
            Date d1 = sdfDb.parse(startDate);
            Date d2 = sdfDb.parse(endDate);
            periode = startDate.equals(endDate)
                    ? "Periode: " + sdfDisplay.format(d1)
                    : "Periode: " + sdfDisplay.format(d1) + " — " + sdfDisplay.format(d2);
        } catch (Exception e) {
            periode = "Periode: " + startDate + " — " + endDate;
        }
        c.drawText(periode, MARGIN_LEFT, y, pSub);
        y += 12f;
        c.drawText("Diekspor: " + sdfDisplay.format(new Date()), MARGIN_LEFT, y, pSub);
        y += 16f;
        if (wantOmset) {
            String ringkasan = "Total Trx: " + (int) summary[0]
                    + "    Galon Keluar: " + (int) summary[1]
                    + "    Galon Kembali: " + (int) summary[2]
                    + "    Pendapatan: Rp " + nf.format(summary[3]);
            c.drawText(ringkasan, MARGIN_LEFT, y, pSub);
            y += 12f;
            String laba = "Pengeluaran: Rp " + nf.format(totalExpense)
                    + "    Laba Bersih: Rp " + nf.format(summary[3] - totalExpense);
            c.drawText(laba, MARGIN_LEFT, y, pSub);
            y += 18f;
        }
        return y;
    }

    private static float drawPdfTableHeader(Canvas c, float y, Paint pHeader, Paint pHeaderBg) {
        c.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + sumWidths(), y + HEADER_HEIGHT, pHeaderBg);
        float x = MARGIN_LEFT;
        float textY = y + HEADER_HEIGHT - 7f;
        for (int i = 0; i < COL_HEADERS.length; i++) {
            float colW = COL_WIDTHS[i];
            c.drawText(COL_HEADERS[i], textX(x, colW, COL_HEADERS[i], COL_ALIGN[i], pHeader), textY, pHeader);
            x += colW;
        }
        return y + HEADER_HEIGHT;
    }

    private static void drawPdfRow(Canvas c, float y, String[] cells, Paint pCell, Paint pBorder) {
        float x = MARGIN_LEFT;
        float textY = y + ROW_HEIGHT - 6f;
        for (int i = 0; i < cells.length; i++) {
            float colW = COL_WIDTHS[i];
            String text = ellipsize(cells[i] != null ? cells[i] : "", colW - 6f, pCell);
            c.drawText(text, textX(x, colW, text, COL_ALIGN[i], pCell), textY, pCell);
            c.drawLine(x, y, x, y + ROW_HEIGHT, pBorder);
            x += colW;
        }
        c.drawLine(x, y, x, y + ROW_HEIGHT, pBorder);
        c.drawLine(MARGIN_LEFT, y + ROW_HEIGHT, MARGIN_LEFT + sumWidths(), y + ROW_HEIGHT, pBorder);
    }

    private static void drawPdfFooter(Canvas c, int pageNum, Paint pSub) {
        c.drawText("Halaman " + pageNum, PAGE_WIDTH - MARGIN_RIGHT - 60f,
                PAGE_HEIGHT - MARGIN_BOTTOM + 18f, pSub);
    }

    private static float textX(float colX, float colW, String text, char align, Paint paint) {
        float padding = 4f;
        if (align == 'R') return colX + colW - padding - paint.measureText(text);
        if (align == 'C') return colX + (colW - paint.measureText(text)) / 2f;
        return colX + padding;
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

    private static String formatCustomer(String name, String phone) {
        String n = name != null && !name.isEmpty() ? name : "-";
        if (phone != null && !phone.trim().isEmpty()) return n + " (" + phone.trim() + ")";
        return n;
    }
}
