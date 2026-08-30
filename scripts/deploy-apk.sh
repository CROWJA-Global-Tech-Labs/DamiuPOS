#!/usr/bin/env bash
# Build the signed release APK (default) and install it on a connected Android device.
# Add "debug" as the first arg to build+install the DEBUG variant instead.
#
# Transport dipilih OTOMATIS: kalau ada perangkat USB terhubung, itu yang dipakai; kalau tidak,
# skrip mencoba menyambung nirkabel sendiri (mDNS "Wireless debugging" Android 11+, lalu alamat
# terakhir yang pernah berhasil). Beda dari scripts/publish-apk.sh yang menerbitkan APK ke SELURUH
# armada lewat dashboard — yang ini memasang langsung ke SATU HP di tangan (uji cepat), tanpa
# menaikkan versi & tanpa menyentuh server.
#
# ⚠️ TIDAK PERNAH meng-uninstall apa pun. HP depot yang di-sign pabrik (mis. A325F/A750GN, cert
#    6ce910) — atau HP mana pun yang sedang memasang varian release (key repo d5ff87) sementara
#    kamu memasang "debug" (key debug bawaan Android), atau sebaliknya — menolak install -r karena
#    tanda tangannya beda: skrip berhenti dengan penjelasan, BUKAN uninstall diam-diam — uninstall
#    akan MENGHAPUS SELURUH DATA transaksi di HP itu.
#
# Usage: scripts/deploy-apk.sh          (build+pasang RELEASE, cermin publish-apk.sh)
#        scripts/deploy-apk.sh debug    (build+pasang DEBUG — untuk HP uji, cepat, tanpa signing key repo)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Gradle 8.13 (this project's wrapper) can't run its Groovy build-script compiler on JDK 24+
# ("Unsupported class file major version 69" on JDK 25) — pin to a known-compatible JDK if the
# system JAVA_HOME/PATH java is too new, instead of failing the whole build. Untracked (script-
# local), so it doesn't bake one machine's absolute JDK path into the versioned gradle.properties.
if command -v java >/dev/null 2>&1; then
    JAVA_MAJOR="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
    if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -ge 24 ]; then
        for CANDIDATE in "$HOME/.jdks/temurin-17.0.18" "$HOME/.jdks/jbr-17.0.14" \
                "/c/Program Files/Android/Android Studio/jbr"; do
            if [ -x "$CANDIDATE/bin/java" ]; then
                export JAVA_HOME="$CANDIDATE"
                echo "JDK sistem terlalu baru untuk Gradle 8.13 (major $JAVA_MAJOR) — pakai $JAVA_HOME"
                break
            fi
        done
    fi
fi

MODE="${1:-release}"
case "$MODE" in
    release) GRADLE_TASK="assembleRelease"; APK="app/build/outputs/apk/release/app-release.apk" ;;
    debug)   GRADLE_TASK="assembleDebug";   APK="app/build/outputs/apk/debug/app-debug.apk" ;;
    *)
        echo "ERROR: argumen '$MODE' tak dikenal — pakai 'debug' atau kosongkan (default: release)." >&2
        exit 1
        ;;
esac

GRADLE_FILE="app/build.gradle"
PKG="com.crowja.damiupos"
# Alamat nirkabel terakhir yang berhasil — disimpan DI LUAR repo (repo ini publik; jangan
# menaruh alamat jaringan depot di dalamnya).
STATE="${HOME}/.damiupos-adb-last-target"

# ---- Temukan adb (PATH → env SDK → lokasi default Android Studio) ---------------------------
find_adb() {
    if command -v adb >/dev/null 2>&1; then echo "adb"; return; fi
    local c
    for c in "${ANDROID_HOME:-}/platform-tools/adb" "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
             "${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe" \
             "${HOME}/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
             "${HOME}/Library/Android/sdk/platform-tools/adb" \
             "${HOME}/Android/Sdk/platform-tools/adb"; do
        [ -n "$c" ] && [ -x "$c" ] && { echo "$c"; return; }
    done
    return 1
}

ADB="$(find_adb)" || {
    echo "ERROR: adb tidak ditemukan." >&2
    echo "       Pasang Android SDK Platform-Tools, atau set ANDROID_HOME / tambahkan adb ke PATH." >&2
    exit 1
}

