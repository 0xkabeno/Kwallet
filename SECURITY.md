# Security

Kwallet generates keys on your device and never sends them anywhere. The Android
app ships **without the INTERNET permission**, and the HTML build makes no network
requests.

## Good practice

- Use Kwallet on a device you trust. For large amounts, run the HTML file on an
  offline machine.
- Verify downloads against `SHA256SUMS.txt` from the release page.
- Write your recovery phrase on paper. Anyone who sees it controls the funds.
- Kwallet will **never** ask you to send your seed phrase or private keys to anyone.

## Hwallet's provider API keys

Hwallet (the online, watch-only companion) reads balances, history and swap quotes
from public infrastructure: Alchemy, Etherscan V2, Helius, TronGrid, LI.FI, Jupiter,
and Reown (WalletConnect). To keep it fast and within rate limits, the official
release APK carries the project owner's keys for these services. Be clear about what
that means:

- **The keys are not in the source code.** CI reads them from GitHub Actions secrets
  at build time and seals each one with AES-GCM under a random per-build key. That
  key is split into masked pieces spread across generated classes, and R8 shrinks and
  renames the code. Hwallet opens a key only in memory, natively.
- **The web page never sees a key.** It writes a placeholder; Hwallet adds the key to
  the outgoing request itself, and only for that provider's own hosts. The bridge can
  report whether a key is present, never its value.
- **This is obfuscation, not a vault.** Anyone determined enough can extract a key
  from the APK. The keys are read-only API keys with provider-side limits; they give
  no access to anyone's funds, and they can be rotated at any time.
- **No key, no problem.** A build without the secrets (for example your own build of
  this repository, or a pull request from a fork) still works: every provider falls
  back to keyless public endpoints. A key that answers 401, 403 or 429 is rested for a
  few minutes and Hwallet goes keyless in the meantime.
- **Your own keys win.** Settings → API keys lets you enter your own key per
  provider. It is stored in Hwallet's private app storage on your phone, used instead
  of the built-in key, and never shown again or sent anywhere except to that provider.
- Kwallet, the signer, has no internet permission and holds no API keys at all.

## Reporting a vulnerability

Please open a private security advisory on this repository
(**Security → Report a vulnerability**) rather than a public issue.
