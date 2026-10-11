#!/bin/bash
# Runs inside reactivecircus/android-emulator-runner (device.yml). Installs each APK pair and drives the flows.
# Never fails on app bugs (those are findings in device-out/*/results.json); fails only if a pair cannot be installed.
set -u
OUT=${OUT:-device-out}
mkdir -p "$OUT"
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'
adb shell input keyevent 82 || true
# P0b diagnostics: stream every logcat buffer (main, system, crash, events, kernel) to a file from now on, so the last
# guest lines before an emulator death are kept even when adb cannot reach the device afterwards.
adb logcat -G 16M >/dev/null 2>&1 || true
adb logcat -b all -v threadtime > "$OUT/logcat_stream_all.txt" 2>&1 &
LCPID=$!
(while sleep 2; do echo "$(date +%T) $(timeout 5 adb get-state 2>&1) qemu=$(pgrep -f qemu-system | head -1)" ; done > "$OUT/health.txt" 2>&1 < /dev/null) &
HPID=$!
# Sound: on API 34/35 the emulator process died ~0.3 s after Hwallet's opening chime opened its Web Audio output
# (last guest lines: AAudioStream_requestStart -> setState 4, then nothing; run 38173266061). Both apps play Web Audio
# only when the ringer is "normal" (HarkNative.ringerMode), so RINGER=silent (default) keeps audio closed, like a phone on
# silent. RINGER=normal reproduces the crash.
RINGER=${RINGER:-silent}; export RINGER
if [ "$RINGER" = silent ]; then
  adb shell cmd audio set-ringer-mode SILENT 2>&1 || adb shell cmd audio set-ringer-mode silent 2>&1 || true
fi
echo "ringer: $(adb shell dumpsys audio | grep -m1 -i 'ringer mode' | tr -s ' ')"
# Vibration: VIB=block silences the haptic for both apps (appops), VIB=allow leaves it on (diagnostic control).
VIB=${VIB:-block}; export VIB
pass() {  # label kwallet.apk hwallet.apk
  local L=$1 KA=$2 HA=$3
  echo "::group::device check: $L"
  adb get-state >/dev/null 2>&1 || { echo "::error::emulator is gone before the $L pass"; return 1; }
  timeout 60 adb uninstall app.hwallet >/dev/null 2>&1; timeout 60 adb uninstall app.kwallet >/dev/null 2>&1
  timeout 180 adb install -r -g "$KA" || { echo "::error::install $KA failed"; return 1; }
  timeout 180 adb install -r -g "$HA" || { echo "::error::install $HA failed"; return 1; }
  if [ "$VIB" = block ]; then for P in app.hwallet app.kwallet; do adb shell appops set $P VIBRATE ignore; echo "appops $P: $(adb shell appops get $P VIBRATE)"; done; fi
  timeout 900 python3 ci/device_check.py "$OUT/$L" "$L" 2>&1 | tee "$OUT/$L.log"
  timeout 30 adb exec-out screencap -p > "$OUT/$L/zz_final.png" || true
  echo "::endgroup::"
  grep -h '^SUMMARY' "$OUT/$L.log" >> "$OUT/summary.txt"
}
RC=0
pass debug app/build/outputs/apk/debug/app-debug.apk hwallet/app/build/outputs/apk/debug/hwallet-debug.apk || RC=1
if [ -f app/build/outputs/apk/release/app-release.apk ]; then
  pass release app/build/outputs/apk/release/app-release.apk hwallet/app/build/outputs/apk/release/hwallet-release.apk || RC=1
fi
timeout 60 adb logcat -d -v threadtime > "$OUT/logcat_full_tail.txt" || true
echo "== last 40 lines of the logcat stream"; tail -40 "$OUT/logcat_stream_all.txt" | cut -c1-220
echo "== health tail"; tail -8 "$OUT/health.txt"
kill $LCPID $HPID 2>/dev/null || true
cat "$OUT/summary.txt" 2>/dev/null
python3 - "$OUT" <<'P' >> "${GITHUB_STEP_SUMMARY:-/dev/null}" || true
import json, sys, glob, os
print('## Emulator device check')
for f in sorted(glob.glob(os.path.join(sys.argv[1], '*', 'results.json'))):
    R = json.load(open(f)); print('### %s  %s' % (R.get('label'), R.get('device', {}).get('h_version')))
    print('| step | ok | note |\n|---|---|---|')
    for s in R['steps']:
        note = s.get('why') or (json.dumps(s.get('google'))[:120] if 'google' in s else '') or (s.get('text') or '')[:100]
        print('| %s | %s | %s |' % (s['step'], 'yes' if s['ok'] else '**no**', str(note).replace('|', '/')))
    if R.get('harness_error'): print('\nharness error: `%s`' % R['harness_error'])
P
exit $RC