# ---- Daftar perangkat SIAP PAKAI (state 'device'); 'unauthorized'/'offline' dilaporkan terpisah --
list_ready() { "$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'; }
list_problem() { "$ADB" devices | awk 'NR>1 && NF>=2 && $2!="device" {print $1" ("$2")"}'; }

device_label() {
    local serial="$1" model
    model="$("$ADB" -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n' || true)"
    [ -n "$model" ] && echo "$model [$serial]" || echo "$serial"
}

# ---- Build release DULU (inkremental — cepat kalau tak ada perubahan) ------------------------
# Sengaja SEBELUM apa pun yang menyentuh adb: kalau build gagal, tak ada waktu terbuang mencoba
# menyambung nirkabel (mDNS bisa perlu beberapa detik) untuk pemasangan yang toh tak akan terjadi.
read_version_code() { sed -n 's/^[[:space:]]*versionCode[[:space:]]\+\([0-9]\+\).*/\1/p' "$GRADLE_FILE" | head -1; }
read_version_name() { sed -n 's/^[[:space:]]*versionName[[:space:]]\+"\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -1; }
VNAME="$(read_version_name)"; VCODE="$(read_version_code)"

if [ "$MODE" = "debug" ]; then
    echo "==> Building DEBUG APK (${VNAME:-?} / ${VCODE:-?})…"
else
    echo "==> Building signed release APK (${VNAME:-?} / ${VCODE:-?})…"
fi
if ! ./gradlew "$GRADLE_TASK" -q; then
    echo "ERROR: build gagal — tidak ada yang dipasang." >&2
    exit 1
fi
[ -f "$APK" ] || { echo "ERROR: $APK tidak ada setelah build." >&2; exit 1; }

SIZE="$(stat -c%s "$APK" 2>/dev/null || stat -f%z "$APK")"
echo "    $APK  (${SIZE} bytes)"

echo "==> Menyiapkan adb…"
"$ADB" start-server >/dev/null 2>&1 || true

mapfile -t DEVICES < <(list_ready)

# ---- Tidak ada perangkat USB → coba sambung NIRKABEL sendiri --------------------------------
if [ "${#DEVICES[@]}" -eq 0 ]; then
    echo "==> Tak ada perangkat USB — mencoba sambungan nirkabel…"

    # 1) mDNS "Wireless debugging" (Android 11+). Hanya perangkat yang SUDAH pernah di-pair yang
    #    muncul sebagai _adb-tls-connect; yang baru muncul sebagai _adb-tls-pairing (perlu `adb pair`).
    while read -r target; do
        [ -z "$target" ] && continue
        echo "    mDNS menemukan $target — menyambung…"
        "$ADB" connect "$target" >/dev/null 2>&1 || true
    done < <("$ADB" mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $NF}' | grep -E '^[0-9.]+:[0-9]+$' || true)

    # 2) Alamat terakhir yang pernah berhasil (HP yang IP-nya tetap / sudah `adb tcpip 5555`).
    if [ -s "$STATE" ]; then
        last="$(tr -d '\r\n' < "$STATE")"
        if [ -n "$last" ]; then
            echo "    Mencoba alamat terakhir: $last"
            "$ADB" connect "$last" >/dev/null 2>&1 || true
        fi
    fi

    mapfile -t DEVICES < <(list_ready)
fi

if [ "${#DEVICES[@]}" -eq 0 ]; then
    echo "ERROR: tidak ada perangkat Android yang tersambung." >&2
    problems="$(list_problem || true)"
    if [ -n "$problems" ]; then
        echo "       Terdeteksi tapi belum siap: $problems" >&2
        echo "       'unauthorized' → buka HP, centang 'Always allow' pada dialog USB debugging." >&2
    fi
    echo "" >&2
    echo "       USB     : aktifkan Opsi Pengembang → USB debugging, lalu colok kabel." >&2
    echo "       Nirkabel: HP & PC di Wi-Fi yang SAMA → Opsi Pengembang → Wireless debugging." >&2
    echo "                 Pertama kali WAJIB dipasangkan sekali (kode dari HP):" >&2
    echo "                     adb pair <ip>:<port-pairing>" >&2
    echo "                 Sesudah itu skrip ini menemukannya sendiri lewat mDNS." >&2
    exit 1
fi

# ---- Pilih perangkat: satu → langsung; banyak → tanya (biar tak salah HP) --------------------
if [ "${#DEVICES[@]}" -eq 1 ]; then
    SERIAL="${DEVICES[0]}"
else
    echo ""
    echo "Ada ${#DEVICES[@]} perangkat tersambung:"
    i=1
    for d in "${DEVICES[@]}"; do
        echo "   $i) $(device_label "$d")"
        i=$((i + 1))
    done
    # Memasang ke HP yang salah = mengganggu perangkat depot yang sedang dipakai bekerja, jadi
    # jangan pernah menebak. Tanpa terminal interaktif (CI/dobel-klik) → berhenti dengan aman.
    if [ ! -t 0 ]; then
        echo "" >&2
        echo "ERROR: lebih dari satu perangkat & tak ada input interaktif — tidak menebak." >&2
        echo "       Cabut yang lain, atau jalankan dari terminal supaya bisa memilih." >&2
        exit 1
    fi
    printf "Pasang ke nomor berapa? [1-%d] (Enter = 1): " "${#DEVICES[@]}"
    read -r pick </dev/tty || pick=""
    [ -z "$pick" ] && pick=1
    if ! [[ "$pick" =~ ^[0-9]+$ ]] || [ "$pick" -lt 1 ] || [ "$pick" -gt "${#DEVICES[@]}" ]; then
        echo "ERROR: pilihan '$pick' tidak valid." >&2
        exit 1
    fi
    SERIAL="${DEVICES[$((pick - 1))]}"
fi

TARGET_LABEL="$(device_label "$SERIAL")"
echo "==> Perangkat tujuan: $TARGET_LABEL"

# ---- Versi yang SEDANG terpasang (biar terlihat apa yang berubah) ----------------------------
installed_line="$("$ADB" -s "$SERIAL" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | awk -F= '/versionName=/{print $2; exit}')"
[ -n "$installed_line" ] && echo "    Terpasang sekarang: $installed_line" || echo "    Terpasang sekarang: (belum ada / instalasi baru)"

# ---- Install (-r = pertahankan data; TIDAK PERNAH uninstall) ---------------------------------
echo "==> Memasang ke $TARGET_LABEL…"
set +e
OUT="$("$ADB" -s "$SERIAL" install -r "$APK" 2>&1)"
RC=$?
set -e
echo "$OUT" | sed 's/^/    /'

if [ $RC -ne 0 ] || echo "$OUT" | grep -qi "failure\|error:"; then
    echo "" >&2
    if echo "$OUT" | grep -qi "INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match\|INCONSISTENT_CERTIFICATES"; then
        if [ "$MODE" = "debug" ]; then
            echo "ERROR: TANDA TANGAN BEDA — HP ini sedang memasang versi RELEASE (key repo d5ff87)," >&2
            echo "       sedangkan APK debug pakai key debug bawaan Android — keduanya tak kompatibel." >&2
        else
            echo "ERROR: TANDA TANGAN BEDA — APK ini di-sign key repo, HP itu memasang versi ber-key lain" >&2
            echo "       (HP depot yang di-sign pabrik: A325F/A750GN, cert 6ce910; atau HP itu sedang" >&2
            echo "       memasang varian DEBUG)." >&2
        fi
        echo "" >&2
        echo "       JANGAN uninstall untuk memaksanya: SELURUH data transaksi di HP itu ikut terhapus." >&2
        echo "       Pakai prosedur reinstall tanpa-kehilangan-data (backup DB → uninstall → install →" >&2
        echo "       restore) yang sudah didokumentasikan untuk kasus A750GN, atau kirim update lewat" >&2
        echo "       dashboard: scripts/publish-apk.sh" >&2
    elif echo "$OUT" | grep -qi "INSTALL_FAILED_VERSION_DOWNGRADE"; then
        echo "ERROR: versi di HP LEBIH BARU dari yang baru dibangun (versionCode $VCODE)." >&2
        echo "       Naikkan versionCode di $GRADLE_FILE, atau pasang paksa (hanya untuk HP uji):" >&2
        echo "           $ADB -s $SERIAL install -r -d \"$APK\"" >&2
    elif echo "$OUT" | grep -qi "INSTALL_FAILED_INSUFFICIENT_STORAGE"; then
        echo "ERROR: penyimpanan HP penuh — kosongkan ruang lalu ulangi." >&2
    elif echo "$OUT" | grep -qi "INSTALL_FAILED_USER_RESTRICTED\|denied"; then
        echo "ERROR: HP menolak pemasangan. Di HP Xiaomi/Samsung: aktifkan 'Install via USB'" >&2
        echo "       (Opsi Pengembang) dan setujui dialog yang muncul, lalu ulangi." >&2
    else
        echo "ERROR: pemasangan gagal (lihat keluaran adb di atas)." >&2
    fi
    exit 1
fi

# ---- Verifikasi apa yang BENAR-BENAR terpasang ------------------------------------------------
NOW_NAME="$("$ADB" -s "$SERIAL" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | awk -F= '/versionName=/{print $2; exit}')"
NOW_CODE="$("$ADB" -s "$SERIAL" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | awk -F= '/versionCode=/{print $2; exit}' | awk '{print $1}')"

# Ingat alamat nirkabel yang berhasil supaya run berikutnya menyambung sendiri tanpa USB.
if [[ "$SERIAL" =~ ^[0-9.]+:[0-9]+$ ]]; then
    printf '%s' "$SERIAL" > "$STATE"
fi

echo ""
echo "✅ Terpasang di $TARGET_LABEL"
echo "   Varian  : $MODE"
echo "   Versi   : ${NOW_NAME:-?} (${NOW_CODE:-?})"
echo "   Paket   : $PKG"
echo "   Data    : utuh (install -r, tanpa uninstall)"
if [[ "$SERIAL" =~ ^[0-9.]+:[0-9]+$ ]]; then
    echo "   Transport: nirkabel ($SERIAL) — diingat untuk run berikutnya"
else
    echo "   Transport: USB"
fi
echo ""
echo "ℹ️  Ini hanya memasang ke HP ini. Untuk mendorong update ke SELURUH armada:"
echo "    scripts/publish-apk.sh"
