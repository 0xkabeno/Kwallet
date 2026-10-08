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

## Reporting a vulnerability

Please open a private security advisory on this repository
(**Security → Report a vulnerability**) rather than a public issue.
