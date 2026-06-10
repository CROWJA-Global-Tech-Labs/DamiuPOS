# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools proguard defaults.

# Apache POI (core) — enkripsi XLSX agile saat laporan shift Pulang.
# Builder agile dipanggil via method-ref (R8 sudah follow), keep ini hanya
# pengaman tambahan. log4j-api opsional → jangan warning kalau tak ada binding.
-keep class org.apache.poi.poifs.crypt.** { *; }
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.commons.**

# JavaMail (android-mail) — auto-kirim email laporan shift via SMTP.
# Provider SMTP ditemukan via reflection + resource META-INF, jadi di-keep.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-dontwarn com.sun.mail.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**
-dontwarn java.awt.**
-dontwarn javax.security.**
