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
 * beberapa part XML; di sini kita tulis satu sheet dengan inline strings &
 * angka. Cukup untuk laporan shift, dan hasilnya valid dibuka Excel/LibreOffice.
 *
 * <p>Sel: {@link Number} → numeric cell, selain itu → inline string.
 */
public final class XlsxWriter {

    private XlsxWriter() {}

    private static final String CONTENT_TYPES =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
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
            "</Relationships>";

    /** Tulis satu sheet ke {@code out}. Tidak menutup {@code out}. */
    public static void write(OutputStream out, String sheetName, List<Object[]> rows)
            throws IOException {
        ZipOutputStream zos = new ZipOutputStream(out);
        put(zos, "[Content_Types].xml", CONTENT_TYPES);
        put(zos, "_rels/.rels", ROOT_RELS);
        put(zos, "xl/workbook.xml", workbookXml(sheetName));
        put(zos, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
        put(zos, "xl/worksheets/sheet1.xml", sheetXml(rows));
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

    private static String sheetXml(List<Object[]> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        sb.append("<sheetData>");
        int r = 1;
        for (Object[] row : rows) {
            sb.append("<row r=\"").append(r).append("\">");
            int col = 0;
            if (row != null) {
                for (Object cell : row) {
                    String ref = colName(col) + r;
                    if (cell instanceof Number) {
                        sb.append("<c r=\"").append(ref).append("\"><v>")
                          .append(numStr((Number) cell)).append("</v></c>");
                    } else {
                        String text = cell != null ? cell.toString() : "";
                        sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
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
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        write(bos, sheetName, rows);
        return bos.toByteArray();
    }
}
