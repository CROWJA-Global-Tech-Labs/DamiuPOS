package com.crowja.damiupos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Penulis XLSX minimal tanpa dependensi (Apache POI tidak dipakai untuk
 * membangun workbook — hanya untuk enkripsi). Sebuah .xlsx hanyalah ZIP berisi
 * beberapa part XML; di sini kita tulis satu sheet dengan inline strings, angka,
 * lebar kolom, border, dan style header tebal.
 *
 * <p>Sel boleh berupa {@link Cell} (nilai + index style) atau objek biasa
 * ({@link Number} → numeric, selain itu → inline string, style default).
 */
public final class XlsxWriter {

    private XlsxWriter() {}

    // Index style yang tersedia (lihat STYLES_XML / cellXfs).
    public static final int STYLE_DEFAULT = 0; // normal, tanpa border
    public static final int STYLE_TITLE = 1;   // tebal (judul)
    public static final int STYLE_HEADER = 2;  // tebal + fill abu + border + center
    public static final int STYLE_DATA = 3;    // border tipis semua sisi
    public static final int STYLE_CURRENCY = 4;      // angka + border + format "Rp"
    public static final int STYLE_CURRENCY_BOLD = 5; // angka tebal + format "Rp" (total)

    /** Sel dengan style eksplisit. */
    public static final class Cell {
        final Object value;
        final int style;
        public Cell(Object value, int style) {
            this.value = value;
            this.style = style;
        }
    }

    /**
     * Sel formula: ditulis sebagai {@code <c><f>expr</f><v>cached</v></c>} sehingga
     * Excel/LibreOffice menghitung ulang saat dibuka. {@code expr} TANPA tanda "="
     * di depan (mis. "C5*D5", "SUM(E5:E9)"). {@code cached} = nilai cache awal.
     */
    public static final class Formula {
        final String expr;
        final double cached;
        final int style;
        public Formula(String expr, double cached, int style) {
            this.expr = expr;
            this.cached = cached;
            this.style = style;
        }
    }

    private static final String CONTENT_TYPES =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "</Types>";

    private static final String ROOT_RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>";

    private static final String WORKBOOK_RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "</Relationships>";

    // Fills: 0 none, 1 gray125 (wajib ada index ini per konvensi Excel),
    // 2 solid abu untuk header. Borders: 0 kosong, 1 thin semua sisi.
    private static final String STYLES_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            // Format mata uang Rupiah (numFmtId custom 164) → tampil "Rp1.000.000"
            // tapi nilai sel tetap angka (formula tetap jalan).
            "<numFmts count=\"1\">" +
            "<numFmt numFmtId=\"164\" formatCode=\"&quot;Rp&quot;#,##0\"/>" +
            "</numFmts>" +
            "<fonts count=\"2\">" +
            "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
            "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
            "</fonts>" +
            "<fills count=\"3\">" +
            "<fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFD9D9D9\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
            "</fills>" +
            "<borders count=\"2\">" +
            "<border><left/><right/><top/><bottom/><diagonal/></border>" +
            "<border>" +
            "<left style=\"thin\"><color indexed=\"64\"/></left>" +
            "<right style=\"thin\"><color indexed=\"64\"/></right>" +
            "<top style=\"thin\"><color indexed=\"64\"/></top>" +
            "<bottom style=\"thin\"><color indexed=\"64\"/></bottom>" +
            "<diagonal/></border>" +
            "</borders>" +
            "<cellStyleXfs count=\"1\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>" +
            "</cellStyleXfs>" +
            "<cellXfs count=\"6\">" +
            // 0 default
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            // 1 title (bold)
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            // 2 header (bold + fill + border + center + wrap)
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">" +
            "<alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>" +
            // 3 data (border)
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\"/>" +
            // 4 currency data (border + format Rp)
            "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\"/>" +
            // 5 currency bold (untuk total)
            "<xf numFmtId=\"164\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/>" +
            "</cellXfs>" +
            "<cellStyles count=\"1\">" +
            "<cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/>" +
            "</cellStyles>" +
            "</styleSheet>";

    /** Tulis satu sheet ke {@code out} (tanpa lebar kolom). Tidak menutup {@code out}. */
    public static void write(OutputStream out, String sheetName, List<Object[]> rows)
            throws IOException {
        write(out, sheetName, rows, null);
    }

