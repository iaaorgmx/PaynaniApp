#!/usr/bin/env bash
# Prueba de humo de PaynaniApp en un emulador o teléfono conectado por adb.
#
# Instala el APK de debug, lo abre, simula un SMS entrante y una llamada perdida,
# y lee event_log de la base de la app (run-as: sólo funciona con el APK de debug).
#
# Uso:  tools/smoke.sh [ruta-al-apk]
#   ANDROID_SERIAL  dispositivo de adb (por omisión emulator-5554)
#   SMOKE_OUT       carpeta para la base copiada y la captura (por omisión /tmp/paynaniapp-smoke)
#
# La simulación de SMS y llamada (adb emu) sólo existe en el emulador. En un
# teléfono real el script instala y abre la app, y hay que mandarle un SMS de verdad.
#
# Sin emparejar con una pasarela de paynani (APP-2), nada sale del teléfono: el
# SMS y la llamada sólo quedan en el registro local.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${ANDROID_SERIAL:-emulator-5554}"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
OUT="${SMOKE_OUT:-/tmp/paynaniapp-smoke}"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
PKG=com.iaaorgmx.paynani
TAG="smoke$(date +%H%M%S)"
mkdir -p "$OUT"

[ -f "$APK" ] || { echo "No existe el APK: $APK (compila con ./gradlew assembleDebug)" >&2; exit 1; }
"$ADB" -s "$SERIAL" get-state >/dev/null || { echo "No hay dispositivo $SERIAL en adb" >&2; exit 1; }

android --no-metrics run --apks="$APK" --device="$SERIAL" </dev/null >/dev/null 2>&1
sleep 6
echo "actividad: $("$ADB" -s "$SERIAL" shell dumpsys activity activities | grep -m1 topResumedActivity | grep -oE "$PKG/[.A-Za-z0-9_]+" || echo '?')"

case "$SERIAL" in
  emulator-*)
    "$ADB" -s "$SERIAL" emu sms send 5550001111 "$TAG sms" >/dev/null
    sleep 4
    "$ADB" -s "$SERIAL" emu gsm call 5550002222 >/dev/null; sleep 4
    "$ADB" -s "$SERIAL" emu gsm cancel 5550002222 >/dev/null
    sleep 5 ;;
  *) echo "Teléfono real: manda un SMS a este teléfono y vuelve a correr el script para ver el registro." ;;
esac

# Room (#105) escribe en modo WAL: las filas recientes están en -wal hasta el
# siguiente checkpoint, así que se copian los tres archivos.
rm -f "$OUT/db.sqlite" "$OUT/db.sqlite-wal" "$OUT/db.sqlite-shm"
for suffix in "" -wal -shm; do
  "$ADB" -s "$SERIAL" exec-out run-as "$PKG" sh -c "cat databases/DBPaynaniAppNew1$suffix 2>/dev/null" > "$OUT/db.sqlite$suffix"
  [ -s "$OUT/db.sqlite$suffix" ] || rm -f "$OUT/db.sqlite$suffix"
done
python3 - "$OUT/db.sqlite" "$TAG" <<'PY'
import sqlite3, sys
c = sqlite3.connect(sys.argv[1])
rows = c.execute("SELECT evento, msg, phone, timestamp FROM event_log ORDER BY id DESC LIMIT 6").fetchall()
for r in rows:
    print("  event_log:", r)
sms = [r for r in rows if r[0] == "incoming_sms" and sys.argv[2] in (r[1] or "")]
print("SMS simulado registrado:", "SÍ" if sms else "NO")
PY
if "$ADB" -s "$SERIAL" shell dumpsys activity services "$PKG" | grep -q SMSService; then
  echo "SMSService: corriendo"
else
  echo "SMSService: NO corre"
fi
android --no-metrics screen capture -o "$OUT/screen.png" --device="$SERIAL" </dev/null >/dev/null 2>&1 && echo "captura: $OUT/screen.png"
