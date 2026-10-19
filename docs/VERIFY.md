# Verifying a Kwallet / Hwallet download

Kwallet holds your keys, so check what you install. Every build is made by GitHub Actions from this repository
(`.github/workflows/build.yml`), and the run log shows exactly which commit it was built from.

## 1. Check the file you downloaded

Each build ships `SHA256SUMS.txt` next to the APKs and HTML files.

```sh
sha256sum -c SHA256SUMS.txt          # Linux
shasum -a 256 Kwallet-v*.apk         # macOS: compare with the line in SHA256SUMS.txt
```

On Windows: `certutil -hashfile Kwallet-v3.98.38.apk SHA256`.

## 2. Check who signed the APK

Kwallet and Hwallet are signed with the same permanent key (the build fails if they are not). Compare the
certificate fingerprint with the one printed in the "Verify signature" step of the GitHub Actions run, and with
earlier versions you installed: it must never change.

```sh
apksigner verify --print-certs Kwallet-v3.98.38.apk | grep SHA-256
apksigner verify --print-certs Hwallet-v1.8.40.apk  | grep SHA-256
```

`apksigner` ships with the Android SDK build-tools. Android itself also refuses an update signed with another key.

## 3. Build it yourself

```sh
git clone https://github.com/0xkabeno/Kwallet && cd Kwallet
git checkout <the commit from the Actions run>
python3 ci/js_check.py                # every inline script must parse
gradle --no-daemon assembleRelease    # JDK 17, Gradle 8.9, Android SDK
```

Your build is signed with your own debug key, so compare contents rather than the whole file, for example with
`diffoscope` or by unzipping both APKs and diffing everything except `META-INF/` (signatures). The pages are the same
file you can diff directly: `web/index.html` is Kwallet, `hwallet/web/index.html` is Hwallet. Hwallet's native Monero
library (monero_c) is downloaded during the build and checked against the SHA-256 sums pinned in `build.yml`.

Byte-for-byte reproducible APKs are a goal, not yet a guarantee: timestamps and build-tool versions can still differ.

## 4. Offline by design

Kwallet's manifest has no `INTERNET` permission. Check it with:

```sh
aapt dump permissions Kwallet-v3.98.38.apk
```
