#!/usr/bin/env bash
# v3.99.49: Kwallet's NIP-06 Nostr key (FastCore.nostrKey, the key pk_pair hands to TextAuth) must match the NIP-06
# test vector. Runs the page's own code in headless Chrome: a copy of web/index.html with one extra script at the end.
set -u
CH=$(command -v google-chrome || command -v chromium || command -v chromium-browser)
[ -n "$CH" ] || { echo "::error::no Chrome on this runner, Nostr key check cannot run"; exit 1; }
D=$(mktemp -d); T="$D/nostr.html"
cp web/index.html "$T"
cat >> "$T" <<'JS'
<script>
(function () {
  function put(v) { var e = document.getElementById('kw-nostr-ci') || document.createElement('pre'); e.id = 'kw-nostr-ci'; e.textContent = v; (document.body || document.documentElement).appendChild(e); }
  function run() {
    try {
      var mn = 'leader monkey parrot ring guide accident before fence cannon height naive bean';
      var k = window.FastCore.nostrKey(getBip39SeedHex(mn, ''), 0);
      put('NOSTR:' + k.sk + ':' + k.pk + ':END');
    } catch (e) { put('NOSTR:ERR ' + (e && e.message) + ':END'); }
  }
  run(); setInterval(run, 500);
})();
</script>
JS
out=$(timeout 90 "$CH" --headless=new --no-sandbox --disable-gpu --virtual-time-budget=6000 \
  --user-data-dir="$(mktemp -d)" --dump-dom "file://$T" 2>/dev/null | grep -o 'NOSTR:[^<]*:END' | tail -1)
echo "got: $out"
want='NOSTR:7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a:17162c921dc4d2518f9a101db33695df1afb56ab82f5ff3e5da6eec3ca5cd917:END'
if [ "$out" = "$want" ]; then echo "ok   NIP-06 vector (account 0): privkey + x-only pubkey match"; exit 0; fi
echo "::error file=web/index.html::NIP-06 Nostr key does not match the test vector"; exit 1