    /**
     * Tulis satu sheet ke {@code out} dengan {@code colWidths} (lebar kolom dalam
     * satuan karakter Excel; null = default). Tidak menutup {@code out}.
     */
    public static void write(OutputStream out, String sheetName, List<Object[]> rows,
                             double[] colWidths) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(out);
        put(zos, "[Content_Types].xml", CONTENT_TYPES);
        put(zos, "_rels/.rels", ROOT_RELS);
        put(zos, "xl/workbook.xml", workbookXml(sheetName));
        put(zos, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
        put(zos, "xl/styles.xml", STYLES_XML);
        put(zos, "xl/worksheets/sheet1.xml", sheetXml(rows, colWidths));
        zos.finish();
    }

    private static String workbookXml(String sheetName) {
        String name = sheetName != null ? sheetName : "Sheet1";
        // Sheet name max 31 char, buang karakter terlarang Excel: : \ / ? * [ ]
        name = name.replaceAll("[:\\\\/?*\\[\\]]", " ").trim();
        if (name.isEmpty()) name = "Sheet1";
        if (name.length() > 31) name = name.substring(0, 31);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"" + xml(name) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
                "</workbook>";
    }

    private static String sheetXml(List<Object[]> rows, double[] colWidths) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");

        // <cols> harus sebelum <sheetData>.
        if (colWidths != null && colWidths.length > 0) {
            sb.append("<cols>");
            for (int i = 0; i < colWidths.length; i++) {
                int col = i + 1;
                sb.append("<col min=\"").append(col).append("\" max=\"").append(col)
                  .append("\" width=\"").append(colWidths[i]).append("\" customWidth=\"1\"/>");
            }
            sb.append("</cols>");
        }

        sb.append("<sheetData>");
        int r = 1;
        for (Object[] row : rows) {
            sb.append("<row r=\"").append(r).append("\">");
            int col = 0;
            if (row != null) {
                for (Object raw : row) {
                    if (raw instanceof Formula) {
                        Formula f = (Formula) raw;
                        String ref = colName(col) + r;
                        String sAttr = f.style != 0 ? " s=\"" + f.style + "\"" : "";
                        sb.append("<c r=\"").append(ref).append("\"").append(sAttr)
                          .append("><f>").append(xml(f.expr)).append("</f><v>")
                          .append(numStr(f.cached)).append("</v></c>");
                        col++;
                        continue;
                    }
                    Object value = raw;
                    int style = STYLE_DEFAULT;
                    if (raw instanceof Cell) {
                        value = ((Cell) raw).value;
                        style = ((Cell) raw).style;
                    }
                    String ref = colName(col) + r;
                    String sAttr = style != 0 ? " s=\"" + style + "\"" : "";
                    if (value instanceof Number) {
                        sb.append("<c r=\"").append(ref).append("\"").append(sAttr)
                          .append("><v>").append(numStr((Number) value)).append("</v></c>");
                    } else {
                        String text = value != null ? value.toString() : "";
                        sb.append("<c r=\"").append(ref).append("\"").append(sAttr)
                          .append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                          .append(xml(text)).append("</t></is></c>");
                    }
                    col++;
                }
            }
            sb.append("</row>");
            r++;
        }
        sb.append("</sheetData></worksheet>");
        return sb.toString();
    }

    private static String numStr(Number n) {
        double d = n.doubleValue();
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    /** 0 → A, 25 → Z, 26 → AA, ... */
    private static String colName(int index) {
        StringBuilder sb = new StringBuilder();
        int i = index;
        do {
            sb.insert(0, (char) ('A' + (i % 26)));
            i = i / 26 - 1;
        } while (i >= 0);
        return sb.toString();
    }

    private static void put(ZipOutputStream zos, String path, String content)
            throws IOException {
        zos.putNextEntry(new ZipEntry(path));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String xml(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default:
                    // Buang karakter kontrol yang ilegal di XML 1.0.
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') b.append(c);
            }
        }
        return b.toString();
    }

    /** Helper: bangun xlsx ke byte[] (dipakai sebelum enkripsi). */
    public static byte[] toBytes(String sheetName, List<Object[]> rows) throws IOException {
        return toBytes(sheetName, rows, null);
    }

    public static byte[] toBytes(String sheetName, List<Object[]> rows, double[] colWidths)
            throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        write(bos, sheetName, rows, colWidths);
        return bos.toByteArray();
    }
}
