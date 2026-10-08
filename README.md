<div align="center">

<img src=".github/assets/icon-512.png" width="128" alt="Kwallet icon">

# Kwallet

**Offline multi-chain HD wallet generator — Never lose your keys.**

[![Build APK](https://github.com/0xkabeno/Kwallet/actions/workflows/build.yml/badge.svg)](https://github.com/0xkabeno/Kwallet/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/0xkabeno/Kwallet?color=DDF869&labelColor=223348)](https://github.com/0xkabeno/Kwallet/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-223348)](LICENSE)
![Offline](https://img.shields.io/badge/network-none-DDF869?labelColor=223348)

[**Download APK**](https://github.com/0xkabeno/Kwallet/releases/latest) · [Single-file HTML](web/index.html) · [Security](SECURITY.md)

</div>

---

Kwallet creates and restores wallets for eight chains from one recovery phrase, entirely on your device.
The Android app has **no internet permission**; the HTML version is one self-contained file that works with the network unplugged.

## Chains

| Chain | Paths | Compatible with |
|---|---|---|
| **ETH / EVM** | `m/44'/60'/0'/0/i`, Ledger Live, Ledger legacy | MetaMask, Trust, Ledger |
| **BTC** | SegWit (84), Taproot (86), Nested SegWit (49), Legacy (44) | Electrum, Sparrow, BlueWallet |
| **SOL** | `m/44'/501'/i'/0'` and variants | Phantom, Solflare, Ledger |
| **TRX** | `m/44'/195'/0'/0/i`, Ledger Live | TronLink, Ledger |
| **SUI** | `m/44'/784'/i'/0'/0'` | Sui Wallet, Phantom, Slush |
| **XRP** | `m/44'/144'/0'/0/i` | Xaman, Trust |
| **ADA** | CIP-1852 (Icarus) | Yoroi, Eternl, Lace |
| **XMR** | Monero 25-word & Polyseed | Monero GUI, Cake, Feather |

## Features

- **Generate or import** 12–24 word BIP39 phrases, with optional passphrase (25th word)
- **Find** — paste any address or part of one and Kwallet scans every common wallet path to locate it
- **Key** — paste a single private key to see its ETH, TRX, BTC and SOL addresses
- **Backup & restore** — full-state backup file, optionally AES-256-GCM encrypted (PBKDF2 600k)
- **Export** — PDF, CSV and JSON, saved to `Download/Kwallet` on Android
- **App lock** — 6-digit PIN, auto-lock timer, two-step *Erase everything*
- **Built-in self-test** — known-answer vectors for every chain in *Log → Verify*
- **Clipboard auto-clear**, haptics, light and dark themes, smooth 120 Hz scrolling

## Install

**Android** — download `Kwallet-vX.apk` from [Releases](https://github.com/0xkabeno/Kwallet/releases/latest) and open it (allow *Install unknown apps* once).

**Any computer** — download `Kwallet-vX.html` and open it in a browser. For the strongest setup, do this on a machine that is offline.

Verify every download:

```bash
sha256sum -c SHA256SUMS.txt
```

## Build it yourself

```bash
git clone https://github.com/0xkabeno/Kwallet.git && cd Kwallet
gradle assembleRelease        # needs JDK 17 + Android SDK, Gradle 8.9
```

The web app lives in [`web/index.html`](web/index.html) and is packed into the APK as-is.
Every push builds an APK in **Actions**; every `v*` tag publishes a release.

To sign releases with your own key, add these repository secrets:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Support Kwallet

Kwallet is free and open source. If it saved you time, a tip keeps it going.

| Chain | Address |
|---|---|
| **ETH / EVM** | `0x00047b2663aB0cE43B6941E30247379Ac7980fc1` |
| **SOL** | `Fd6GYpjp91xkw1nR5teZuugfdUy7Kg6oKzZ4ENQTA6JM` |
| **BTC** | `bc1q9xv9snra9p3n6sqj5d6l0p6z22aesc597f3jx8` |
| **XMR** | `48n3nYPQgXTZYdrmdxiLkVYS41y9eXFf4RsSm9oXDvbcggNVP6Zzj5NSrNnWjQZcTtaaRJxfrQxVqBG5TNkSU4WKNmeKET2` |

The same addresses are in the app under **Settings → Support Kwallet**, one tap to copy.

## Disclaimer

Kwallet is provided as-is, without warranty. You alone hold your keys: keep your recovery phrase offline and private, and test with small amounts first.

<div align="center"><sub>Made with care · MIT License</sub></div>
