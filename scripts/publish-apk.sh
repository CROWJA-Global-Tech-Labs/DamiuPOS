#!/usr/bin/env bash
# Build a signed release APK and publish it to the fleet (dashboard.airfrez.com) — no args.
#
# Bumps versionCode/versionName in app/build.gradle FIRST, then mirrors
# AdminController::publishVersion exactly (server-side, via SSH + tinker) instead of using the
# browser upload form: uploads the APK bytes to storage/app/public/apk/, then inserts the
# AppVersion row (mandatory=true, target_devices=null → ALL live devices get the forced update
# popup on next poll). Verifies the published file's SHA-256 matches the local build byte-for-byte
# before declaring success.
#
# WHY THE VERSION BUMP MATTERS: the phone decides "update needed" by comparing APK SHA-256, so an
# update would fire even without it — but the DASHBOARD groups devices by the `app_version_code`
# each phone reports from its APK manifest (DeviceController::index / Kontrol Versi). Shipping
# every build as the same versionCode makes all releases indistinguishable there, and disabling
# "a version" would hit every build ever shipped. Android also refuses to install an APK whose
# versionCode is LOWER than the installed one.
#
# Usage: scripts/publish-apk.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Gradle 8.13 (this project's wrapper) can't run its Groovy build-script compiler on JDK 24+
# ("Unsupported class file major version 69" on JDK 25) — pin to a known-compatible JDK if the
# system JAVA_HOME/PATH java is too new, instead of failing the whole publish. Untracked (script-
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

SSH_PORT=65002
SSH_HOST="u387533081@62.72.50.17"
REMOTE_LARAVEL="domains/damiupos.hantechno.my.id/laravel"
PUBLIC_BASE="https://dashboard.airfrez.com"
APK_LOCAL="app/build/outputs/apk/release/app-release.apk"
GRADLE_FILE="app/build.gradle"

