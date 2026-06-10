package com.crowja.damiupos;

import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Enkripsi XLSX dengan ECMA-376 Agile Encryption (password-protected workbook
 * yang dibuka Excel dengan prompt password). Memakai Apache POI core saja:
 * {@link Encryptor} cukup meng-AES byte mentah yang kita tulis, jadi kita bisa
 * membangun .xlsx sendiri ({@link XlsxWriter}) tanpa poi-ooxml/xmlbeans.
 */
public final class XlsxEncryptor {

    private XlsxEncryptor() {}

    /**
     * Tulis {@code plainXlsx} (byte .xlsx valid) ke {@code out} dalam bentuk
     * terenkripsi agile dengan {@code password}. Melempar exception kalau gagal.
     */
    public static void encrypt(byte[] plainXlsx, File out, String password) throws Exception {
        POIFSFileSystem fs = new POIFSFileSystem();
        try {
            EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
            Encryptor enc = info.getEncryptor();
            enc.confirmPassword(password);
            // Encryptor meng-AES apa pun yang ditulis ke stream ini; setelah
            // didekripsi Excel mendapat persis byte .xlsx kita.
            try (OutputStream os = enc.getDataStream(fs);
                 InputStream is = new ByteArrayInputStream(plainXlsx)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
            }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fs.writeFilesystem(fos);
            }
        } finally {
            try { fs.close(); } catch (Exception ignored) {}
        }
    }
}
