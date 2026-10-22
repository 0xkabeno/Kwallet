#!/usr/bin/env bash
# v3.99.5 (S1): open both pages in headless Chrome and fail on an uncaught error while they start
# (the 1.9.0 white screen was an init-order ReferenceError). Network failures and promise rejections are ignored:
# CI has no paired wallet and no Android bridge.
set -u
CH=$(command -v google-chrome || command -v chromium || command -v chromium-browser)
[ -n "$CH" ] || { echo "no Chrome on this runner, skipped"; exit 0; }
bad=0
for f in web/index.html hwallet/web/index.html; do
  log=$(mktemp)
  timeout 90 "$CH" --headless=new --no-sandbox --disable-gpu --enable-logging=stderr --v=0 --virtual-time-budget=6000 \
    --user-data-dir="$(mktemp -d)" --dump-dom "file://$PWD/$f" > /dev/null 2> "$log"
  errs=$(grep -E 'CONSOLE.*Uncaught (SyntaxError|ReferenceError|TypeError|RangeError)' "$log" | grep -v 'in promise' || true)
  if [ -n "$errs" ]; then echo "::error file=$f::uncaught error at start"; echo "$errs"; bad=1; else echo "$f: started with no uncaught errors"; fi
  grep -c CONSOLE "$log" | sed "s/^/$f console lines: /"
done
exit $bad
