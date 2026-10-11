# Changelog

## Kwallet v3.96 + Hwallet v1.6 (Push 2)

Hwallet becomes a full dApp wallet (WalletConnect, a dApp browser and Swap) while every key and every signature stays in Kwallet.

### Kwallet 3.96 — the offline vault
- **dApp sign sheet** (#60, #80): `personal_sign` with a sign-in-with-Ethereum domain check, `eth_signTypedData_v4` with Permit / Permit2 decoded, `eth_sendTransaction` with ABI decode, Solana `signMessage` / `signTransaction` (v0 transactions and address lookup tables, instructions decoded), Tron `signMessage` and smart-contract calls. Solid, low-noise layout: Kwallet chip top-left and chain chip top-right on every request, amount first, one risk line, everything else under Details.
- **Exact approvals**: an unlimited ERC-20 `approve` / `increaseAllowance` or Permit2 `approve` can be re-encoded to the exact amount (Exact / Unlimited toggle with a warning). Signed permits (typed data) can't be changed, so Kwallet warns instead.
- **Custom network fee** (#82): Slow / Normal / Fast / Custom on the send page and the dApp sheet: EVM max fee, priority tip and gas limit (or gas price on legacy chains), Solana priority fee and compute units (only when the transaction can safely be changed), Bitcoin sat/vB, Tron fee limit. Hwallet sends the chain's live minimum and Kwallet warns when a custom value is below it. This control only changes the network fee.
- **Settings** is a full page with a back arrow and Kwallet branding (#70); **Pair Hwallet** is a short full page with inline errors that say how to fix them (#79).
- NEAR account derivation (ed25519, `m/44'/397'/0'`, compatible with Meteor) for NEAR Intents fee withdrawals.
- Official Robinhood Chain, USDG and Base logos bundled offline (#73).

### Hwallet 1.6 — the hot wallet
- **WalletConnect v2** (#57, #78): Reown WalletKit bundled offline, for EVM networks, Solana and Tron. Pair from the scanner, a pasted `wc:` link (#77) or a deep link (`wc:`, `hwallet://wc?uri=`, `https://hwallet.app/wc?uri=`). Proposal sheet with the Verify API result, sessions list with Disconnect in Settings. Messages, typed data, transactions, approvals and chain switches all go to Kwallet.
- **Browser tab** (#59, #83): bottom bar Wallet / Activity / Browser / Settings. Unlimited tabs, each saved to the phone (address, title, icon, scroll, back/forward history and a page snapshot) and restored from local storage first. Injected EIP-1193 + EIP-6963 Ethereum provider, Solana Wallet Standard and a TronLink-style provider; per-site connections you can revoke; storage use and Clear all in Browser settings. Respects the mobile-data cap for page resources.
- **Swap** (#58, #81, #82): best quote across NEAR Intents (1Click), Jupiter, LI.FI and SunSwap in a Gem-style screen; Details with the provider list, rate, minimum received, slippage (Auto or custom, with too-low / too-high warnings), service fee, provider fee and an editable network fee. Approvals are for the exact amount.
- **Service fee** (#74): 0.3% to Kwallet on swaps, always shown as its own line. On NEAR Intents routes the user pays 0.6% in total (Kwallet nets 0.3%, NEAR Intents' 1Click takes the other 0.3%). On Jupiter the fee is only taken when the fee token account already exists for the pair; otherwise it is shown as none. On SunSwap it is a separate 0.3% transfer after the swap. Network fees go to the blockchain and are never part of the service fee.
- **Fees accounts** (#75): shown only when a paired wallet owns a fee address; collected fees per route and one "Withdraw all fees" flow, every step signed in Kwallet.
- **EVM history** (#71): Etherscan V2 and Alchemy (with your own or the built-in key), then keyless Blockscout and RPC log scans; method names, approvals and swaps decoded; internal transfers included.
- **Pull to refresh** (#72) on Home, networks, coin pages and Activity, with a light tick at the threshold.
- **Notification coins** (#76): pick the four coins in the live notification, including tokens imported by contract on any chain.
- **Scanner** (#77): the broken flash-off icon is fixed; Paste on the scan screen.
- **Pairing page** (#79): short, with a clear "Kwallet isn't installed" state and an Install button.
- **API keys** in Settings (Alchemy, Etherscan, Helius, TronGrid, LI.FI, Jupiter). Built-in keys are sealed into the release APK at build time and never shown to the web layer; Hwallet works with no keys at all. See SECURITY.md.

### Known limits
- The dApp browser needs Android System WebView 86 or newer (web message listener + document-start script). Older WebView loads pages without a wallet.
- The Tron browser provider is minimal: `tron_requestAccounts`, `tronWeb.trx.sign` and `signMessageV2` only (no `tronWeb.contract()` or `sendRawTransaction`).
- NEAR Intents routes cost the user 0.6% (0.3% to Kwallet + 0.3% to 1Click).
- Jupiter's 0.3% is only taken where the fee token account exists; Kwallet never creates one at the user's expense.
- XMR send is still not included (XMR stays watch-only).
- Not yet tested end-to-end against live dApps, a live WalletConnect relay or live swap/withdraw APIs on a device; the release is verified by headless tests with mocked network calls and by CI.
- WalletConnect does not offer `eth_signTransaction` or `solana_signAllTransactions` yet; the browser signs Solana batches one sheet at a time.
- The browser's main page download is not throttled by the mobile-data cap (a WebView limit); page resources are.
