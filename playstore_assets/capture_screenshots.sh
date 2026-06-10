#!/usr/bin/env bash
# Capture Google Play Store screenshots from a connected Android device.
#
# Prasyarat:
#   - HP Android terkoneksi via USB atau wireless debugging
#   - adb tersedia (script ini pakai ADB di lokasi default Windows SDK, bisa di-override via env ADB)
#   - APK debug sudah ter-install: ./gradlew installDebug
#
# Penggunaan:
#   bash playstore_assets/capture_screenshots.sh
#
# Hasil: PNG di playstore_assets/screenshots/phone/
#
# Play Store phone screenshot requirement (2026):
#   - Min 2, max 8 screenshot
#   - Rasio 16:9 atau 9:16
#   - Sisi terpendek >= 320 px, terpanjang <= 3840 px
#   - Format PNG / JPEG 24-bit (tanpa alpha)

set -u

ADB="${ADB:-/c/Users/ardi/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
PKG="com.crowja.damiupos"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)/screenshots/phone"
mkdir -p "$OUT_DIR"

if ! "$ADB" get-state 1>/dev/null 2>&1; then
    echo "❌ Tidak ada device terkoneksi. Jalankan:"
    echo "   adb connect <ip>:<port>    # untuk wireless debugging"
    echo "   atau colok kabel USB."
    exit 1
fi

echo "✅ Device terdeteksi: $("$ADB" shell getprop ro.product.model | tr -d '\r')"
echo "📁 Output: $OUT_DIR"
echo

# Helper: launch an activity, wait, screencap via exec-out, save locally.
# Menggunakan exec-out screencap -p untuk hindari bug path translation MSYS
# (`/sdcard/...` → `C:/Program Files/Git/sdcard/...`) di Git Bash Windows.
shoot() {
    local label="$1"
    local activity="$2"
    local extras="${3:-}"
    local wait_s="${4:-2}"

    echo "📸 [$label] -> $activity"
    if [[ -n "$extras" ]]; then
        MSYS_NO_PATHCONV=1 "$ADB" shell am start -n "$PKG/$activity" $extras >/dev/null
    else
        MSYS_NO_PATHCONV=1 "$ADB" shell am start -n "$PKG/$activity" >/dev/null
    fi
    sleep "$wait_s"
    "$ADB" exec-out screencap -p > "$OUT_DIR/${label}.png"
}

# Kembali ke home dulu.
"$ADB" shell input keyevent KEYCODE_HOME >/dev/null
sleep 1

# Seed demo data + complete wizard (debug build saja; no-op di release).
echo "🌱 Seeding demo data..."
MSYS_NO_PATHCONV=1 "$ADB" shell am start -n "$PKG/.DebugSeedActivity" >/dev/null 2>&1 && sleep 3 || true
"$ADB" shell am force-stop "$PKG" >/dev/null
sleep 1

# 1. Dashboard
shoot "01_dashboard"         ".MainActivity" "" 3

# 2. Jual galon (TransactionActivity dengan extra type=JUAL)
shoot "02_jual_galon"        ".TransactionActivity" "--es type JUAL" 3

# 3. Galon kembali
shoot "03_galon_kembali"     ".TransactionActivity" "--es type KEMBALI" 3

# 4. Daftar transaksi
shoot "04_daftar_transaksi"  ".TransactionListActivity" "" 3

# 5. Daftar pelanggan
shoot "05_pelanggan"         ".CustomerListActivity" "" 3

# 6. Stok galon
shoot "06_stok_galon"        ".GalonStockActivity" "" 3

# 7. Laporan & grafik
shoot "07_laporan"           ".ReportActivity" "" 3

# 8. Upgrade / subscription
shoot "08_upgrade"           ".UpgradeActivity" "" 3

# Kembali ke home
"$ADB" shell input keyevent KEYCODE_HOME >/dev/null

echo
echo "✅ Selesai. Review hasil di:"
echo "   $OUT_DIR"
echo
echo "Tips sebelum upload ke Play Console:"
echo "  - Pastikan data demo terlihat realistis (beberapa pelanggan, transaksi)."
echo "  - Re-run script kalau ada layar yang masih kosong."
echo "  - Flatten alpha: magick input.png -background white -alpha remove output.png"
