#!/bin/bash
# Runs inside reactivecircus/android-emulator-runner (device.yml). Installs each APK pair and drives the flows.
# Never fails on app bugs (those are findings in device-out/*/results.json); fails only if a pair cannot be installed.
set -u
OUT=${OUT:-device-out}
mkdir -p "$OUT"
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'
adb shell input keyevent 82 || true
pass() {  # label kwallet.apk hwallet.apk
  local L=$1 KA=$2 HA=$3
  echo "::group::device check: $L"
  adb get-state >/dev/null 2>&1 || { echo "::error::emulator is gone before the $L pass"; return 1; }
  timeout 60 adb uninstall app.hwallet >/dev/null 2>&1; timeout 60 adb uninstall app.kwallet >/dev/null 2>&1
  timeout 180 adb install -r -g "$KA" || { echo "::error::install $KA failed"; return 1; }
  timeout 180 adb install -r -g "$HA" || { echo "::error::install $HA failed"; return 1; }
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
