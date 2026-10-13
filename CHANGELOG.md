# Changelog

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
