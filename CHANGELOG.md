# Changelog

## Kwallet 3.99.16 / Hwallet 1.9.16 (work in progress)
- Fix (critical): choosing or adding an identity on id.ai goes straight to Kwallet's passkey chooser. The extra "Restore identities" approval no longer appears in front of it. Names and dates are now read on request from Hwallet Settings > Internet Identity.
- Kwallet passkey sheet is the same whether you add or connect, and whether there is one identity or several. Smaller green dot. Each row shows the name, with one clean line under it: passkey number, identity ID and the exact creation time (II's created_at, or when this phone made it).
- Hwallet: new Settings > Internet Identity sheet listing every identity of this seed with its ID, passkey and exact creation time from id.ai.
- Hwallet: Sui tokens (USDC and others) take their icon from the coin's on-chain CoinMetadata, cached, with the letter only as a fallback.
- Hwallet: pull to refresh brings back the springy bounce on the Chains header, and the 3-dot menu now bounces with it.

## Kwallet 3.99.15 / Hwallet 1.9.15 (work in progress)
- Kwallet: Settings always opens, even if one row fails to fill.
- Kwallet + Hwallet: the selected choice is a solid green dot (no dark centre) and keeps beating even with system animations reduced.
- Internet Identity: the passkey chooser opens at once from identities Hwallet already knows and refreshes behind it; this phone's own passkeys are never hidden by a slow or partial II lookup, and an empty answer no longer erases a known identity.
- TON naming: the chain is TON everywhere (chain switches, Receive, explorers, errors); only the coin is Gram (GRAM), with the official Gram badge bundled in both apps. Kwallet's signing screens show amounts in GRAM.
- Hwallet: ckBTC, ckETH, ckUSDC and ckUSDT show the Bitcoin, Ethereum, USDC and USDT icons with the ICP badge, from icons already in the app (nothing fetched).
- Hwallet Receive: a chain added after the wallet was paired (NEAR, ICP, TON) is listed with "Pair again with Kwallet to add" instead of being missing.
- Hwallet QR air-gap (the other side of Kwallet 3.99.14's): "Pair offline Kwallet" (setup screen and Settings > Wallets & pairing) scans Kwallet's kw-pair QR; every signature for that wallet then shows a kw-req animated QR for Kwallet to scan, and Hwallet scans Kwallet's kw-res answer, which is checked against the waiting request's id. Pairing again from a Kwallet on the same phone switches the wallet back to the normal link. Same KwUR (BC-UR) engine as Kwallet, embedded offline.
- Not in this build: Network & VPN (app-only OpenVPN with kill switch) and secure DNS, plus the activity timeline. See docs/HANDOFF-3.99.15.txt.

## Kwallet v3.99.14 / Hwallet v1.9.14 (test build)

- Kwallet: TON (W5 and v4R2 wallets, TON and USDT sends with comments, TonConnect signing), Tonviewer and Tonscan explorers.
- Kwallet: readable EVM and Solana previews; approvals default to the exact amount; revoke shows as "Revoke … approval".
- Kwallet: seed backup-check prompts; QR air-gap side (settings rows, scanner, camera permission). Still no INTERNET permission, and CI fails if it ever appears.
- Kwallet: Internet Identity rows show plain "Identity N". Monero first in the chain row.
- Kwallet Settings opened to nothing (an air-gap helper was out of scope); fixed.
- Boot fix: the BC-UR (KwUR) bundle read Node's `process` at load and broke Kwallet's start; it now gets a local stub.
- Both apps: every selected single choice (passkeys, identities, networks, dialog options, theme and icon pickers) beats like the pairing dot. Off with reduce motion.
- Hwallet: Sui works again. Sui turned its public JSON-RPC off, so balances showed "—". Balances, coins, gas price, sending, status, history, dApp transactions and the background service now use Sui GraphQL.
- Hwallet: TON as a full chain, coin shown as Gram (GRAM): balances, jettons (USDT pinned), history, send with comment, broadcast, live status. Pair again in Kwallet to add it.
- Hwallet: TON Connect in the browser, as Hwallet and as a Tonkeeper-compatible wallet (connect with ton_proof, sendTransaction, signData). Kwallet signs; Hwallet broadcasts. Pairing now also carries the TON public key and wallet StateInit.
- Hwallet: swipe a Home row for Send (green), Swap (red), Pin (blue), Hide, with haptics.
- Hwallet: token approvals work without an Etherscan key (checks every held token against the spenders you used and the big routers), and "Revoke all".
- Hwallet: address book memo/tag, tap to edit, names in history, and "Save to address book?" after sending to a new address.
- Hwallet: price alerts (above/below, checked by the background service every minute) and money-out alerts next to incoming ones.
- Hwallet: Internet Identity scan in batches of 5 with up to 3 tries each; a scan only counts as fresh when every passkey answered.
- Hwallet: native scam-site list (MetaMask eth-phishing-detect + ScamSniffer) with a full-page "Deceptive site ahead" warning: Go back to safety, or Open anyway for this session.

## Kwallet v3.99.13 / Hwallet v1.9.13

- Passkey sheets: shorter, cleaner text. Create shows Name and Passkey number; sign-in shows a radio chooser (name, identity number, passkey number, last used).
- Faster Internet Identity: opening id.ai warms Kwallet and refreshes the identity list in the background; a list checked in the last 10 minutes is used at once, an older one waits at most 4 s.
- Browser: swipe a tab card left or right to close it, with haptic feedback (a tick at the point of no return, a firmer one on close).

## Kwallet v3.99.12 / Hwallet v1.9.12 (test build)

- Internet Identity, several identities on one seed (F6c). Hwallet now asks II which of this seed's Kwallet passkeys (#1 to #20) are registered, with II's public `lookup_device_key` query (signs nothing). A fresh phone with the same seed finds every identity again.
- Names exactly as II has them (hen, hen1, hen2). II keeps names private, so the first time Hwallet finds identities whose names it never saw, Kwallet shows one "Restore identities" sheet; each passkey signs one read-only `identity_info` call. Declining it just shows identity numbers.
- Kwallet's passkey sheet uses the same cards as the other popups: "Create passkey" shows the identity name exactly as II sent it and the passkey number; "Sign in with passkey" shows a Google-style chooser (name, identity number, passkey number, last used) when the seed has more than one identity.
- New identities skip every passkey number II already has, so a fresh install never reuses #1.
- After a new identity is created, Hwallet checks with II that it was registered and saves its identity number with the name it was made with.
- Pairing now also hands Hwallet the public passkey IDs for id.ai (no keys).
- ICP dApps: Hwallet announces itself through ICRC-94 (the ICP version of NEAR's "near-wallet-injected"), so IdentityKit / DFINITY signer dApps list Hwallet with its own icon. Supports ICRC-25 permissions, ICRC-27 accounts, ICRC-29 status and ICRC-49 canister calls (content map + IC certificate), every signature in Kwallet. Plug-style window.ic.plug stays for fixed wallet lists.
- Checked locally: II lookup query and Candid decoding against the live II canister (known identities resolve to their numbers, unknown passkeys to none); a Kwallet passkey-signed `identity_info` call is accepted by the Internet Computer (valid WebAuthn sender signature, II answers Unauthorized for an unregistered key, as expected).

## Kwallet v3.99.11 / Hwallet v1.9.11 (test build, not released)

- Internet Identity passkey: creating an identity hung on "Creating identity…". II's first passkey prompt signs a 9-byte challenge, which Hwallet refused as too short, and the refusal never reached the sign-in window (its answers were dropped). Both fixed; a failed passkey request now shows a message in Hwallet and II gets the error instead of waiting forever.

## Kwallet v3.99.10 / Hwallet v1.9.10 (test build, not released)

- Internet Identity with Kwallet as the passkey (F6b). When a site opens II, Hwallet now opens the real II page in a sign-in window over the tab (the site keeps window.opener). On II's pages, passkey prompts go to Kwallet, which makes or uses a P-256 passkey derived from the seed (relying party + passkey number), so the seed restores it. II itself signs the session, so apps that only accept genuine II logins (OISY) work, and Hwallet and Kwallet stay the only signers.
- Each seed has its own passkeys; a new II on the same seed gets the next passkey number. An existing II can add Kwallet as a passkey from II's own settings (open id.ai in the Hwallet browser).
- The v1.9.7 to v1.9.9 stand-in II window (Kwallet-made per-site accounts) is no longer used: OISY's backend refuses identities that II did not sign.
- Checked locally: registration and sign-in responses verify with a standard WebAuthn verifier (origin, RP ID, user verification, signature), II's COSE key extraction, deterministic credentials, conditional sign-in cancelled by abort.

## Kwallet v3.99.9 / Hwallet v1.9.9 (test build, not released)

- Internet Identity: Hwallet now also speaks the ICRC signer standard (ICRC-29 heartbeat, ICRC-25/34 delegation, ICRC-95 derivation origin) used by @icp-sdk/auth v6 and OISY, including its blank pre-opened signer window. The legacy II window protocol still works.
- Delegations limited to canisters (targets) are signed and shown on the Kwallet sheet.
- Checked locally against the real @icp-sdk/signer transport: channel, delegation signature, same account on every login, and a slow approval keeping the channel alive.

## Kwallet v3.99.8 / Hwallet v1.9.8 (test build, not released)

- Internet Identity sign-in reaches Kwallet: Hwallet's stand-in II window is now a real (hidden) window, so the app receives II's ready signal instead of reporting an interrupted login.

## Kwallet v3.99.7 / Hwallet v1.9.7

- NEAR Max to a 64-character (implicit) address works: NEAR charges an account-creation amount upfront on any send to an implicit address, even one that exists, and refunds the unused part a few seconds later. Max now keeps that back (B37).
- NEAR storage hold from 3.99.6 removed for zero-balance accounts (up to 770 bytes, no contract): they need no storage stake.
- Send all and close account stays the way to end at exactly 0 NEAR.
- Removed the "needs X NEAR available" line from the Kwallet and Hwallet send screens; close option text is now "Leaves 0 NEAR".
- New: Internet Identity in the Hwallet browser (F6). When a site opens Internet Identity, Hwallet answers and Kwallet signs the session. Each site gets its own account, derived from the seed and the site's origin, so the seed restores every account. ic0.app and icp0.io count as one site; a derivationOrigin is honoured only when its ii-alternative-origins file lists the site.

## Kwallet v3.99.6 / Hwallet v1.9.6 (test build, not released)

- NEAR Max keeps back the storage the account locks (0.00736 NEAR for 736 bytes). Max sends were failing with "Needs X available".
- NEAR "Send all and close account" (after Max, recipient must already exist): a DeleteAccount action sends the whole balance minus gas, and the account ends at exactly 0. Funding the address again reopens it.
- Activity: opening a transaction saves its confirmed status, fee and time onto the local entry.

## Kwallet v3.99.5 / Hwallet v1.9.5 (test build, not released)

- NEAR dApps: Hwallet registers with NEAR Connect (the "near-wallet-injected" event) as Hwallet, HOT Wallet, Meteor Wallet and NEAR Mobile. Every action is signed in Kwallet.
- Transaction details: the Network fee is read from the chain on every network (NEAR, Bitcoin, Sui, XRP, Cardano and ICP join XMR, Solana, EVM and TRON) and saved by hash, so it shows at once next time.
- Browser: a monochrome search engine logo in the address bar. Each tap switches Google, DuckDuckGo, Brave.
- Chains: two tabs, Chains and EVM networks. The same rows and switches, regrouped; the last tab is remembered.
- Hwallet's send sheet shows "needs X NEAR available", like Kwallet's.
- Kwallet ignores NEAR fee data from Hwallet that falls outside sane limits, and flags any network fee over $50 as unusually high.
- More fallback endpoints: Base, Arbitrum, Optimism, BNB Chain, Linea, Bitcoin, TRON.
- CI: builds on pushes to wip-* branches, checks that the NEAR fee code is identical in both apps and gives the protocol's numbers, and starts both pages in headless Chrome, failing on any uncaught error.

## Kwallet v3.99.4 / Hwallet v1.9.4 (test build, not released)

- NEAR fees follow today's protocol: gas is bought up front at 0.001 NEAR per Tgas and partly refunded, and creating a new account costs 0.007 NEAR. The send sheet shows the real fee plus "needs X NEAR available" when more must be held for a few seconds. Max subtracts the upfront amount, so "Not enough NEAR" no longer happens after Max.
- Hwallet reads NEAR's live fee settings (cached for an hour) and checks whether a 64-character recipient already exists.
- Small accounts (up to 770 bytes, no contract) no longer keep back a storage reserve.
- NEAR errors: "Not enough balance" now says how much is needed and how much you have. The old 0.00182 NEAR recipient wording is gone.

## Kwallet v3.99.3 / Hwallet v1.9.3 (test build, not released)

- Pairing: dismissing or failing the unlock ends the request; the seed list never opens without it.
- NEAR Max: the fee covers NEAR's upfront transfer cost (creating a new implicit account too), so Max sends everything except the account's storage minimum.
- Opening and PIN screens: NEAR and ICP marks join the floating coins.

## Kwallet v3.99.2 / Hwallet v1.9.2 (test build, not released)

- Pair sheet: the connected wallet is its own card under "Connected to Hwallet"; the other seeds follow in a second card under "Other wallets".
- Signing: when a pairing's secret copy is missing ("Kwallet could not open this pairing"), Kwallet finds the seed by its public seed ID. Re-pairing no longer rewrites the vault list.
- NEAR send: a named recipient must exist before signing; chain failures are explained in words (new account below 0.00182 NEAR, unknown account, nonce, expiry); the discontinued Lava node is replaced by dRPC and rpc.mainnet.near.org.
- Clean up: every node is asked at once and the first good answer wins, so the sheet loads faster.

## Kwallet v3.99.1 / Hwallet v1.9.1 (test build, not released)

- Pair sheet: the wallet Hwallet is using now sits on top under its own name, marked "Connected to Hwallet" and preselected; the other seeds follow under "Other wallets". Before, the first paired seed was preselected, so Pair could switch Hwallet to another wallet.
- Re-pairing a wallet paired before 3.99.0 now adds its NEAR and ICP addresses (derived from the seed, checked against its Ethereum address). Before, the old record was sent back unchanged.
- Hwallet Receive and Send pickers: a wallet without NEAR or ICP addresses shows a "Pair again with Kwallet" prompt.
- Clean up sheet shows what you get back (SOL rent, Sui storage rebate), not the balance.
- Cardano DEX orders (Minswap): Kwallet reads the order datum and shows the ADA locked in the order, what you swap, about how much ADA comes back with the tokens, and the batcher fee, instead of counting the whole order as spent.

## Kwallet v3.99.0 / Hwallet v1.9.0 (test build, not released)

- NEAR and Internet Computer (ICP), in both apps. NEAR: m/44'/397'/0' ed25519, implicit 64-hex account, NEP-141 tokens. ICP: m/44'/223'/0'/0/0 secp256k1, principal plus ledger account ID, ICRC-1 tokens.
- Kwallet: NEAR and ICP address rows, QR, key export (NEAR ed25519:… key, ICP hex key), address search, explorers (NearBlocks, Pikespeak, ICP Dashboard, IC Explorer), pairing with Hwallet, signing for sends and app requests (NEAR transactions and NEP-413 messages, ICP canister calls and messages).
- Chain row: NEAR and ICP sit after XMR. The row keeps its size and scrolls sideways with a soft edge fade; the selected chain scrolls into view.
- Hwallet: live NEAR and ICP balances and USD prices, token discovery and Add token, history, send and receive (ICP shows the account ID too), address book, explorers, CSV through the shared history.
- Hwallet appears as Sender (window.near, for NEAR Wallet Selector apps) and as Plug (window.ic.plug: connect, balance, ICP and ICRC transfers, canister calls). Plug's actor API is not supported.
- NEAR Intents swaps can pay from NEAR: tokens go straight to the deposit account; native NEAR is wrapped and sent in one transaction.
- Cardano tokens: USD prices from DefiLlama (stablecoins at $1), logos from the Cardano registry, then Minswap's logo server, sent to Kwallet as small offline images; CIP-68 decimals and tickers.
- Monero: a typed maximum fee. Hwallet takes the fastest of Monero's 4 levels that fits under it.
- S|V, PK and Addr keep OG's rounded rectangle (9 px corners, 30 px high) in every UI style; Kwallet's signer sheets follow the active style.
- Faster Hwallet to Kwallet updates: slow chains are re-read every 2 to 4 s instead of every second; WebSocket block and account events still update at once.
- Re-pair Hwallet once after installing so NEAR and ICP come across.

## Kwallet v3.98.40 / Hwallet v1.8.42

- Cardano dApp transactions (Minswap and other DEX swaps): Hwallet looks up every input the transaction spends and sends it to Kwallet; Kwallet checks the list against the transaction and shows your real balance change (ADA and tokens) instead of adding up every output, so a pool's own UTxOs no longer look like your money. No false "Not enough ADA" or "tokens leave your wallet"; contract outputs move to Details.
- Cardano withdrawals: the staking warning shows only when the withdrawal is from your own stake key; an app's own 0 ADA contract check is a Details note.
- Pair sheet: one header row (Kwallet badge, Pair with Hwallet, close) with a muted "addresses only" line and the paired chains' logos; no chain chip, no balance card; empty "Seed N" slots fold into "+ New wallet".

## Hwallet v1.8.41 (2026-10-19)

- Swap screen tightened: both token cards and Details now share one width, the flip button sits on the seam between them, and the Details sheet rows are shorter.
- SOL sent to a brand-new address (a cross-chain swap deposit, such as NEAR Intents SOL→TRX) now shows in Kwallet as SOL you send. It no longer shows as one-time "Rent" with "No change to your balances".
- Saved Sui custom fee on app requests: your gas price is kept, and only the gas budget (a cap) is raised when the app's transaction needs more. Before, it fell back to the app's fee.
- Swap: the default network fee reads "Normal" (it was "Site suggested"), and for chains whose fee is set in Kwallet it reads "Your saved fee".

## Kwallet v3.98.39 (2026-10-19)

- Monero Send sheet in Kwallet has the Kwallet badge and Monero chip again, like every other sheet.
- Sui app requests: the main view shows only balance changes, fee and wallet, like Solana. The simulation note and the raw Move steps are now under Details (when there's no simulation, the steps stay visible).
- Recipient on Kwallet's Send sheets (Monero and the other chains): first and last 6 characters in white with a muted ellipsis in between. The full address is still under Details.

## Kwallet v3.98.38 + Hwallet v1.8.40 (2026-10-19)

- Solana signing preview stays live: while Kwallet's sheet is open, Hwallet re-simulates the transaction every 4 seconds and the sheet updates when the result changes (Approve re-arms after a change). Polling stops on approve, reject or cancel.
- Solana preview runs one simulation instead of two (the older balance-only check runs only if the preview can't be built). Address lookup tables are read through Helius with fallback and one retry; if a table still can't be read, the preview says it may be incomplete. When the node gives no answer, the sheet says so in plain words instead of showing nothing.
- Send: the recipient address shows its first and last 6 characters in white and the middle muted and shortened, whenever the field isn't being edited (helps spot look-alike addresses). Typing and pasting are unchanged.
- Sign and send sheets tightened: smaller gaps and paddings and shorter rows on Solana, EVM, Sui, XRP, Monero and every Send sheet. Same design: badge, chain chip, nested cards, button edge, colours and sizes unchanged.
- Sui and XRP dApp sheets show the chain chip like the others; Sui balance changes show the real token icon (USDC etc.) instead of a letter.
- Send XMR: the close button sits inside the sheet edge, in line with the chain chip.
- Monero: coin page shows how much XMR is still unlocking (received in the last 10 blocks).
- Activity: export as CSV (Download/Hwallet).
- Kwallet: crash reports are saved on the phone and offered once on the next launch (copy to send yourself; no keys, seeds or addresses; nothing leaves the phone).
- Build: every inline script of both pages is syntax-checked (`node --check`) before the APKs are built. New docs/VERIFY.md on checking downloads, signatures and building it yourself.

## Kwallet v3.98.37 + Hwallet v1.8.39 (2026-10-19)

- Kwallet pairing: the big card shows only the selected seed's most active chain (logo, name, balance and USD), and the header chip names that chain. Seed rows are compact one-line selectors. The title no longer overlaps the card, and the extra note moved into Details. Balances come from Hwallet's latest requests for that seed.
- Solana cleanup fixed: the Token-2022 program id was wrong (caused "String is the wrong size"). Lookups now go through Hwallet's working Solana nodes with fallback, so a 403 from one node no longer breaks the panel. Tokens with no live price can be burned, with a warning. Sui cleanup uses the same fallback. Token-2022 balances and sends now work too.
- Chains ⋮ menu: clean "Clean up" sheet using the Send network rows, with spacing fixed and balances shown.
- Transaction status on every chain (EVM, Solana, Sui, Tron, Bitcoin, XRP, Cardano): pending transactions are checked on-chain every few seconds and flip to Completed or Failed, with the real fee saved and balances refreshed. Nodes that fail or refuse are skipped. Kwallet's activity follows the statuses Hwallet sends.
- Activity history: dApp connects, disconnects and signatures are logged with the dApp's name and origin; every transaction Kwallet signs is logged with chain, type, amount, fee, recipient or contract, origin, hash and status.
- Tron fees rebuilt from live chain data: real bandwidth and energy left, current burn prices, energy estimated for the actual token transfer, and +1 TRX (+0.1 TRX) activation for new recipients in its own row. "Free" appears only when the fee really is 0. The fee updates as you type the address, and Kwallet now receives the updated fee.
- Solana token send: the new token account cost shows once, as a clean "New token account" row, instead of being repeated in the fee line.

## Kwallet v3.98.36 + Hwallet v1.8.38 (2026-10-18)

- Kwallet: the pairing sheet is now a clone of the Send sheet. It has the same header, "Request from Hwallet", a "Pair" title, the chosen seed in the asset card, seeds as Send-style rows with aligned values, Address number in the Wallet row, the same note and Details (seed ID, type, words), and Cancel / Pair. The selected seed is highlighted inside the card, never clipped.
- Hwallet always follows Kwallet: renaming a seed in Kwallet updates Hwallet at once, and Hwallet re-reads the names each time it opens. In Hwallet, names of Kwallet seeds are read-only.
- Hwallet's real logo (lime circle, navy H, or the navy icon if chosen) now shows in dApp wallet pickers (Solana, EVM, Sui, XRPL, BTC, Cardano) and in WalletConnect.
- Solana: a token that drops to 0 (e.g. USDC) no longer keeps showing its old balance.
- Tron Send: the recipient is checked on-chain. A not-yet-activated recipient is shown with the 1 TRX activation fee (+0.1 TRX when staked bandwidth doesn't cover it) in the fee and total, in Hwallet and Kwallet. Token (TRC-20) sends don't activate the recipient, so they show no activation fee.
- Solana token Send: shows whether the recipient already has a token account. If not, it shows "Opens a token account" with the live rent added to the fee and total, and the account is created in the same transaction.
- New: ⋮ next to Chains on Hwallet's home opens Solana and Sui cleanup panels.
  - Solana: lists your token accounts live. For each one, choose Close (empty accounts) or Burn (tokens worth under $0.10), and see the SOL you get back per account and in total. Kwallet signs.
  - Sui: merge and delete empty coin objects to get the storage SUI back, or burn tokens worth under $0.10 (sent to 0x0). SUI itself is never offered. Kwallet signs.

## Kwallet v3.98.35 + Hwallet v1.8.37 (2026-10-18)

- Kwallet: the pairing PIN screen is now Kwallet's real lock screen: "Enter PIN" with no subtitle, the same logo, dots and keypad positions, the fingerprint key and "Forgot PIN?". The fingerprint continues pairing without unlocking Kwallet itself. Android Back cancels pairing and Hwallet is told it was rejected. Forgot PIN? cancels pairing (nothing is erased) and Kwallet's own Forgot PIN dialog opens the next time you open Kwallet.
- Kwallet: the pairing sheet uses the same design as the Send and dApp signing sheets: the "Request from Hwallet" line, a "Pair" title, the same cards, fonts and rows, and the same Cancel / Pair buttons.
- Custom fees on every chain that allows them, saved per chain until you change or reset them (shown as "Custom · saved"):
  - Monero: Custom no longer falls back to Normal; you pick priority 1–4, and it is used to build the transaction.
  - Cardano: custom fee in ADA (never below the network minimum, at most 5 ADA).
  - Kwallet's Send sheet can now edit custom fees for Bitcoin, XRP and Sui as well as EVM, Solana and Tron tokens.
  - A saved custom fee survives restarts, account switches, lock/unlock and live fee updates. If it can't be used for one send, Normal is used with a warning and the saved fee is kept.
  - Bitcoin PSBT and Cardano dApp transactions keep the dApp's fee, since changing it would break the dApp's transaction.

## Kwallet v3.98.34 + Hwallet v1.8.36 (2026-10-18)

- Kwallet: the pairing sheet (Pair with Hwallet) is compact and solid: one header row with the Kwallet badge only (no Hwallet logo), seeds as one flat list of 56px one-line rows (name with type, word count and seed ID on a single quiet line, no chips), and the address number with Cancel / Pair pinned in the footer. The sheet fits its content, with no wasted space.
- Kwallet badge: the K is no longer clipped by the outlined "wallet" on all sheets in both Kwallet and Hwallet.

## Kwallet v3.98.33 + Hwallet v1.8.35 (2026-10-18)

- Hwallet: Sui dApp requests: Confirm no longer stays greyed out after picking a fee. The SUI balance is read fresh when the sheet opens and a low balance is shown as a warning; the dry run and Kwallet still refuse a gas budget the coins can't cover.
- Fees stay as you set them, per chain: Send and dApp sheets (now including Sui and XRP Ledger dApp requests) open on the speed or custom fee you last picked for that chain, shown as "Custom · saved". If a saved custom fee is below the network's current minimum it is never replaced silently: Hwallet's Send / dApp sheets and Kwallet's sheet say so and show the minimum.
- Kwallet: Solana dApp requests where someone else pays the fee: the balance check counts only the SOL that leaves your own wallet and no longer asks for the network fee on top, so these requests are no longer refused by mistake.
- Hwallet: the Wallets menu shows each seed's chain logo (its most used chain) instead of a letter, and each seed's live total balance, refreshed while the menu is open. Hide balances still hides them.
- Kwallet: seed names and seed IDs are now kept in backups (encrypted and plain) and in exports (ZIP JSON, PDF, CSV), and restored on import, so restored seeds keep their names and stay matched with their Hwallet pairings. Older backups still restore as before; older Kwallet versions still read new backups.
- Hwallet Browser: Chromium-style address bar with history and suggestions as you type.
- Hwallet Browser: native autofill dropdown under login, email and address fields (like Chrome), and a Save / Update password prompt after you sign in. Note: saved passwords now need your phone's screen lock (PIN, pattern, password or biometrics) before they are filled or shown.
- Send and dApp sheets in both apps carry the OG Kwallet badge, and the sheets open and scroll more smoothly.
- Kwallet: the pairing, signing and Send sheets use Kwallet's OG card design (stacked panels, K chips, highlighted address ends) in the charcoal palette.
- Kwallet: pairing from Hwallet now uses Kwallet's real lock screen, opens faster, and does not lock or unlock Kwallet's own session.

## Kwallet v3.98.32 + Hwallet v1.8.34 (2026-10-17)

- Privacy fix: Hwallet no longer keeps wallets of seeds that Kwallet no longer holds. When Kwallet's data is cleared (Android Settings > Storage > Clear data), Kwallet is uninstalled, Kwallet is erased in the app, or a seed is unpaired in Kwallet, Hwallet erases those wallets: every address number, balances, activity and history, swaps, recent recipients from that history, the Monero view key with its scan state and sync files, Browser site connections and WalletConnect sessions of those addresses.
- It happens at once, also when Hwallet is closed: a cleared or removed Kwallet is caught by Android's own notice, and Kwallet tells Hwallet itself when it is erased or a seed is unpaired. If Hwallet missed it (closed by force, phone off), it checks with Kwallet each time it opens or comes back, before any wallet is shown.
- Kwallet being locked, closed or not answering changes nothing in Hwallet; only an erase, a data clear, an uninstall or an unpaired seed does.
- Addresses typed into Hwallet by hand (watch only) stay, as a Watch-only wallet. With no wallet left, Hwallet opens on Pair with Kwallet.
- Hwallet says what happened in one short notice, e.g. "Kwallet's data was cleared. Hwallet erased its paired wallets. Pair again to continue." or "Seed 4821-0937-52 was removed in Kwallet. Its wallet was erased from Hwallet."
- Kwallet: Erase everything and Export with erase now also remove the Hwallet pairings and their public index. Hwallet only learns Kwallet's install ID and the public seed IDs of paired seeds; no PIN, no keys, no addresses. Only the two apps, signed with the same key, can use this link.
- Kwallet: the "12 words" chip on the seed card keeps its normal size in every UI style; it no longer grows over the last line of words.
- Kwallet: the signing PIN page (Hwallet asks Kwallet to sign) wears Kwallet's own look in light and dark, opens at once with no intro animation, and covers the status and navigation bar areas with readable bar icons.
- Kwallet: the word-count picker (12 Standard / 15 / 18 / 21 / 24 Strong / 16 Poly / 25 XMR) is an even, edge-to-edge row in OG, Material and MIUIX, light and dark; on narrow screens it wraps into an even 4 + 3 grid. No more tall bubbles or "Stan…".
- Kwallet: the review shows Hwallet's custom fee as "Custom" and says when a fee is set by the app (Solana, Bitcoin PSBT) or by the Cardano protocol.
- Hwallet: Pair with Kwallet page redesigned in Kwallet's look (lime accent, RobotoE, K cards and chips), light and dark. Pair with Kwallet, pair by seed ID, watch-only addresses and the privacy note all stay.
- Hwallet: Monero scan start is one compact sheet ("Scan from": New · Date · Block · Everything, one input, Start scan). A paired Monero wallet now starts scanning by itself from the restore height Kwallet sends and syncs spent status at the start; the sheet stays as an optional way to change the start.
- Hwallet: the dark Send sheet and the dApp request sheets are solid and readable in both themes (amount, address, fee and chips no longer vanish), tighter and more polished.
- Hwallet: the network fee can be changed on Send and on dApp requests: Slow / Normal / Fast plus Custom where the chain allows (Bitcoin sat/vB, XRP drops, Sui gas price and budget, Solana priority fee, EVM gas fields). For an app-built transaction Hwallet rewrites only the fee fields before Kwallet signs; co-signed or pre-signed transactions and Cardano's protocol fee are shown as fixed, with the reason.
- Hwallet Browser: new Dark pages setting in the ⋮ menu (Auto follows Hwallet's theme / On / Off). Uses the site's own dark theme first, otherwise Android's automatic darkening.

## Kwallet v3.98.31 + Hwallet v1.8.33 (2026-10-16)

- Kwallet: the Pair Hwallet option is gone from Settings. Pairing starts only from Hwallet (Pair with Kwallet): Kwallet unlocks once with PIN or biometrics, shows every seed, and you pick the seed and any address number.
- Kwallet: the seed card shows the seed's name and seed ID beside Passphrase, in the same chip style. Tap the name to rename the seed (Hwallet gets the name at the next pairing); tap the ID to copy it. The chips sit out of the way of typing and pasting in Import.
- Hwallet: xrp.cafe connects again. Its GemWallet button is answered again on xrp.cafe, and WalletConnect XRP sign-ins sent with autofill off are signed as given, so an unactivated account or a slow node no longer breaks xrp.cafe's sign-in.

## Kwallet v3.98.30 (2026-10-16)

- Add seed now lists every seed Kwallet holds: the seed on screen, every seed in the Gen / Import Undo and Redo history, and the Saved keys seed book (generated and imported), each once with its seed ID. Before, it showed only the seed book and paired seeds.

## Kwallet v3.98.29 + Hwallet v1.8.32 (2026-10-16)

- Kwallet Pair Hwallet page rebuilt in Kwallet's own Saved keys style: one card per paired seed (GEN / IMP badge, name, type, seed ID, address numbers with a + to add one, Copy ID, Unpair). Tapping an address number offers to remove it.
- New Add seed sheet: every seed in Kwallet, generated or imported, with its type and seed ID (search when there are many). Pick one, name it, choose the address number, Pair. The old name / number panel under the list is gone.
- Hwallet no longer closes on xrp.cafe: the wallet wake-up link a dApp opens after connecting (bifrostwallet://…) resolved to Hwallet itself and sent it to the background after the next WalletConnect answer. Such links are dropped inside Hwallet's Browser.
- Hwallet answers Girin Wallet (girinwallet://) and Joey Wallet (joey://settings/wc?uri=) WalletConnect links too, next to Bifrost, for XRP Ledger dApps.
- Tron: Hwallet's Tron provider is also announced under the TronLink name (TIP-6963), which TronLink adapters such as SunSwap's look for.

## Kwallet v3.98.28 + Hwallet v1.8.31 (2026-10-16)

- Kwallet's Pair Hwallet page lists every seed Kwallet holds (seed book, the seed on screen, paired seeds), each with its type (BIP39 12/24 words, Polyseed, Monero 25), seed ID with copy, and its paired address numbers. Pick a seed, pick an address number, Pair; no need to put the seed on screen first.
- The pairing request from Hwallet (Add wallet / Pair with Kwallet / seed ID) shows the same seed list after one PIN or biometric unlock; the user picks the seed and the address number, and a new number is derived right there. Only that seed's public addresses (and Monero view key) go to Hwallet; the other seeds' addresses are no longer sent.
- Pairing text (kwallet-pair: codes) removed from both apps: no Copy code in Kwallet, no paste or QR pairing in Hwallet. Pairing is Pair with Kwallet, or the seed ID.
- Hwallet: Add wallet opens Kwallet's seed picker directly. Wallet rows are compact: initial, name, seed ID, address numbers on the right (shown as 0 1 2, no #); tapping a number in the wallet sheet switches to it.
- Address numbers read as plain numbers everywhere ("Address 1" in menus and toasts).

## Kwallet v3.98.27 + Hwallet v1.8.30 (2026-10-16)

- Address numbers per seed: a seed paired at #0 and then #1 is one wallet in Hwallet with both numbers as chips on its row; a tap switches the live number. Only the selected number is live; balances, activity, history and swaps are kept per number. Wallet options list the numbers (remove one) and the seed ID with a copy button.
- Kwallet keeps every address number it pairs for a seed (needed to sign for each), shown as one seed row with #0 #1 chips; the ⋮ menu removes one number or the whole seed.
- Pair by seed ID: Hwallet's pairing page takes the 10-digit seed ID (the check digit catches typos); Kwallet opens on that seed and sends every paired address number in one handoff.
- Hwallet wallets paired before seed IDs learn theirs from the next pairing or the next signed request.
- Kwallet's Pair Hwallet page: no step strip, name and address number on one row, a copy button beside each seed ID, Copy pairing code removed (Hwallet still accepts old codes under Other ways).
- Hwallet's pairing page: step strip removed, seed ID field under Pair with Kwallet.

## Kwallet v3.98.26 + Hwallet v1.8.29 (2026-10-16)

- Seed ID: every paired seed gets a 10-digit public ID (e.g. 4821-0937-52), a hash of its primary public address with a check digit. The same seed always gets the same ID at any address number; a BIP39 passphrase gives another ID. It is shown on Kwallet's Pair Hwallet page (with Copy seed ID), on Kwallet's pairing sheet and on Hwallet's wallet rows and pairing result.
- One seed, one pairing: pairing a seed again replaces its pairing in Kwallet and updates the same wallet in Hwallet, found by its seed ID.
- Per-seed sandbox in Hwallet: chain switches belong to each seed. Turning a chain off on one seed never affects another; the old app-wide switches are only the starting point for seeds without their own. App settings are unchanged and stay app-wide.
- Fixed: turning Monero off on a multi-chain seed made a Monero-only seed look unpaired (Pair again) and pairing it crashed. A paired wallet is never emptied by chain switches, and pairing never crashes while drawing.
- Monero spent status syncs automatically after pairing and when a scan finds outputs without key images; Kwallet answers without a tap when it can sign without a PIN (key images only, can't move funds). Settings > Monero > Sync spent status stays as the manual way.
- Pairing panels: compact, solid cards, square avatars, tighter rows, seed ID in tabular mono. dApp sheets are unchanged.

## Kwallet v3.98.25 + Hwallet v1.8.28 (2026-10-16)

- Tron dApps: Hwallet's Tron provider now has the TIP-1193 events (on / removeListener) that discovery-aware dApps call after TIP-6963, so they list Hwallet by name. TronLink compatibility is unchanged.
- WalletConnect from any dApp, any chain: Hwallet answers the WalletConnect links dApps open for MetaMask, Trust, OKX, Bitget, TokenPocket, Rainbow, SafePal, Zerion and Uniswap Wallet when those apps are not installed, and a WalletConnect link copied from a QR ("Copy link") pairs as soon as Hwallet opens. The chains come from the dApp's proposal.
- xrp.cafe: Hwallet's injected XRP stand-ins (Xaman / Crossmark / GemWallet style) are left out on xrp.cafe, which connects through WalletConnect (Bifrost) instead.
- Kwallet: version bump only (the dApp sheets keep their look).

## Kwallet v3.98.24 + Hwallet v1.8.27 (2026-10-16)

- Hwallet as Bifrost (XRP Ledger WalletConnect): dApps that connected to Hwallet through their Bifrost button (xrp.cafe and others) open bifrostwallet:// to pair and to bring the wallet up for each sign request. Hwallet now answers those links, so a sign request opens Hwallet and goes to Kwallet instead of only leaving a notification while Hwallet stays in the background. Kwallet: version bump only.

## Kwallet v3.98.23 + Hwallet v1.8.26 (2026-10-16)

- Hwallet in Tron dApps: still connects as TronLink (window.tronLink / tronWeb, for fixed wallet lists) and now also announces itself as Hwallet with its own icon (TIP-6963 and window.tron), so dApps that discover Tron wallets list Hwallet by name. Kwallet: version bump only.

## Kwallet v3.98.22 + Hwallet v1.8.25 (2026-10-16)

- Kwallet: one red (#d93025) on every red button (Clear, PK, S|V, Cancel on the sign sheet, the lock screen's Forget buttons) in every style (OG, MD3, M3, MIUIX), light and dark.
- Cardano governance (CIP-95): Hwallet (and its eternl / lace aliases) now offers CIP-95, so DRep sites (DRepTalk, GovTool…) can sign in. getPubDRepKey and the stake keys come from Kwallet once (Share sheet); signData with a DRep ID or stake address is signed by that key in Kwallet; transactions whose certificates, withdrawals or votes name your stake or DRep key get those witnesses too. Every signature is reviewed in Kwallet.
- Sui dApps: a simulation that fails right after the app built the transaction (MoveAbort from a router whose quote or objects the node had not caught up with) is built and simulated again before Kwallet shows a failure, so the review sheet no longer opens on an error that clears a second later.
- XRP Ledger WalletConnect: Hwallet accepts proposals that list xrpl_signTransactionFor and the account / chain events (Bifrost-style dApps), so they connect instead of failing; multi-signing itself is still refused.
- Crash fix: every WebView in Hwallet and Kwallet shares one renderer; when a heavy dApp page killed it, Android closed the whole app. Both apps now rebuild their screen instead of closing (the force close on some XRP dApps).
- Browser: websites can upload files from storage again (system file picker, single or multiple files).
- Notifications: a WebSocket event now checks the balance at once and again after 1.5 s and 4 s (the node that answers can lag the one that pushed), an event that lands during a check is no longer dropped, and a flaky node backs off for at most 8 s instead of 30 s. Incoming payments are notified within seconds on every chain.

## Kwallet v3.98.21 + Hwallet v1.8.24 (2026-10-16)

- Kwallet: the Monero S|V key button is now a real PK button (same classes), so in every style (OG, MD3, M3, MIUIX, light and dark) it has exactly PK's size, corners, red and font, split into S | V by a thin divider. The live-measuring script from 3.98.19 is gone. Copying S or V never greys the button; only the copied key text is struck. Hide dims it once, like PK.
- Hwallet: the Monero balance no longer goes up while your own send waits in the mempool. It shows at most balance before minus amount and fee until the send is in a block (or 2 hours pass).
- Hwallet: the TRX Max button and the swap sheet's 25% / 50% / Max chips use the same outlined chip style as the send sheet.

## Kwallet v3.98.20 + Hwallet v1.8.23 (2026-10-16)

- Hwallet: tighter, cleaner USD/Max chips on the send sheet. (Max on Monero fills the spendable amount; change from a recent send stays locked for 10 blocks.)

## Kwallet v3.98.19 + Hwallet v1.8.22 (2026-10-16)

- Hwallet: Monero confirmation blocks turn green with a green 10+ once complete; slimmer blocks with the count on the same line.
- Monero S|V key button now takes the PK button's exact shape, size, font and red in the active theme (measured live, so it follows every theme switch), and copying S or V no longer greys the button; only the copied key text is struck.

## Kwallet v3.98.18 + Hwallet v1.8.21 (2026-10-16)

- Monero confirmed working end to end on device (Hwallet signs nothing; Kwallet signs every send after review).
- Hwallet: the Transaction sheet shows Monero confirmations as 10 blocks with a count; the chip reads Waiting for a block / Confirming until the 10th confirmation.
- Kwallet: the Monero S|V key button is a split pill with exactly the PK button's shape, size and red in every theme.

## Hwallet v1.8.20 (2026-10-15, work in progress; Kwallet stays 3.98.17)

- Monero: Kwallet's "Failed to authenticate ciphertext" means the outputs it got were exported by a wallet whose view key is not the paired one. Every Monero op Kwallet signs for (outputs export for key images, transaction build) now carries the paired primary address; Hwallet's native engine refuses to run it on any other open wallet (WALLET_MISMATCH), Hwallet reopens the paired wallet and retries once, and the export reports which wallet it came from so a wrong one never reaches Kwallet. Still unconfirmed on device.

## Kwallet v3.98.17 · Hwallet v1.8.19 (2026-10-15, work in progress)

- Monero: Kwallet's engine no longer swallows an output-import failure. monero-ts resolved importOutputs() with the error text instead of rejecting, so Kwallet returned zero key images and Hwallet fell back silently ("sending may still fail:" with no reason). Kwallet now fails the request with the real reason ("Kwallet could not read Hwallet's Monero outputs: …"), both for Sync spent status and for Send; Hwallet names an empty or offset answer and shows the raw engine error next to its hint when a send fails. Diagnostic step; Monero send still unconfirmed on device.
- Hwallet › Settings: the Support card is the 1.8.12 card again exactly (four stacked rows ETH / SOL / BTC / XMR with coin badge, short address and copy icon, stretched down to just above the bottom bar); the 1.8.16 row shrinking and the 1.8.18 2×2 grid are gone. The bottom padding for the bottom bar stays.
- Hwallet browser: Sui is also announced as "Slush" (Wallet Standard, sui:mainnet) for apps with a fixed list such as Cetus, like the Cardano eternl alias. Same Hwallet Sui account; every signature still goes to Kwallet with its review sheet.

## Hwallet v1.8.18 (2026-10-15, work in progress; Kwallet stays 3.98.16)

- Hwallet › Settings: the Support card is compact and takes its natural height: one header line ("Support Hwallet · Free and open source"), then ETH, SOL / BTC, XMR as a 2×2 grid of chips (coin icon, short address, copy icon; tap a chip to copy, with the copy toast). Lime card with dark text in OG, M3 and MIUIX, light and dark. The 1.8.17 stretch-to-fill / height-measuring logic and the row tightening are gone; the Settings page now pads its bottom by the fixed bottom bar's real height (gesture inset included) + 8 px, so nothing sits under the bar and a short screen simply scrolls. versionName 1.8.18 / versionCode 10818 (from APP_VERSION).

## Hwallet v1.8.17 (2026-10-15, work in progress; Kwallet stays 3.98.16)

- Hwallet › Settings: the lime "Support Hwallet · Free and open source" donation card (ETH / SOL / BTC / XMR, icon, short address, tap to copy) is back on the main Settings page, directly under the settings list; the separate "Support Hwallet" row and page are gone. The page is a flex column measured live down to the bottom bar (bar height + gesture inset, so OG, M3 and MIUIX all fit): header, list and card on one screen, no scrolling, nothing under the bottom bar or the status bar. On shorter screens the list and card rows tighten (44 px, then 40 px rows; card rows 36–52 px); only a very short screen scrolls. versionName 1.8.17 / versionCode 10817 (from APP_VERSION).

## Kwallet v3.98.16 · Hwallet v1.8.16 (2026-10-15, work in progress)

Working build for phone testing. Not tested on a device or emulator: static checks (`node --check` of every inline and injected script, Kotlin brace/comment check, a stubbed run of the Browser providers, BIP-322 checked against the BIP's to_spend / to_sign vectors, PSBT sign + extract checked with bitcoinjs) and the CI build. The Monero fix is ready to test on a device, not confirmed.

### Monero
- Kwallet's Monero sheet crashed before drawing ("Cannot set properties of null (setting 'onclick')"): `xmrPage()` drew the sheet before mounting it, so its buttons were looked up in a page that was not in the document yet. It now mounts first, then draws, like every other sheet (the other sheet kinds were checked: pair, send, dApp and the error sheet already mount first).
- Cancel works while the Monero engine is still running (it stops the engine; nothing has left Kwallet at that point).

### Live K↔H link, no timeouts
- No time limits on a request any more: the 30 s watchdog error in Kwallet, the request `expires` countdown and auto-expire on both sides, Hwallet's 45 s Monero wait and 300 s limit, the 12 s "Kwallet did not open" error and the hard 110 s Monero signing cut are gone. Only the user's decision (Approve / Reject / Cancel) ends a request. `expires` is still sent for older builds but Kwallet ignores it.
- Hwallet hands Kwallet a Binder with each request; Kwallet pushes every state to it (received → sheet open → waiting for you → signing → signed / rejected / error with the stage) and hands back its own Binder. Both sides check the caller's package on every call.
- Hwallet shows a "Waiting for Kwallet…" card with the live state and a Cancel button; Cancel closes Kwallet's sheet (nothing is signed).
- If either app's process dies, the other notices at once (Binder death): Hwallet says "Kwallet stopped before answering. Nothing was signed"; Kwallet closes its sheet when Hwallet is gone.
- A sign page that throws before its sheet draws answers immediately with the error and the stage. A long Monero operation shows "Still signing… N s" on the sheet and in Hwallet instead of failing.

### Design
- Hwallet › Pair with Kwallet: the empty red error bar under the Pair button is gone (the error box exists only when there is an error); tighter hero, one status card, slim one-line stepper, privacy line as a small footnote.
- Kwallet › Pair Hwallet: Kwallet's lime / dark palette instead of blue; slim stepper on top, the pair form in one card, paired wallets as clean rows (chain icon, name, short address, chain count) with Copy pairing code and Remove in a ⋮ menu.
- Kwallet address rows: the Monero S|V key button is one pill with a segmented look (Spend | View), same height, radius and type as the Addr pill in every UI style, in dark slate instead of alarm red.
- Hwallet Settings: shorter rows, smaller icons and one-line subtitles so the menu fits one screen; the "Support Hwallet" card moved to its own page (Settings › Support Hwallet); the Settings title is sticky under the status bar, and page content no longer scrolls visibly under the status bar on any screen except the dApp browser.

### dApps (Hwallet Browser; every signature in Kwallet with a review sheet; only chains switched ON in Chains are announced)
- Cardano: the CIP-30 API is also exposed as `window.cardano.eternl` and `window.cardano.lace` (Hwallet icon) for dApps with a fixed wallet list (dano.finance and similar).
- XRP Ledger: Crossmark-compatible provider (`window.xrpl.isCrossmark`, the @crossmarkio/sdk request / response protocol: sign-in, sign, sign-and-submit, address, network); a page that loads xrpl.js keeps its library. XRPL Wallet Standard registration as Hwallet (`xrpl:0`). GemWallet compatibility kept.
- Bitcoin (native SegWit address of the paired seed): UniSat-compatible `window.unisat` (requestAccounts, getAccounts, getPublicKey, getBalance, getNetwork, signMessage, signPsbt / signPsbts, pushPsbt, pushTx, sendBitcoin), Xverse / sats-connect `request()` provider (`window.XverseProviders.BitcoinProvider`, `window.BitcoinProvider`, listed in `window.btc_providers` as Hwallet) and a Bitcoin Wallet Standard wallet (`bitcoin:mainnet`). WalletConnect bip122 now also supports `signMessage` and `signPsbt`.
- Kwallet signs Bitcoin messages (legacy "Bitcoin Signed Message" ECDSA, or BIP-322 simple) and PSBTs. The PSBT sheet lists every input (yours or not, signed by Kwallet or not), every output, change, fee and fee rate, and flags sighash types other than ALL; Kwallet refuses to sign when an input's amount is unknown (fee cannot be checked) or nothing is yours. Pairing now also sends the Bitcoin public key (pair again; older pairings ask Kwallet once with a "Share public key" sheet).
- WalletConnect from a page (Cetus' WalletConnect option and other WC modals): a wallet deep link that carries a WalletConnect URI (`trust://wc?uri=…`, `intent://…`, `https://…?uri=wc:…`), a `wc:` link or window.open, and the modal's "Copy link" are paired with Hwallet's own WalletConnect wallet instead of leaving the app. Whether Cetus offers a WC v2 Sui session this way is not verified.

## Kwallet v3.98.15 · Hwallet v1.8.15 (2026-10-15, work in progress)

Working build for phone testing. Not tested on a device or emulator: static checks (`node --check` of every inline and injected script, a stubbed run of the Browser providers) and the CI build. The Monero changes are ready to test on a device, not confirmed.

### Monero (K↔H key-image link)
- Kwallet's sign page reports each stage it reaches (request read + size, route, Monero checks, record lookup, draw, ready, engine boot). If the sheet never draws, the 30 s watchdog error names the last stage instead of "could not open the signing screen".
- Any exception while opening a request is caught and shown on the sheet with the stage it happened in (it used to leave the sheet undrawn until the watchdog).
- The Monero engine (worker + WebAssembly) starts only after the sheet has drawn and `KwSign.ready()` fired; the multi-MB outputs are shape-checked cheaply before the first paint and fully checked after the tap.
- No manual Sync spent status: when a send fails because key images are unknown (monero_c builds only from outputs with a known key image: "No enotes available to spend"), Hwallet asks Kwallet for them right there, imports them and builds again. Every Monero send also returns key images for all outputs. Sync spent status stays as a repair tool.
- Output-order contract made explicit (HwXmrN.kt): Hwallet exports all outputs with their public keys in the same order; wallet2's import checks each signature against output i; the fallback now matches by output public key (1.8.14 matched by the output's index inside its transaction, which pointed at the wrong outputs). A partial export (only new outputs) is not possible while Kwallet signs in a throwaway wallet (wallet2 refuses an offset beyond the outputs it knows).
- The bundled monero_c v0.18.4.6-RC2 exports `MONERO_Wallet_importKeyImages`, but its file needs wallet2's chacha key derived through cn_slow_hash; the Trezor-JSON import (the same `wallet2::import_key_images` underneath) is kept.

### dApps
- Browser: Solana and Sui are ONE Wallet Standard wallet "Hwallet" (both chain and feature sets, `id` app.hwallet); one approval sheet connects both; Sui accounts carry the public key. Re-announce kept.
- Chains switched off in Chains are not announced to pages (EVM, Solana, Tron, Sui, Cardano, XRP); new tabs pick up a change.
- Cardano CIP-30: `getExtensions` / `supportedExtensions` and `enable({ extensions })`; CIP-95 is listed as unsupported until Kwallet can review governance signatures.
- Incognito: a wallet connection approved in an incognito tab is dropped when the tab closes.

### Design (Hwallet)
- Tab switcher header: "N tabs" on one line, icon buttons for new tab and incognito, Close all in the ⋮ menu, Done on the right.
- Saved inputs moved from Settings to Browser ⋮ › Browser settings › Saved inputs: Offer to save on top, then four rows (Passwords, Emails, Inputs, Addresses) with counts, each opening its list.
- Chains: one-line intro, normal row height and type, smaller icons; on a Monero-only wallet only Monero is active, the others are greyed out with "Monero seed: other chains need a 12/24-word wallet", and the EVM networks section is hidden.

## Kwallet v3.98.14 · Hwallet v1.8.14 (2026-10-15, work in progress)

Working build for phone testing. Not tested on a device or emulator: static checks (`node --check` of every inline and injected script, a stubbed run of the Browser providers) and the CI build. The bundled monero_c library was checked to export `MONERO_Wallet_importTrezorEncryptedKeyImagesJson` and `MONERO_Wallet_trustedDaemon`.

### Monero
- Sending works from Hwallet's view-only wallet: Sync spent status now imports Kwallet's real key images (with their signatures) into wallet2, through monero_c's encrypted key-image import (ChaCha20-Poly1305, a fresh key each time, node trusted only for the import). Every output's key image becomes known, so wallet2 can build a send (the "No enotes available to spend" error) and sees later spends while syncing. Needs Android 9+; older phones keep the previous check-and-freeze path. Run Sync spent status once after updating.
- A send that fails because key images are missing says to run Sync spent status.
- Sync spent status no longer leaves an invisible Kwallet window over Hwallet: Kwallet shows itself after 10 s if its sheet has not drawn, and after 30 s answers with an error Hwallet shows.
- Faster native sync: the scan is re-armed only after 90 s without a new block (was 45 s) and the node is switched only when it is actually failing; the wallet is saved every 5 min while syncing (was 90 s).

### Sui and dApps
- WalletConnect: `sui_getAccounts` (address + public key) and the same answer in the session properties, which Sui dApp Kit apps (Cetus and others) need before they show a wallet as connected.
- Kwallet sends the Sui public key with the pairing (pair again to send it); for older pairings Hwallet reads it once from the address's last transaction, or learns it from the first signature.
- Browser: the Sui wallet is announced again for apps whose wallet list mounts late, and through the legacy `navigator.wallets` queue.
- Kwallet's Sui signing sheet gets the same title as EVM / Solana / Tron (Swap, Send, Receive) from Hwallet's simulation; stablecoin amounts show their dollar value.

### Design
- Hwallet: Chains moved from Settings to a "Chains ›" row above the coin list on Home (every UI style).
- Kwallet Restore: two tiles (Choose backup file, Paste backup text), a file chip with name, size and remove, Paste from clipboard, the password field only for an encrypted backup, a preview card of the backup, and one confirmation: Hold to erase & restore.
- Kwallet Export and Backup: one short line each, "Repeat password" appears only once a password is typed.

## Kwallet v3.98.13 · Hwallet v1.8.13 (2026-10-15, work in progress)

Working build on the `wip-3.98.13` branch for phone testing. Not tested on a device or emulator: only static checks (`node --check` of every inline script and of the injected Browser scripts, an ESLint no-undef comparison against v3.98.12 / v1.8.12) and the CI build.

### Kwallet
- Bottom of the main screen (3.98.12 report): the empty band above the navigation bar and the cut-off last rows are gone. The page already ends above the bar and the bar is painted solid in the page colour; a second fixed strip of the same height sat inside the page (the page box contains its fixed children) and covered the bottom rows. It is removed, the solid bar stays (no transparent bar, nothing drawn behind it). The same extra band on the full-screen Settings page is removed too.
- Monero signing: a Monero engine that cannot start now shows its real reason on the sheet at once (it used to be swallowed, then Confirm waited for the 110 s limit), and an engine that stops while working fails every waiting request immediately.
- XRP Ledger: the signed transaction fields go back to Hwallet with the signature (for WalletConnect `xrpl_signTransaction`).
- Shorter Monero sheet texts.

### Hwallet
- Settings › Chains: an on/off switch per chain and per EVM network. Off means no request at all for it (balances, history, prices, nodes, explorers, WebSockets, the live service, WalletConnect, Browser wallet requests, Monero sync: the native engine closes and its service stops) and it disappears from every list and picker. Only Settings › Chains still lists it. Saved; turning it on resumes normally. Present in every UI style.
- WalletConnect: Sui (`sui:mainnet`: signPersonalMessage, signTransaction, signAndExecuteTransaction and the *TransactionBlock forms), Cardano (`cip34:1-764824073`: signTx, signData, submitTx, getBalance, getUtxos, getCollateral, getNetworkId, addresses), XRP Ledger (`xrpl:0`: xrpl_signTransaction with tx_json, autofill and submit) and Bitcoin (`bip122:000000000019d6689c085ae165831e93`: getAccountAddresses, sendTransfer). Signing goes through the same Kwallet sheets as the Browser's providers.
- Monero: a request to Kwallet that gets no sheet within 45 s ends with "Kwallet didn't answer. Open Kwallet once and try again" instead of waiting up to 5 minutes; once Kwallet's sheet is up, you have the normal time to approve.
- Monero (native engine): Sync spent status exports the outputs with wallet2's refresh paused (like a send) and always releases it, also on errors; a failed build / submit / export never leaves the wallet held.
- Monero: outputs whose key images Kwallet returned in Sync spent status are remembered as checked, so "received outputs are not checked for spends" clears (spent ones are still frozen; a view-only wallet2 cannot import key images without wallet2's encrypted key-image file).
- Browser: incognito tabs (tab switcher and menu). No history, no saved tab state, snapshot, thumbnail or site icon; on WebViews with profile support they run in their own profile (cookies, storage, cache) which is deleted when the last incognito tab closes. Marked in the address bar and the tab switcher.
- Browser: Settings › Saved inputs. Offers to save logins (password sealed with an Android Keystore AES-GCM key), emails, other form fields and pasted chain addresses; a "Saved" chip on a field opens Hwallet's own picker to fill it. View, copy, edit and delete by type (Passwords, Emails, Inputs, Addresses by chain). Never from incognito tabs.
- Shorter Monero texts.

### Known issues
- Nothing in this build was run on a device or emulator.
- WalletConnect Bitcoin: signMessage and signPsbt are not supported (Kwallet has no Bitcoin message or PSBT signer); sendTransfer has no memo and always returns change to your address. XRPL: xrpl_signTransactionFor (multi-sign) is not supported. Cardano: Kwallet signs with the payment key only.
- Incognito on a WebView without profile support (older than about version 123): cookies and site storage of the visited sites are deleted when the tab closes, but they were shared with normal tabs while it was open. Wallet connections approved in an incognito tab are kept in Connected sites.
- Saved inputs: the "Saved" chip and the save offer work on ordinary HTML forms; sites that build their own login widgets may not trigger them.

## Kwallet v3.98.12 · Hwallet v1.8.12 (2026-10-14, pre-release)

Pre-release for phone testing. Not tested on a device or emulator: only static checks (`node --check` of every inline script in both apps and of the JS modules, CSS brace balance and a full CSS parse of both apps), Node tests of Kwallet's new signers against the official libraries, mock dApps that drive Hwallet's injected providers with the dApp-side libraries, a scripted run of Kwallet's approval sheet in headless Chromium, and the CI build.

### Native Monero engine (Hwallet)
- Monero now syncs with monero_c (the wallet2 library Cake Wallet uses, LGPL-3.0) instead of the WebAssembly engine. In a desktop benchmark on the same node and wallet it scanned about 7 to 8 times faster (about 260 blocks/s instead of about 34) and reached the restore height about 6 times sooner.
- The scan runs in a foreground service with a progress notification and keeps going with the screen off until it reaches the chain tip. It resumes after a reboot and saves its progress every 90 seconds. The view key is sealed with the Android Keystore.
- The node traffic goes through Hwallet's own HTTPS client with a node list, failover and backoff (wallet2 only talks to a local relay).
- "Sync spent status" freezes outputs whose key images the node reports as spent (a view-only wallet2 cannot import key images on its own). Submitting a transaction signed by Kwallet imports all its key images.
- The WebAssembly engine stays as the fallback when the native library is missing (for example on an unsupported ABI).

### Connect Wallet for Sui, Cardano and the XRP Ledger
- Sui: Hwallet registers as a Wallet Standard wallet (connect, signPersonalMessage, signTransaction, signAndExecuteTransaction and the legacy *TransactionBlock calls, mainnet only). Hwallet builds and dry-runs the transaction with its node, Kwallet signs, Hwallet executes.
- Cardano: a CIP-30 wallet at `window.cardano.hwallet` (enable, getNetworkId, addresses, balance, UTxOs, collateral, signTx, signData, submitTx).
- XRP Ledger: Hwallet answers GemWallet's page protocol, so apps built on `@gemwallet/api` see it as GemWallet (address, network, public key, signMessage, signTransaction, submitTransaction, sendPayment, setTrustline). Hwallet fills in Fee, Sequence and LastLedgerSequence.
- Kwallet's approval sheet shows these requests like the others: the app and its address, the decoded transaction (Sui commands, Cardano outputs, XRP fields), the amounts, the network fee and your balance. When Kwallet cannot see what a smart contract will do (a Sui Move call with no simulation, Cardano script outputs, certificates or minting, an unknown XRP transaction type), it shows a blind-signing warning. Kwallet decodes every request itself and signs only what it decoded. It refuses a request from another account, a signData request for another address, and an XRP fee above 2 XRP.

### Known issues
- Nothing in this release was run on a device or emulator; the phone test decides when it becomes the latest release.
- Native Monero: building an unsigned transaction with a funded wallet was not tested (no funds). The spent status of a view-only wallet comes from the node's key image check ("Sync spent status").
- Cardano: Kwallet signs with the payment key only (no stake-key witness for certificates or reward withdrawals), and it sees where ADA goes but not the value of the inputs it spends.
- Sui: amounts beyond plain SUI transfers come from Hwallet's dry run. Sui's public fullnode JSON-RPC is deprecated, so Hwallet uses https://sui-rpc.publicnode.com.
- XRP Ledger: only apps that use GemWallet's protocol are supported (there is no common injected XRPL standard).

## Kwallet v3.98.11 · Hwallet v1.8.11 (2026-10-14, pre-release)

Pre-release for phone testing. Not tested on a device or emulator by the build: only static checks (`node --check` of every inline script in both apps and of the translation engine, the Monero engine and the browser provider, CSS brace balance and a full CSS parse of both apps), a scripted run of Hwallet's pull-to-refresh state machine in all three pull styles, and the CI build.

### Pull to refresh (every style)
- Reported: the refresh indicator could stay on screen and carry over to other tabs, pages and sheets. Cause: the indicator lives above the pages and its closing animation runs on animation frames, which a paused or hidden WebView never delivers, so the close never finished; nothing reset the pull when the page changed either.
- The pull now belongs to the page it started on (screen + coin / network / token). Changing tab, page or route, opening a sheet, or hiding the app drops any pull or spin, removes the indicator and puts the old page's content back (no offset, normal overscroll). A late finish of an old refresh can no longer touch the new page.
- A pull starts only when the page is scrolled to the top and the finger went down on the page itself: not on a sheet, the browser, a text field or a sideways scroller.
- A cancelled gesture (touch or pointer cancel) never refreshes and always returns to rest.
- The refresh closes when it settles; if the close animation cannot run, the indicator is removed after 1.5 s; whatever happens, nothing spins longer than 12 s.
- One refresh at a time: a pull or the refresh button while a refresh is still running waits for that one instead of starting a second.

### One typography and one row in every style
- Wallet list, a network's token list, the networks list and search results: OG, Material UI, Material You and MIUIX now use the same font, sizes, weights and letter spacing for row names, prices, changes and balances (name and balance 16 px medium, price / change and fiat value 14 px, no extra letter spacing). OG's wider price line is gone, so the price and change stay on one line and every row has the same height. The balance column gives way first when space is tight; nothing wraps.

### Browser menu (MIUIX)
- The six toolbar tiles (back, forward, reload, new tab, bookmark, desktop view) are six equal tiles across the full width, as in the other styles (a menu width limit from MIUIX's pop-up menus was cutting them short).
- Rows in the menu and in every sheet size to their content with the shared gap between them; they can never overlap.

### Known issues
- Nothing in this release was run on a device or emulator before publishing; the phone test decides when it becomes the latest release.
- A pull while an earlier refresh (started on another page) is still running waits for that refresh rather than starting a new one for the current page.
- Kwallet 3.98.11 only changes its version number.

## Kwallet v3.98.10 · Hwallet v1.8.10 (2026-10-14, pre-release)

Pre-release for phone testing. Not tested on a device or emulator by the build: only static checks (`node --check` of every inline script in both apps and of the translation engine, the Monero engine and the browser provider, CSS brace balance and a full CSS parse of both apps), a scripted run of Hwallet's dApp request router in Node with stubbed Android bridges, and the CI build.

### dApps in the Hwallet browser (EVM, Solana, Tron)
- Reported: since 1.8.9, dApps show an error instead of opening Kwallet. Hwallet's request router was run against the shipped page with a paired wallet and a connected site: connect, switch chain, `eth_signTypedData_v4` (Permit2), `personal_sign`, `eth_sendTransaction`, `solana_signMessage`, `solana_signTransaction`, `tron_signMessage` and `tron_signTransaction` all open Kwallet and hand its answer back with the page's own request id. The cause could not be reproduced without the phone, so the 1.8.9 changes that can stop it on a real phone are fixed:
  - Full browser storage: a full localStorage made a new site connection fail to save, so every signing request was refused with "Connect to this site first" before Kwallet opened, on every chain. Hwallet now drops its rebuildable caches (decoded method names and contract names added in 1.8.9, browsing history) and saves again; the connected-sites list is also kept in memory, and site icons stored with it are https links only (an inline image could take up to 90 KB per site).
  - Shared node reads (1.8.9): filters, subscriptions, nonce, account and signing calls are never shared between callers any more, and a shared answer is copied for each caller.
  - Translation never rewrites a page by itself when the site is connected to the wallet (React apps can break when their text changes under them). Tap Translate to translate such a page.

### Round action buttons in every style
- Send, Receive, Swap, Stake and Trust line are full circles of one size (54 px, 22 px icon) on Home, the coin pages and everywhere else, in OG, Material UI, Material You and MIUIX, light and dark. Only the colour changes with the style (Material UI no longer draws rounded squares).

### The same cards and spacing everywhere
- OG gets the same row cards as the other styles for the Wallet list, a network's token list, the networks list and search results (Activity, Settings and the coin info card already had them).
- New shared spacing tokens used by every container: one 8 px gap between cards, tiles and panels, 2 px between rows of one card, the 16 px screen gutter (also the side padding of every sheet and picker), 14 px tile corners. Grids of tiles (browser menu, bookmarks, tab switcher) keep equal widths whatever the labels say. Sheets start closer to their handle, section titles take less height.

### Mobile data: Adaptive
- Settings › Network has **Adaptive** next to the speed limit and Unlimited. With Adaptive on there is no fixed speed cap; on mobile or metered data Hwallet refreshes half as often, and on a slow link (2G/3G, under about 1.5 Mbit/s, or Data Saver) a quarter as often, loads no new remote token icons and does not start the translation model download. Wi-Fi is never adapted. The Network row shows "Mobile data: Adaptive". Turning it off brings back the speed you had set.

### Browser
- The orange **Data limit** pill in the address bar is a switch: tap to turn the limit off (the pill stays, dimmed, reading "Data limit off"), tap again to turn your speed back on. It looks the same as before.

### Monero
- The Monero coin page has a pause / resume button at the top right of the header. Pausing stops the scan right away (it saves where it got to) and also holds any rescan; the status line reads "Paused · N blocks left". Resume continues from the saved height, never from the start. The pause is kept per wallet across restarts. Balances and sending keep working from what was already scanned.

### Known issues
- Nothing in this release was run on a device or emulator before publishing; the phone test decides when it becomes the latest release.
- The dApp error from 1.8.9 was not reproduced: if it still happens, the exact message the dApp shows (or a screenshot) is what pins it down. Possible remaining causes that need the phone: the Android WebView back-forward cache (switched on in 1.8.9 where offered) and desktop view becoming the default for new tabs once it was used.
- "Paused · N blocks left" counts from the last known chain height; right after a restart while paused it uses an estimate from the date until the node is asked again (on resume).
- Adaptive reads connection speed from the WebView's network information; on WebViews that do not report it, only the mobile/metered state is used.
- Kwallet 3.98.10 only changes its version number; its signing sheet is the same as 3.98.9.

## Kwallet v3.98.9 · Hwallet v1.8.9 (2026-10-14, pre-release)

Pre-release for phone testing. Not tested on a device or emulator by the build: only static checks (`node --check` of every inline script in both apps and of the translation and Monero engines, CSS brace balance and a full CSS parse of both apps) and the CI build.

### Activity: no Pending without a signed, broadcast transaction
- A local Activity entry is written only after Kwallet signed the transaction and a node returned its transaction id, for every source (Send, Stake, Swap, dApps in the browser, WalletConnect) and every chain. Cancel, reject in Kwallet, swipe-down, an expired request or a dApp that gives up writes nothing, anywhere (Activity, the coin's history, notifications, the background service).
- An EVM broadcast counts only when the node answers with a real 32-byte transaction id ("already known" = this exact signed transaction is in the node's pool).
- One-time cleanup on upgrade (and a cheap check at every start): Pending rows without a transaction id are deleted in every paired wallet, in the activity cache on disk and in the notification shade. Rows that do have an id but that the chain never saw (a node accepted and dropped it, or another transaction with the same nonce replaced it) are removed after 10 minutes, once two checks at least 90 s apart both find nothing (Bitcoin: after 72 hours).
- dApp rows show the network's own coin (BNB on BNB Chain, POL on Polygon) and the decoded action ("Approve USDT", "Swap USDT → BNB", the contract method, else "App interaction"), with the network and the app's full name underneath.
- Audit: Solana, Tron, Bitcoin and Monero already wrote their entry only after a broadcast returned an id; they now go through the same check (a Monero send without an id leaves the row to the Monero engine's own pool listing).

### Kwallet's dApp sheet opens complete
- Hwallet shows "Preparing…" while the preview (balance changes, decoded method, contract and token names) runs in parallel with the fee, gas and nonce reads; it now gets up to 8 s (was 5 s). If it answers later, the result is pushed to Kwallet's open sheet over the live feed and the sheet redraws with it (no more cancel-and-retry). The site icon loads alongside instead of before.
- Decoded method names and verified contract names are kept on the phone (not just for the session), so a second attempt is never needed; token metadata was already kept.
- Identical read requests in flight at the same moment share one network call.

### Hwallet browser
- Translation starts on tap for the whole page: text in and just below the viewport first, then the rest in batches of up to 150 strings, without scrolling. New content is collected as it appears and translated in batches. The engine and the language model start loading as soon as a foreign page is seen (not while the mobile-data limit is on), stay in memory between pages, and the next pages of a tab you translated are translated by themselves. Translations are cached per page (URL + language pair + text hash) on the phone, so Back and revisits are translated at once.
- Page memory: the last 3 tabs stay live; older tabs hibernate and come back showing their last picture at once while the page restores. Back / forward use the WebView's back-forward cache where the phone's WebView supports it. With the mobile-data limit on, sub-resources go through an HTTP disk cache (64 MB) with conditional revalidation, so unchanged files are not downloaded again.
- Storage stays bounded: saved pages of the least recently used tabs are dropped first above 160 MB (their history stays), at most 400 site icons, 300 translated pages; file reads and writes run on one background thread (tab state is no longer read on the UI thread); history is written when the page is idle and keeps the newest 400 pages. Browser settings shows saved tabs, cache, site icons and translations, with "Clear cached pages" next to Clear all.
- Desktop view works with any user agent: the tab gets the chosen user agent if it is a desktop one (preset or Custom), otherwise Windows Chrome, set through the WebView's user agent, with a 1024 px desktop viewport (wide viewport + overview mode + the page's meta viewport rewritten at document start), and navigator.platform / maxTouchPoints / userAgentData to match (a Custom string is read for its platform and browser). It is kept per tab (also through hibernation and restarts), becomes the default for new tabs, and the menu button shows the tab's real state. Choosing a desktop user agent in Settings turns desktop view on for the tabs; a phone one turns it off.

### One layout for every style
- Shared layout tokens: every page, card and list has the same 16 px side margin, row padding and 40 px icon column in OG, Material UI (and Material You colours) and MIUIX, light and dark; the styles change colour, corner radius and shadow only. MIUIX no longer pulls cards 4 px wider; Settings cards and the Support card line up with the Wallet list.
- Material and MIUIX: the Wallet list, a network's token list, the networks list and search results get the same row cards as Activity (card surface, 2 px gap, the style's corners). OG keeps its flat list.
- The price / change line wraps before the balance column takes its space (no more "-3.1…"); the Monero icon is the same size and in the same column as the others.

### Known issues
- Nothing in this release was run on a device or emulator before publishing; the user's phone test decides when it becomes the latest release.
- The dropped-transaction sweep needs the network: a row whose id the chain never saw stays Pending until two checks find nothing (10+ minutes after sending). A public node that loses track of a real transaction for two checks would remove its local row early; the confirmed transaction still appears from the chain's history.
- Back-forward cache: switched on through androidx.webkit when the installed Android System WebView offers it; on WebViews without it, Back reloads from the HTTP cache as before.
- Hibernated tabs show their last picture only if one was saved (the tab switcher saves it); a tab without one restores as before.
- Translation warm-up downloads the engine and the language model (up to about 45 MB) when a foreign page is first seen, before any tap, unless the mobile-data limit is on.
- Desktop view: sites that detect touch support or screen size in script may still serve their mobile layout.
- Kwallet's sheet takes a late preview only from Kwallet 3.98.9; older Kwallet builds ignore it (they open as before).
- Partly done from the performance request: Hwallet already rendered balances, tokens, prices and Activity from its caches on open and kept HTTP connections alive and RPC calls batched; this release adds in-flight de-duplication and persistent decode caches, but does not yet gate balance and history refreshes on per-chain block or latest-signature checks, nor move those caches to IndexedDB.

## Kwallet v3.98.8 · Hwallet v1.8.8 (2026-10-14, pre-release)

Pre-release for phone testing. Not tested on a device or emulator by the build: only static checks (inline JS syntax of both apps, CSS brace balance and parse, `node --check` of the Monero and translation engines and the browser provider) and the CI build.

### Monero (snappy)
- The scan watches only what Hwallet hands out: account 0, the primary address and subaddresses #1 and #2 (lookahead 3), down from 10 accounts x 200+ subaddresses. If Kwallet paired a higher subaddress, the scan grows to cover it (Settings > Monero > Wallet > Addresses scanned). This is a one-time rescan from the restore height; the old, larger caches are deleted.
- Receive makes new addresses only up to what the scan watches.
- Fast node path: the Monero worker now talks to Hwallet's native HTTP client directly (OkHttp, keep-alive), instead of every block request going worker -> page -> native. On a WebView that does not route worker requests to the app it falls back to the old path by itself.
- Sending shows each phase ("Building · fetching decoys 3", "Packing for Kwallet") and has a Cancel button under Continue while Hwallet builds; Cancel restarts the engine from its saved cache, so the scan goes on. A build that runs too long stops with a plain error. After a cancel or an error the scan resumes right away (no 4-minute hold).

### Hwallet <-> Kwallet connection
- Large requests (a Monero unsigned set plus outputs, often far over 400 kB) and large answers (a signed set) now travel as files through a private content provider in Hwallet with a one-shot URI grant to Kwallet only. The old ~400 kB Intent cap is gone, and nothing is ever dropped silently.
- If Kwallet's sheet does not come up within 12 s, Hwallet says so and gives the screen back (it used to sit hidden behind a sheet that never came).
- A large request to a Kwallet older than 3.98.8 gets a one-line fix ("Update Kwallet to 3.98.8 or later"). Small requests still work with older Kwallet builds, and older Hwallet builds still work with this Kwallet.

### Sheets
- Swipe down to dismiss works on every bottom sheet in both apps (Kwallet's signer, Hwallet's Send, dApp, fee, info and settings sheets): drag the handle, header or content at the top; past 25% of its height or a quick flick = Cancel / close, otherwise it springs back. A sheet that is busy (signing, sending) springs back instead.
- Send: the Network row is label left, chain icon + name right-aligned, like the Network fee row.
- Network fee: one short label (Slow / Normal / Fast / Custom). "Custom · saved", "Custom · raised to network min" and the "Live · updated just now" line are gone; live data is a tiny dot. The saved-fee behaviour is unchanged.

### Settings (OG style)
- Every switch in OG has its real size again (it collapsed to a thin bar, with the knob outside it), in both apps and every theme.
- OG Settings pages in Hwallet have a side margin, so the cards are inset with their rounded corners.

### Hwallet browser
- Toolbar: [address bar][Translate][Tabs][menu] with equal gaps; the menu button is no longer clipped.
- Translation, on the phone, with no popups: the Translate icon flashes bright grey on a page that is not in your language. Tap = the page is translated in place (spinner while the model downloads or works) and new content keeps being translated; tap again = the original comes back and the icon flashes again. Long-press = the menu with the options inline: Translate to (language), Always translate this language, Never translate this site. "Translate pages to" is also in Browser settings and Settings > Browser (default: the phone's language).
- The translator is a fork of Mozilla's Firefox Translations engine (Bergamot, MPL-2.0) running in a worker. The engine (about 5 MB) and one model per language pair (about 15-40 MB; pairs without English use two) are downloaded once from Mozilla's model registry, checked against their SHA-256 and cached. Page text never leaves the phone.
- Menu: one nav row across the full width (back, forward, reload, new tab, bookmark star, desktop view), Bookmarks above History and Connected sites, translation options inline.
- Bookmarks: star the page in the menu; the Bookmarks list opens or deletes them, and the newest show on the new-tab page. Kept by Hwallet itself.
- Desktop view: one tap per tab (Windows Chrome user agent and hints, then reload), highlighted while on.
- Compact browser sheets (menu, settings, user agent, bookmarks, connected sites): smaller handle gap, 44 px rows, smaller icon tiles, short 12.5 px footers; the switches have a real track width with the knob inside.

### Known issues
- Nothing in this release was run on a device or emulator before publishing; the user's phone test decides when it becomes the latest release.
- Translation: the language is detected from the page's own text (script ranges and common words for about 20 Latin-script languages) and its `lang` attribute; Firefox's fastText detector is not bundled, so short or mixed-language pages may be missed or misread. Only text is translated (not images, `placeholder` / `title` attributes, or text inside iframes). The first use of a language pair needs a download (engine about 5 MB + model 15-40 MB) over the network; after that it works offline. Big pages are translated in batches, so long pages fill in over a few seconds.
- The translation engine and its models come from Mozilla's public registry at first use; if Mozilla moves those files, the download fails and the icon simply returns to flashing (no popup by design).
- Monero fast node path: it depends on the Android System WebView passing worker requests to the app; where it does not, the old (slower) path is used automatically.
- Monero: the "Waiting for Kwallet" state does not survive Hwallet being killed while Kwallet's sheet is open; the request is then cancelled, never sent.
- Desktop view uses one desktop preset (Windows Chrome); the global user agent setting still applies to the other tabs.

## Kwallet v3.98.7 · Hwallet v1.8.7 (2026-10-14)

### Fees
- A saved custom network fee now sticks on EVM dApp and WalletConnect requests, as it already did on Solana. It is applied over the app's own fee until you change it; only the fee fields change (never gas limit, nonce, amount or data).
- If the network's base fee is above your saved fee, Kwallet raises it to the live minimum (+12%) instead of dropping it, and the sheet says so ("Custom · raised to network min"). Otherwise it reads "Custom · saved".

### Popups and navigation bar
- Every signing popup (Hwallet connect/sign/fee sheets and the Kwallet signer) uses one fixed Jupiter-style graphite sheet on every theme, with a stronger dim behind it.
- The Kwallet signer's colour now runs under the Android navigation bar: the wallet behind no longer shows below Cancel / Confirm.
- Kwallet and Hwallet: the navigation-bar area is a solid strip in the app's own colour; pages no longer scroll behind it.

### Hwallet browser
- Ad blocker using uBlock Origin's default lists (uBlock filters, privacy, badware, quick fixes, unbreak, EasyList, EasyPrivacy, Peter Lowe, URLhaus): network filters and element hiding, applied natively. Lists download in the background and refresh weekly. Wallet, RPC and dApp services are always allowed.
- Per-site shield with a blocked count, in the browser menu.
- User-agent manager: Off (Hwallet's own) or presets for current phones, tablets and desktops (Chrome, Safari, Firefox, Edge, Opera, Samsung Internet, Brave, DuckDuckGo) and Custom. navigator.platform / userAgentData match the chosen one.
- Both switches in the browser menu, Browser settings and Settings > Browser.
- Compact browser menu, tab switcher and address bar.
- Android Back goes back inside single-page dApps (pushState routes) before leaving the browser.

### Monero
- Sending no longer freezes: the scan is paused (and waited for) before the transaction is built, then Kwallet opens. The button shows each step.
- The scan saves its progress every ~2,000 blocks, so leaving the app no longer restarts it from the restore height.
- Rescan really rescans from the restore height (it used to keep the old cache).
- Settings > Monero cleaned up: Node, Wallet (restore height, rescan), Scan (status, spent status), Pairing.

## Kwallet v3.98.6 + Hwallet v1.8.6

Quick fix for Hwallet Settings from the phone test of v3.98.5. Kwallet only changes its version number.

### Fixed
- **Settings pages clear the status bar.** Every Settings sub-page header now sits below the Android status bar (the same top inset as the main Settings page), so the back arrow no longer collides with the clock. Long titles such as "Notifications & background" use a slightly smaller size and end with an ellipsis instead of running under the system icons.
- **Compact Settings.** Tighter rows and padding on the main Settings page and on every sub-page.
- **Wallets & pairing values.** "Pair with Kwallet" shows a short "Add or refresh" that fits, and Wallets shows "1 wallet · name", the same as on the main page.
- **Support card.** The previous Support Hwallet card is back (chain badge, short address, copy icon), now at the foot of Settings and stretched to fill the space down to the bottom bar, with readable dark-on-lime text. Tapping a row copies that chain's donation address with a toast such as "ETH address copied" and a brief tick on the copy icon.

## Kwallet v3.98.5 + Hwallet v1.8.5

Hwallet Swap fixes from the phone test of v3.98.4.

### Fixed
- **Swap 25% / 50% / Max.** On a gas coin they now work on the spendable balance: the balance minus the native reserve the chain really needs (network fee, the Solana rent minimum, the temporary wSOL account and a new token account on a same-chain Solana swap, the XRP base and owner reserve). Max is all of the spendable amount (it used to keep a flat 0.01 SOL, so a 0.01008 SOL balance gave 0.00008). On a token they use the whole token balance, and Hwallet says when the native coin can't pay the fee. All in exact base units.
- **Swap errors are short and friendly.** No HTTP codes or JSON: a compact amber note such as "Minimum for this route is $1,000" (with a one-tap Set amount), "No route for this pair right now", "This pair isn't supported", "Swap providers are busy" or "Can't reach the swap providers" (with Try again).

## Kwallet v3.98.4 + Hwallet v1.8.4

Monero pairing fix, a friendlier pairing flow, fintech-style Hwallet Settings, one shared send check for both apps, and Monero subaddress coverage.

### Fixed
- **Monero after pairing from Hwallet ("needs view key").** Pairing started from Hwallet opens Kwallet's sign screen, where the vault is closed, so Kwallet sent an empty view key. Kwallet's public pairing index now carries the view-only Monero data (private view key, primary address, restore height), is rewritten once the next time Kwallet is opened, and old pairings are filled in from the seed. If the index is still old, the pair screen fills it from the signing copy (or after one PIN when Require PIN to sign is on). Hwallet shows **Pair again to enable Monero** on the Monero page, its home row and in Settings > Monero when a wallet has a Monero address but no view key.
- **Solana token Max blocked by Kwallet ("Not enough to cover the network fee", amount "—").** A failed token-accounts request was counted as a token balance of 0 and sent to Kwallet, while Hwallet had checked the cached home balance. The request is now retried and a failed one never becomes 0; a token Max goes to Kwallet as the exact raw token balance; the fee and account rent are checked against SOL only.
- **One send check for both apps (KwSend).** Hwallet and Kwallet now run the same code on the same balances for every chain (EVM, Solana, Tron, Bitcoin, Sui, XRP, Cardano), so their messages always agree. Solana: an account can end at 0 or at least 0.00089088 SOL (rent minimum), never in between. Bitcoin: the recipient output is sized by its address type in Hwallet too (Max and fee match Kwallet's planner). XRP tokens: an unknown balance stays unknown instead of 0.
- **Monero subaddresses.** Hwallet's view-only wallet now covers Kwallet's addresses: 10 accounts and max(200, paired index + 100) subaddresses per account (wallet2 extends past any used index), with balances and history across all of them. The cache is rebuilt once, from the restore height (never from genesis). Sends use account 0, or the first account that can pay.
- **Send sheet:** the token icon and chain badge are a fixed-width block with a 12dp gap after the badge (no more text touching the icon); the amount, USD / Max pills and To card share the card's 16dp edge; **Save to address book** shows only for a valid address that isn't saved yet (never for your own address).

### Changed
- **Pairing, seamless.** One button, **Pair with Kwallet**, with a 3-step explainer (Open Kwallet · Choose a wallet · Done) and a success sheet listing what came across (chains, Monero ready / scanning). Pairing a wallet that is already in Hwallet updates it (never a duplicate); another subaddress of the same Monero wallet joins its wallet. Friendly errors with one fix button: Install Kwallet, Show me how (no seed paired in Kwallet), Try again (cancelled / timed out). Pasting a pairing code and watch-only addresses are under **Other ways**. Kwallet's pair screen explains the steps when no seed is paired.
- **Hwallet Settings, fintech style.** The main page is one row per category with a one-line summary: Wallets & pairing · Appearance · Sending & fees · Network · Monero · Security & privacy · Notifications & background · About, plus a slim Support card. Each row opens its own page with a slide-in (Android back slides out). Kwallet's Settings are unchanged.
- **Kwallet signer header for Hwallet's own Send / Swap:** no "Hwallet" app row any more, only the Kwallet chip (top-left), the chain chip (top-right) and a small grey "Request from Hwallet" line. dApps and WalletConnect keep the app row with icon and link.
- **Monero node sheet:** compact rows in one card with a radio, live latency and a status dot (green = in use and answering, including the node Automatic is using; grey = idle; red = unreachable). A custom node that answers is saved as its own row (with remove), selected, and joins Automatic's fallback pool; **Use this node** is a full-width pill.
- **Monero speed:** the fastest default node is picked by a quick latency check; the scan resumes from the saved cache at once and shows blocks per second and an ETA.
- **Monero page:** lists every address of the wallet (label, short address, balance, used).

## Kwallet v3.98.3 + Hwallet v1.8.3

Polish from the phone test of v3.98.2 (its layout stays as it was).

### Changed
- **One tap on Confirm signs.** Hold to confirm is gone from every operation: send, swap, approve, sign message, dApp, WalletConnect, Monero and key images. The button still waits a moment after any change on the sheet, and Settings > Hwallet > Require PIN to sign still adds one PIN or biometric check.
- **Balance-change amounts flush right.** In every sign sheet (dApp, Send, WalletConnect, Monero) of both apps, the amount and its $ value sit right-aligned at the card edge, and every row has the same height.
- **Rent is a row in the card.** When a transaction opens an account, a "Rent" row with the SOL icon sits right under the first SOL row, with the red amount and its $ value (tap it to read what rent is). The separate grey rent line is gone. An account closed shows a green "Rent back" row.
- **Send: no stacked sheets.** While Kwallet's signer is up, Hwallet's Send sheet is hidden behind it (no second handle); it comes back as it was if you cancel.
- **Send: no "First time sending to this address" banner.** "Save to address book" stays.
- **Send: tidier To field.** Contacts, Paste and Scan are three matching 40dp round tonal buttons with outline icons and even spacing.
- **Dialogs:** text centred in every dialog button, 24dp side padding for dialog content. Saved fee speeds shows proper network names (BNB Chain, Ethereum, Base and so on).

### Fixed
- **QR scanner with the camera off:** the scanner is now a full opaque screen (before, the Send sheet showed through it). The camera-off message sits in the middle with an Allow camera button (or Open settings when Android will not ask again), Choose photo and Paste. X goes straight back to the Send sheet, and the camera starts by itself when you come back from the app settings with Camera allowed.

## Kwallet v3.98.2 + Hwallet v1.8.2

A calmer, denser look across both apps, after the phone test of v3.98.1.

### Changed
- **Jupiter buttons everywhere.** Every Confirm / Cancel pair (dApp approvals, connect, the Send review, Pair, Monero, Kwallet's PIN sheet, Hwallet's own sheets and dialogs) is two equal full-round 48dp pills side by side, Cancel left and Confirm right, in every style (OG, Material UI, Miuix) and theme: Confirm solid #ACF47D, Cancel solid red, both with the dark button text #07090E. Single primary buttons (Continue, Swap, Use this fee) take the same green pill. Hold to sign is unchanged.
- **Compact dApp and Send sheets like Jupiter's confirm sheet:** a small header (app icon, name, link on one line), a one-line grey disclaimer, one grouped balance-change card (token rows about 56dp, red out / green in, $ under the amount), the network fee and wallet cards (short address in grey on the right), a small grey "Only confirm if you trust this website", then the buttons. The big You send / You receive hero cards are gone. The #60 header (Kwallet chip top-left, chain chip top-right) stays.
- **Inter, bundled offline** (SIL Open Font License 1.1, credited in NOTICE and in Hwallet's licenses): the whole of Hwallet, and Kwallet's sign sheets, Settings and dialogs. Tabular numbers for amounts; titles 17-18sp semibold, body 15sp, secondary 13sp grey. Tiny amounts use subscript zeros like Jupiter (0.0₅6 SOL, $0.0₃647) instead of "< $0.01".
- **Settings, single-line.** Rows are about 52dp with the value on the right; subtitles only where they carry state (rows with a switch or a picker show none). Hwallet is regrouped into Wallets (wallets, pair, WalletConnect) · Appearance · Sending & fees (saved fee speeds, simulate) · Network (data limit, API keys, Monero node / scan start / sync spent / rescan) · Security · Background & alerts · About; no header repeats its only row (Kwallet's UI style moved into Appearance). New **Saved fee speeds** row: see the speed kept for each chain, reset one or all. The Support card is slimmer.
- **Same density in both apps:** 16dp cards, 16dp sides, 52-56dp rows, solid cards with no gradients.
- **Chain selector follows the app's accent** in every style and theme: Kwallet lime (dark text) in OG and Miuix, the Material You wallpaper colour in Material UI (and with Kwallet's own Material You switch); Hwallet's chain chips navy. The restored rectangle shape is kept.
- **Hwallet Browser, compact:** slimmer address bar, a tighter Most visited grid (smaller tiles, one-line labels), History and Connected sites as compact rows right under it, then Popular apps and a short grey note.
- **Most visited is yours to arrange:** long-press a tile, drag to reorder (the order is saved), tap the small red x to remove a site (it stays removed); tap outside or Done to finish.

## Kwallet v3.98.1 + Hwallet v1.8.1

Sheets, buttons and fees from the phone test of v3.98.

### Changed
- **Primary buttons in the style's own colour.** Confirm, Continue, Send, Approve, Use this fee and Unlock are filled with the selected style's primary: OG = Kwallet lime / Hwallet navy, Material UI = Material You (the wallpaper's primary on Android 12+, the brand colour before that), Miuix = Miuix primary (#3482FF light, #277AF7 dark). Disabled is the same colour at 38%, never grey.
- **One sheet width in every style.** Every bottom sheet (Send, fee picker, dApp, connect, Kwallet's sign sheet, Hwallet's own sheets) uses the same 16dp side inset and 600dp maximum in OG, Material UI and Miuix (Miuix no longer adds 24dp on top, which squeezed Send). Row labels are never cut ("Network fee" stays whole) and values stay on one line, with the $ value on the line under it.
- **Fee speed remembered per chain.** The speed you pick (Slow / Normal / Fast / Fastest / Custom, EVM per network) is kept and used in Send, Swap and every dApp and WalletConnect approval until you change it, in Hwallet or in Kwallet's sheet (Kwallet reports the pick back). dApp sheets start on it; "Site suggested" (the app's own fee) stays a choice and is used when nothing faster was saved.
- **Account rent as one small grey line** under the amounts ("+0.0015 SOL rent · refundable", "one-time", "rent back"), with an info icon that opens a short sheet on what rent is. No red, no full-width banner. Sending a token to an address without its token account shows "+… SOL rent · opens the recipient's token account" the same way.
- **No expiry countdown bar.** A request still expires at the same fixed deadline; Confirm turns off and the sheet closes on its own with a short "Request expired" toast.
- **Jupiter-style recap** on Kwallet's dApp approvals and on the Send review: Kwallet chip top-left and chain chip top-right (#60), the app header, a hero You send card, an arrow and a You receive card, compact rows (rate, route, minimum received, network fee with its speed), the wallet row, then one full-width Confirm (hold to sign, as before) with Cancel as a text button under it.
- **Kwallet's chain switcher is the pre-3.97.4 one again** in OG, Material UI and Miuix, light and dark: a rounded rectangle track with its 1px border and the selected chain as a filled rectangle behind a bold label (never a pill or circle), in the style's colour. The style skin no longer rewrites its corners or font. The Gen / Undo / Redo / Copy / Clear row is unchanged.

## Kwallet v3.98 + Hwallet v1.8

Full Monero.

### New
- **Monero balance and history from a view-key scan on the phone.** Hwallet runs the Monero engine (monero-ts, which is the Monero Project's wallet2 compiled to WebAssembly) in a background Web Worker, so the screen never blocks. The scan starts at the wallet's restore height, saves its block/output cache on the phone (IndexedDB) and continues incrementally from where it stopped; the coin page shows a progress bar with the percentage and blocks left. It keeps scanning while Hwallet's always-on service keeps the app running. Nothing about the wallet is uploaded: the node only serves blocks, the view key stays on the phone.
- **Restore height**: Kwallet sends it at pairing when the seed carries a birthday (Polyseed), otherwise Hwallet asks once ("This wallet is new", a date, a block height, or scan everything). Editable in Settings > Monero > Scan start.
- **Receive**: the main (or paired) address plus subaddresses, with a **New address** button, QR and copy; addresses that already received are marked.
- **Send XMR** with the same Send screen and the same Kwallet sign sheet as every other chain: Hwallet picks the outputs and fetches decoys from the node, builds the **unsigned** transaction at the fee level you choose (Slow / Normal / Fast, live from the node's fee estimate), Kwallet derives the spend key from the paired seed only after the hold (and PIN if on), imports the outputs, makes their key images, reads what the set really does and refuses unless it pays exactly the address and amount shown with a fee no higher than shown, then signs (CLSAG + Bulletproofs+). Hwallet broadcasts it and imports the key images that came back, so the balance is right after the spend. Max sends sweep the unlocked balance.
- **Activity rows for Monero**, received and sent, with "n/10 confirmations" until the funds unlock; tap for the detail sheet (fee, date, explorer).
- **Incoming XMR notification** on the phone (found by the local scan), or a toast while Hwallet is open.
- **Settings > Monero**: node (automatic over four public HTTPS nodes with fallback, or your own `https://host:port`, checked before it is saved), scan start, **Sync spent status** (Kwallet signs key images for every received output, so spends made elsewhere show up), and **Rescan**.
- Kwallet pairing now carries the Monero primary address (a subaddress pairing scans the whole wallet) and the restore height when known; older pairings keep working.

### Changed
- Kwallet stays offline: the Monero engine in Kwallet has no network access at all (Kwallet still has no INTERNET permission); its worker is closed after every request.
- Hwallet's node traffic for Monero goes through Hwallet's native HTTP client (same data-speed limit as the rest of the app).
- Sign requests between Hwallet and Kwallet may now carry up to 400 kB (a Monero unsigned set with its outputs).

## Kwallet v3.97.5 + Hwallet v1.7.5

UI styles redone from the phone test of v3.97.4.

### Changed
- **One shared skin for both apps.** Hwallet and Kwallet compile the same style file, so Material UI and Miuix render with the same dimensions in both (page side margin, card corners, row heights, type, switches, buttons, sheets, dialogs, snackbars).
- **System wide.** Besides the hand-tuned components, every corner and every interface font in both apps (every screen, list, card, chip, menu, sheet, dialog, toast, picker, scanner, browser chrome, swap, send, receive, Activity, token pages, setup and pairing, Kwallet's generator, lock and sign sheet) now follows the selected style; nothing keeps the OG shape when Material UI or Miuix is on. Monospace (addresses, keys, seeds) and the brand marks stay as they are.
- **Carbon-copy values.** Miuix: Card 16dp, 12dp from the screen edge; BasicComponent 56dp / 16dp, 17sp Medium title, 14sp summary; SmallTitle 14sp Bold 28x8dp; Switch 49x28; Button 16dp / 16x13dp / 17sp; TextField 16dp with a 2dp focus border; SuperDialog 32dp at the bottom, 12dp outside, 24dp inside, centred 18sp title; SuperBottomSheet 28dp, 640dp max, 45x4 handle; NavigationBar 64dp, 26dp icons, bold label, no indicator; TopAppBar 32sp large title, 20sp Medium collapsed; Snackbar 16dp; TabRow contour 45dp; press overlay; smooth corners where the WebView supports `corner-shape`. Material 3: list items 56/72dp, cards 12dp, NavigationBar 80dp with the 64x32 indicator centred on the icon, top app bar titles 22/28 and 28/36, Switch 52x32, buttons 40dp full round (56dp for main actions), outlined fields 56dp/4dp, dialogs 28dp, sheets 28dp with the 32x4 handle, snackbar 4dp, menus 4dp, 10% state layer.

### Fixed
- Hwallet's Settings cards were narrower than Kwallet's under Miuix (an extra 12dp inset): both now sit 12dp (Miuix) / 16dp (Material UI) from the screen edge.
- Kwallet's generator screen under Miuix/Material UI: the page no longer shifts off the left edge, the S/V key buttons stay one joined pair, the Addr buttons and Import keep their size (the style no longer resizes Kwallet's generator buttons, it only reshapes them).
- Hwallet's bottom bar: the selected-tab pill is centred on the icon (it hung to the right of the Settings icon); Miuix shows no pill, as in Miuix.
- Snackbars no longer slide half off screen in the new styles.
- **Browser pull to refresh**: only starts when the page is really at the top, including pages that scroll an inner box (the page is asked on finger-down whether the touched element's scroll box is at the top); once the pull starts the page stops getting the finger (no stray taps or overscroll stretch); release reloads once; the indicator closes when the page has loaded (or after 9 s).

## Kwallet v3.97.4 + Hwallet v1.7.4

### New
- **UI style** (Settings > UI style, Hwallet and Kwallet): **OG** (default, unchanged look), **Material UI** (Material 3) or **Miuix** (Xiaomi HyperOS style, as in LSPosed / InstallerX Revived). Same structure everywhere; the styles change shapes, sizes, type scale, controls (buttons, list rows, section titles, switches, segmented controls, text fields, chips), bottom sheets, dialogs, snackbars, top bars, the bottom bar, pressed states and motion, while keeping Hwallet's navy and Kwallet's lime colours (light and dark). Covers Hwallet's screens and every popup (sheets, pickers, fee sheet, Send, WalletConnect and dApp prompts, the dApp "Connect wallet" sheet, confirmations) and Kwallet's Settings, dialogs and sign sheet. Kwallet's sign sheet over Hwallet wears Hwallet's style (sent with every request). Miuix metrics come from compose-miuix-ui/miuix (Apache-2.0); Material values from the Material 3 spec / androidx.compose.material3 tokens.
- **Pull to refresh in each style**: OG = the classic detach, now updated once per frame and always released (refresh done, 9 s cap, cancelled touch, app hidden, style change); Material UI = the M3 indicator (40dp circle with the arrow arc, 80dp threshold with tension, spinner while refreshing); Miuix = the HyperOS capsule (ring fades in, stretches into a capsule past the threshold with a haptic tick, "Pull down to refresh / Release to refresh / Refreshing... / Refreshed successfully", orbiting dot, ring shrinks when done; thresholds and spring from Miuix's PullToRefresh.kt / SpringUtils.kt). Home, networks, coin and token pages, Activity, and the top-bar refresh button.
- **Browser pull to refresh is native** and works on every page, http included (it used to be an injected https-only script), in the selected style.
- Unknown Solana programs in a preview are named from their on-chain **Anchor IDL** when the program publishes one (program name in the App row and the matched instruction as a plain line).
- **Jupiter 0.3% service fee on Token-2022 pairs** too (Jupiter accepts Token-2022 fee accounts since Oct 2025; Metis quotes ask `instructionVersion=V2`). If a route refuses the Token-2022 fee account (e.g. transfer-hook mints) the swap is quoted again without the service fee.
- **Tron Activity goes back past 200 transactions**: TronGrid's fingerprint paging is followed while it lasts, then the history continues by time window (`max_timestamp`), for TRX and TRC-20.

### Notes
- Fees accounts already read the fee addresses' balances directly (#101 option A, since v3.97); no wallet switch is needed to see them.

## Kwallet v3.97.3 + Hwallet v1.7.3 (hotfix)

### Fixed
- **Hwallet stayed blank after the splash** (v1.7.2): a syntax error in the swap provider row (`'</span>'<span class="dp-v">'`) stopped Hwallet's main script from loading. Fixed; Hwallet opens normally again.

Includes everything from v3.97.2 / v1.7.2. Kwallet 3.97.3 has no functional change (version kept in step with Hwallet).

## Kwallet v3.97.2 + Hwallet v1.7.2

Second phone test of v3.97.1: the dApp signing fix, fluid pull to refresh, cleaner swap, live rent, and a set of quiet safety and speed features.

### Fixed
- **dApp swaps on every EVM chain never reached Kwallet** (Uniswap, 1inch, Stargate, Across, Jumper/LI.FI, Aave, OpenSea…): when a dApp put `chainId` in its transaction, Hwallet's chain check did `+BigInt(...)`, which throws "Cannot convert a BigInt value to a number". The request was rejected as an internal error before the sheet opened (Uniswap shows that as "Swap failed · Try adjusting slippage"). Chain ids are now parsed safely (hex, decimal, number, CAIP-2).
- dApp requests can no longer be blocked by Hwallet's own extras: a failed preview, simulation, allowance lookup or fee read falls back (gas price levels, the app's gas or a safe default) and the sheet always opens. Fees never go below the node's gas price (BNB Chain zero-tip blocks).
- `eth_sendTransaction` answers only with the 0x transaction hash after the broadcast (node answer, else Kwallet's, else computed from the signed bytes); failures are plain `-32603` errors, user cancels `4001`. No early auto-reject while Kwallet's sheet is open. Bigint values from pages travel as hex; quantities are normalised to hex.
- Every other read-only JSON-RPC method is proxied to the active chain's node instead of being refused; `wallet_switchEthereumChain` updates the page's chain id at once.
- **Tron dApps**: the injected `tronWeb` is now a working TronWeb surface (addresses, units, transactionBuilder incl. triggerSmartContract / triggerConstantContract / estimateEnergy, trx reads, sign / multiSign / signMessageV2, sendRawTransaction, `contract().at()` with call/send, `fullNode.request`) talking to TronGrid through Hwallet, so SunSwap, JustLend and SUN.io reach Kwallet's sheet.
- **Pull to refresh** could stay stuck (Activity). Rewritten as one fluid state machine for Home, networks, coin/token pages and Activity: content follows the finger with rubber-band resistance, the spinner turns with the pull, and the content always eases back when the refresh ends or at 9 s. The Activity refresh button uses the same path. New: pull to refresh in the in-app Browser (reloads the page, only when it is at the top).
- **Base logo** is the official Base square (Trust Wallet assets); token icons fall back from Gem's CDN to Trust Wallet's repo before a letter tile.

### Kwallet 3.97.2
- dApp sheet: plain titles (Swap, Send, Approve, Revoke; never "Contract call"), the real app/protocol name instead of a page title like "Home" or a contract name like "Aggregator", no method-call rows, amounts at sensible precision, network fee on one line with $ under it and a plain "Normal" subtitle.
- A live fee change updates in place; you are asked to hold again only if the fee rose more than 20%.
- Account rent is one small line under the amounts: red "−0.00204 SOL account rent · refunded when closed" (exact, from the simulated account size, Token-2022 included), green "+X SOL rent back" when accounts close; rent is counted in the balance after.

### Hwallet 1.7.2
- **Swap**: 25% / 50% / Max on You Pay for every coin and token (Max keeps the network fee only on the gas coin); missing prices (e.g. USDS) fetched from Jupiter / DefiLlama / CoinGecko; service fee as "0.3% · 0.000003 SOL" (never "0 SOL") with a small red "+0.00204 SOL one-time fee-account rent" only when that account must be opened (live rent); unclipped flip button; best-route badge and how much more it gives than the next provider; favourites and recent tokens at the top of the token picker; a small "Unverified" / "Risky" badge (GoPlus honeypot, sell-block, tax and freeze checks) on tokens before you swap; quotes, prices, gas and icons warmed when the screen opens.
- **Faster dApps**: keyed nodes first (Alchemy, Helius, TronGrid with your key), identical reads shared, short caches for block number / gas / balances, cheap answers (chain id, accounts) straight from the page, and the site's chain warmed when a connected site opens.
- **Token approvals** (Settings > Security): every open EVM / Tron allowance and Solana delegate, read live, with one-tap Revoke signed in Kwallet.
- **Address book** with names and chain icons (Send and Settings), and a stronger **address-poisoning warning** (look-alike of any address you know; addresses that only sent you a zero-value transfer).
- **Transaction notifications**: pending, confirmed or failed with a plain reason, for sends, swaps and dApp transactions.
- **Home**: swipe a token row to Pin or Hide; spam and dust tokens are hidden automatically (toggle in the hidden group).
- Balances and prices show at once from the phone's cache and refresh quietly.

## Kwallet v3.97.1 + Hwallet v1.7.1 (fix release)

Fixes from the first phone test of v3.97 / v1.7.

### Kwallet 3.97.1
- **Plain-language preview** (R5): the sign sheet no longer shows instruction counts, versions, compute-budget lines, account counts or raw data. It opens with one headline ("Swap 0.001 SOL for ≈0.1069 USDC via Jupiter"), then You send / You receive rows with icon, exact amount and $, approvals as "Allow X to spend up to N TOKEN" (or unlimited), account opens/closes with their rent, and recipients/contracts by name. Kwallet re-checks the token amounts it can decode itself and warns if they differ.
- **Plain errors** (R6): no raw JSON; common failures read "Not enough funds", "The price moved more than your slippage allows", "This request has expired". Co-signed / gasless transactions (fee paid by the app, e.g. Jupiter Ultra) are never flagged as failing; "Preview not available for this app's transaction" only when the changes cannot be computed.
- Fee sheets: the selected row has the same blue tint everywhere (no green).

### Hwallet 1.7.1
- **Exact previews from the chain** (R5), computed before Kwallet's sheet opens, every token resolved live: Solana via Helius simulateTransaction (all writable accounts, lookup tables included) and Helius DAS for any mint (SPL and Token-2022); EVM via Alchemy simulateAssetChanges, else eth_simulateV1, with method/contract names from Etherscan V2 verified ABIs or 4byte; Tron via TronGrid triggerconstantcontract logs and getcontract. Prices from Hwallet, else DefiLlama.
- **Pull to refresh** (R1): the classic pull: the content itself moves down, a small plain spinner sits in the gap, the content snaps back when done (9 s cap). The floating circle is gone. Transform-only.
- **Swap** (R2, R4, R7): the Network fee sheet opens on top of Details; live pair data (requote every 10 s and on typing, spinning ring while it runs, rate/output/$ flash on change, a failed requote keeps the last good quote, prices every 30 s); the 0.3% service fee now applies to every Jupiter pair: the fee owner's token account is found for the input or output mint (classic SPL), and created once in the swap when it does not exist yet.
- **Gem 1:1 dimensions** (R3) on home (top bar, wallet chip, balance block, round actions, token rows) and the swap screen, taken from gem-android's AssetsTopBar, AmountListHead, ListItem, AssetItem, IconWithBadge, paddings and SwapItem.

## Kwallet v3.97 + Hwallet v1.7 (Push 3)

Kwallet stays the fully secure offline signer; Hwallet becomes the quickest hot signer: every signature is one sheet over Hwallet, fees are set in the coin like Jupiter, and every transaction reads in plain language. Full Monero comes in v3.98.

### Kwallet 3.97 — the offline vault
- **One sign sheet for every signature** (#94, #102, #106): Send for every coin, Swap (approvals and the service-fee transfer included), dApps, WalletConnect and fee withdrawals all open the same translucent sheet over Hwallet, in Hwallet's navy (light) / blue (dark) theme. The old full-page sign screen is gone. Warm pre-loaded sheet opens in about 0.3 s.
- **Exact operation names** (#94): Swap, Send, Approve, Revoke, Stake / Unstake, Delegate, Vote, Supply, Borrow, Bridge, Limit order, Recurring buy, Sign-in, NFT listing (Seaport), Permit / Permit2. Known-program registries for Solana, EVM (Uniswap Universal Router incl. V4, 1inch v6, V3 routers) and Tron (SunSwap Smart / Universal / V2 / V3 routers, SUN.io Permit2, JustLend, sTRX, native Stake 2.0 types).
- **Balance changes** (#104): Solana simulation deltas (SOL, SPL, Token-2022) and Tron/Solana simulation errors from Hwallet; quotes shown as "You send / You receive at least". Unknown programs are a quiet Details note; red only for real risks.
- **Jupiter-style fee sheet** (#108, #109, #110): speed rows with the fee in the coin and dollars and a check (Solana Site suggested / Faster / Fastest, EVM Low / Normal / High with Gwei, Tron Normal / Fast as "up to X TRX"), and a Manual Custom section: Transaction landing Max Cap | Exact Fee, Solana priority fee in SOL, EVM max fee / priority fee in Gwei and gas limit, Tron fee limit in TRX, with the live total. No micro-lamports or compute units on screen. "Use this fee" applies on the first tap; sheets clear the navigation bar; the "unusually high fee" warning is measured against each chain's normal fee.

### Hwallet 1.7 — the hot wallet
- **Send as a sheet** (#103) for every coin, with the same fee sheet and Manual Custom fee; **Swap settings** use it too, including an optional **Jito tip** on Jupiter routes.
- **Swap** (#84, #87, #100): smoother layout and token picker (chips no longer clipped, quiet selection, badge room), live quotes every 12 s with a countdown ring, and a stepper (Approve exact → Swap → service fee) that stays over the Kwallet sheets; approval skipped when the allowance is enough.
- **Readable Activity** (#98): tap a row for a plain-language headline, balance changes read from the chain, status, network fee, date, counterparty and an Explorer button (Solana, EVM networks, Tron). Swaps made on this phone read "Swap USDC → SOL" in the list.
- **WalletConnect** (#92, #97b): requests keep arriving while Hwallet is in the background (native relay socket + the live foreground service); a heads-up notification brings Hwallet forward and Kwallet's sheet opens at once; after answering Hwallet returns to the dApp (its redirect link, else the previous app). Wallet metadata now has an https icon and the `hwallet://wc` redirect.
- **Browser** (#86, #95, #99, #105): pages load on every phone (an invalid origin rule stopped every tab), normal HTTP cache, compositor-only progress bar, Android Back = page back, 20 bundled dApp icons plus each site's favicon, History and Most visited. Providers register at document start and again when the page is parsed and loaded; Solana Wallet Standard lists mainnet by name and by CAIP-2 id; EIP-6963 announces "Hwallet" (rdns `app.hwallet`).
- **Balances** (#93, #107): batches fall back to single calls; a token missing from a reply keeps its last value; Solana reads Token and Token-2022 accounts and adds any held token automatically; pinned TRC-20 tokens are read directly; retries per chain. Pull to refresh (#85) spins until done, with a 9 s cap.
- **Fees accounts** (#101): reads the fee addresses' balances directly on every chain.
- **Settings > API keys** (#89): one row that opens its own page with Your key / Built-in / Keyless per provider and the editor.
- **Gem dimensions** (#90c, #91): 72 dp rows, 44 dp logos with Gem's badge size and ring, 64 dp header icon and top bar, balance in Gem's display size, 54 dp round actions. Chain and token icons come from Gem Wallet's assets (MIT, credited in NOTICE).
- No "Let app always run in background?" prompt unless you ask for it in Settings; no focus ring on the bottom bar.

### Known limits
- XMR send is not included yet; full Monero (view-key scan, Kwallet cold signing, key images) comes in **v3.98**.
- Approve → Swap uses two Kwallet sheets with Hwallet's stepper; no Permit2 / EIP-2612 signature path yet.
- The Jito tip is offered on Hwallet's own Jupiter swaps only; Kwallet never adds instructions to an app's transaction.
- A WalletConnect request that arrives while another app is open needs one tap on its notification (Android blocks background app starts).
- Hwallet is not in the WalletConnect directory yet.
- This release was built and signed by CI; it was not tested on a device before publishing.

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