# ---- Bump versionCode (+1) & versionName (last numeric segment +1) --------------------------
read_version_code() { sed -n 's/^[[:space:]]*versionCode[[:space:]]\+\([0-9]\+\).*/\1/p' "$GRADLE_FILE" | head -1; }
read_version_name() { sed -n 's/^[[:space:]]*versionName[[:space:]]\+"\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -1; }

OLD_CODE="$(read_version_code)"
OLD_NAME="$(read_version_name)"
if [ -z "$OLD_CODE" ] || [ -z "$OLD_NAME" ]; then
    echo "ERROR: tidak bisa membaca versionCode/versionName dari $GRADLE_FILE — dibatalkan." >&2
    exit 1
fi

NEW_CODE=$((OLD_CODE + 1))
# Naikkan segmen angka TERAKHIR pada versionName ("1.4.12" → "1.4.13"). Format non-standar yang
# tidak diakhiri angka dibiarkan apa adanya (hanya versionCode yang naik) agar tak merusak label.
if [[ "$OLD_NAME" =~ ^(.*[^0-9])?([0-9]+)$ ]]; then
    NEW_NAME="${BASH_REMATCH[1]}$(( BASH_REMATCH[2] + 1 ))"
else
    NEW_NAME="$OLD_NAME"
    echo "!! versionName \"$OLD_NAME\" tidak diakhiri angka — hanya versionCode yang dinaikkan." >&2
fi

# Rollback otomatis versi bila build/terbit GAGAL, supaya build.gradle tidak meninggalkan versi
# "phantom" (dinaikkan tapi tak pernah terbit → run berikutnya naik lagi, muncul lubang di riwayat).
# Dipasang lewat trap EXIT: apa pun penyebab keluar sebelum PUBLISH_OK=1 (build gagal, upload gagal,
# SHA beda, URL tak reachable) mengembalikan versi ke nilai lama. Hanya terbit sukses yang menahannya.
VERSION_BUMPED=0
PUBLISH_OK=0
restore_version_on_fail() {
    local status=$?
    if [ "$VERSION_BUMPED" = "1" ] && [ "$PUBLISH_OK" != "1" ]; then
        sed -i "s/^\([[:space:]]*versionCode[[:space:]]\+\)$NEW_CODE\b/\1$OLD_CODE/" "$GRADLE_FILE"
        sed -i "s/^\([[:space:]]*versionName[[:space:]]\+\)\"$NEW_NAME\"/\1\"$OLD_NAME\"/" "$GRADLE_FILE"
        echo "" >&2
        echo "↩︎  Gagal sebelum terbit — versi dikembalikan ke $OLD_NAME ($OLD_CODE). Perbaiki lalu jalankan lagi;" >&2
        echo "    versi akan naik SEKALI ke $NEW_NAME ($NEW_CODE) saat build+terbit benar-benar sukses." >&2
    fi
    return $status
}
trap restore_version_on_fail EXIT

echo "==> Menaikkan versi: $OLD_NAME ($OLD_CODE) → $NEW_NAME ($NEW_CODE)"
sed -i "s/^\([[:space:]]*versionCode[[:space:]]\+\)$OLD_CODE\b/\1$NEW_CODE/" "$GRADLE_FILE"
sed -i "s/^\([[:space:]]*versionName[[:space:]]\+\)\"$OLD_NAME\"/\1\"$NEW_NAME\"/" "$GRADLE_FILE"
VERSION_BUMPED=1

# Verifikasi tulisan benar-benar terjadi — sed yang tak cocok pola TIDAK error, jadi tanpa cek ini
# skrip bisa diam-diam mem-build & menerbitkan versi LAMA (persis bug yang diperbaiki di sini).
GOT_CODE="$(read_version_code)"
GOT_NAME="$(read_version_name)"
if [ "$GOT_CODE" != "$NEW_CODE" ] || [ "$GOT_NAME" != "$NEW_NAME" ]; then
    echo "ERROR: gagal menulis versi baru ke $GRADLE_FILE (terbaca: $GOT_NAME / $GOT_CODE)." >&2
    echo "       Periksa format baris versionCode/versionName; tidak ada yang diterbitkan." >&2
    exit 1
fi

echo "==> Building signed release APK…"
if ! ./gradlew assembleRelease -q; then
    echo "ERROR: build gagal. Versi dikembalikan otomatis ke $OLD_NAME ($OLD_CODE) (lihat di bawah)." >&2
    exit 1
fi

if [ ! -f "$APK_LOCAL" ]; then
    echo "ERROR: $APK_LOCAL not found after build." >&2
    exit 1
fi

LOCAL_SHA="$(sha256sum "$APK_LOCAL" | cut -d' ' -f1)"
LOCAL_SIZE="$(stat -c%s "$APK_LOCAL" 2>/dev/null || stat -f%z "$APK_LOCAL")"
TS="$(date +%Y%m%d-%H%M%S)"
REMOTE_NAME="app-${TS}.apk"
REMOTE_PATH="${REMOTE_LARAVEL}/storage/app/public/apk/${REMOTE_NAME}"

echo "==> Built: $APK_LOCAL"
echo "    size=${LOCAL_SIZE} bytes  sha256=${LOCAL_SHA}"

echo "==> Uploading to server (${REMOTE_NAME})…"
scp -P "$SSH_PORT" "$APK_LOCAL" "${SSH_HOST}:${REMOTE_PATH}"

echo "==> Publishing AppVersion row (mandatory, all devices)…"
ssh -p "$SSH_PORT" "$SSH_HOST" "cd ${REMOTE_LARAVEL} && /opt/alt/php82/usr/bin/php artisan tinker --execute='
\$path = \"apk/${REMOTE_NAME}\";
\$sha = hash_file(\"sha256\", storage_path(\"app/public/\".\$path));
\$apkUrl = url(\\Illuminate\\Support\\Facades\\Storage::disk(\"public\")->url(\$path)).\"?v=\".time();
\$label = \"${NEW_NAME} (${NEW_CODE}) · \".now()->format(\"d M Y H:i\");
\\App\\Models\\AppVersion::query()->update([\"is_current\" => false]);
\$v = \\App\\Models\\AppVersion::create([
    \"version_code\" => max(time(), (int) (\\App\\Models\\AppVersion::max(\"version_code\") ?? 0) + 1),
    \"version_name\" => \$label,
    \"apk_url\" => \$apkUrl,
    \"apk_sha256\" => \$sha,
    \"mandatory\" => true,
    \"is_current\" => true,
    \"published_at\" => now(),
    \"published_by\" => 1,
    \"target_devices\" => null,
]);
echo \"PUBLISHED id=\".\$v->id.\" name=\".\$v->version_name.\" sha=\".\$sha.\"\n\";
echo \"URL=\".\$v->apk_url.\"\n\";
' 2>&1 | grep -v deprecated"

PUBLIC_URL="${PUBLIC_BASE}/storage/apk/${REMOTE_NAME}"
echo "==> Verifying published file (SHA-256 + reachability)…"
REMOTE_SHA="$(ssh -p "$SSH_PORT" "$SSH_HOST" "sha256sum ${REMOTE_PATH} | cut -d' ' -f1")"

if [ "$REMOTE_SHA" != "$LOCAL_SHA" ]; then
    echo "ERROR: SHA-256 mismatch! local=${LOCAL_SHA} remote=${REMOTE_SHA}" >&2
    exit 1
fi

HTTP_STATUS="$(curl -s -o /dev/null -w '%{http_code}' "$PUBLIC_URL")"
if [ "$HTTP_STATUS" != "200" ]; then
    echo "ERROR: public URL not reachable (HTTP ${HTTP_STATUS}): ${PUBLIC_URL}" >&2
    exit 1
fi

# Sukses penuh: tahan kenaikan versi (trap EXIT tidak akan mengembalikannya).
PUBLISH_OK=1

echo ""
echo "✅ Published and verified — SHA-256 match, HTTP 200."
echo "   Versi   : ${NEW_NAME} (${NEW_CODE})   [sebelumnya ${OLD_NAME} (${OLD_CODE})]"
echo "   URL     : ${PUBLIC_URL}"
echo "   Every live device gets the mandatory-update popup on its next poll."
echo ""
echo "ℹ️  ${GRADLE_FILE} sudah diubah ke versi baru — commit perubahan itu supaya"
echo "    riwayat versi di repo cocok dengan yang beredar di perangkat."
