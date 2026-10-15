package app.hwallet

import com.sun.jna.Library
import com.sun.jna.NativeLong
import com.sun.jna.Pointer

/**
 * v1.8.12 (push 17): the native Monero engine. monero_c's C API over monero-project's wallet2
 * (libmonero_wallet2_api_c.so, prebuilt release v0.18.4.6-RC2 of github.com/MrCyjaneK/monero_c, LGPL-3.0; Monero BSD-3-Clause),
 * the same library Cake Wallet's cw_monero loads. Loaded with JNA; no C code of ours.
 * C `bool` is mapped to Byte (one byte, read/written as 0 or 1) so a return register's upper bits can never read as true;
 * size_t is NativeLong (64-bit on arm64/x86_64, 32-bit on armeabi-v7a, like size_t on Android).
 */
@Suppress("FunctionName")
interface HwXmrLib : Library {
    fun MONERO_WalletManagerFactory_getWalletManager(): Pointer?
    fun MONERO_WalletManagerFactory_setLogLevel(level: Int)
    fun MONERO_WalletManager_walletExists(wm: Pointer, path: String): Byte
    fun MONERO_WalletManager_openWallet(wm: Pointer, path: String, password: String, nettype: Int): Pointer?
    fun MONERO_WalletManager_createWalletFromKeys(wm: Pointer, path: String, password: String, language: String, nettype: Int, restoreHeight: Long,
                                                  address: String, viewKey: String, spendKey: String, kdfRounds: Long): Pointer?
    fun MONERO_WalletManager_closeWallet(wm: Pointer, w: Pointer, store: Byte): Byte

    fun MONERO_Wallet_status(w: Pointer): Int
    fun MONERO_Wallet_errorString(w: Pointer): String?
    fun MONERO_Wallet_address(w: Pointer, account: Long, index: Long): String?
    fun MONERO_Wallet_init(w: Pointer, daemon: String, upperTxSizeLimit: Long, user: String, pass: String, useSsl: Byte, lightWallet: Byte, proxy: String): Byte
    fun MONERO_Wallet_setTrustedDaemon(w: Pointer, on: Byte)
    fun MONERO_Wallet_trustedDaemon(w: Pointer): Byte
    /** v1.8.14: wallet2::import_key_images from Trezor-style JSON (ChaCha20-Poly1305 IETF blobs of key image || sig.c || sig.r) */
    fun MONERO_Wallet_importTrezorEncryptedKeyImagesJson(w: Pointer, json: String): Byte
    fun MONERO_Wallet_setSubaddressLookahead(w: Pointer, major: Int, minor: Int)
    fun MONERO_Wallet_setRefreshFromBlockHeight(w: Pointer, h: Long)
    fun MONERO_Wallet_getRefreshFromBlockHeight(w: Pointer): Long
    fun MONERO_Wallet_startRefresh(w: Pointer)
    fun MONERO_Wallet_pauseRefresh(w: Pointer)
    fun MONERO_Wallet_refreshAsync(w: Pointer)
    fun MONERO_Wallet_setAutoRefreshInterval(w: Pointer, ms: Int)
    fun MONERO_Wallet_blockChainHeight(w: Pointer): Long
    fun MONERO_Wallet_daemonBlockChainHeight(w: Pointer): Long
    fun MONERO_Wallet_synchronized(w: Pointer): Byte
    fun MONERO_Wallet_connected(w: Pointer): Int
    fun MONERO_Wallet_balance(w: Pointer, account: Int): Long
    fun MONERO_Wallet_unlockedBalance(w: Pointer, account: Int): Long
    fun MONERO_Wallet_store(w: Pointer, path: String): Byte
    fun MONERO_Wallet_numSubaddresses(w: Pointer, account: Int): NativeLong
    fun MONERO_Wallet_addSubaddress(w: Pointer, account: Int, label: String)
    fun MONERO_Wallet_getSubaddressLabel(w: Pointer, account: Int, index: Int): String?
    fun MONERO_Wallet_setSubaddressLabel(w: Pointer, account: Int, index: Int, label: String)
    fun MONERO_Wallet_exportOutputs(w: Pointer, file: String, all: Byte): Byte
    fun MONERO_Wallet_submitTransaction(w: Pointer, file: String): Byte
    fun MONERO_Wallet_getBytesReceived(w: Pointer): Long
    fun MONERO_Wallet_createTransaction(w: Pointer, dst: String, paymentId: String, amount: Long, mixin: Int, priority: Int, account: Int,
                                        preferredInputs: String, separator: String): Pointer?
    fun MONERO_Wallet_createTransactionMultDest(w: Pointer, dstList: String, dstSep: String, paymentId: String, sweepAll: Byte, amountList: String,
                                                amountSep: String, mixin: Int, priority: Int, account: Int, preferredInputs: String, preferredSep: String): Pointer?
    fun MONERO_Wallet_history(w: Pointer): Pointer?
    fun MONERO_Wallet_coins(w: Pointer): Pointer?

    fun MONERO_PendingTransaction_status(p: Pointer): Int
    fun MONERO_PendingTransaction_errorString(p: Pointer): String?
    fun MONERO_PendingTransaction_commit(p: Pointer, file: String, overwrite: Byte): Byte
    fun MONERO_PendingTransaction_amount(p: Pointer): Long
    fun MONERO_PendingTransaction_fee(p: Pointer): Long
    fun MONERO_PendingTransaction_txCount(p: Pointer): Long
    fun MONERO_PendingTransaction_txid(p: Pointer, separator: String): String?

    fun MONERO_TransactionHistory_refresh(h: Pointer)
    fun MONERO_TransactionHistory_count(h: Pointer): Int
    fun MONERO_TransactionHistory_transaction(h: Pointer, i: Int): Pointer?
    fun MONERO_TransactionInfo_direction(t: Pointer): Int
    fun MONERO_TransactionInfo_isPending(t: Pointer): Byte
    fun MONERO_TransactionInfo_isFailed(t: Pointer): Byte
    fun MONERO_TransactionInfo_amount(t: Pointer): Long
    fun MONERO_TransactionInfo_fee(t: Pointer): Long
    fun MONERO_TransactionInfo_blockHeight(t: Pointer): Long
    fun MONERO_TransactionInfo_confirmations(t: Pointer): Long
    fun MONERO_TransactionInfo_hash(t: Pointer): String?
    fun MONERO_TransactionInfo_timestamp(t: Pointer): Long
    fun MONERO_TransactionInfo_subaddrIndex(t: Pointer, separator: String): String?
    fun MONERO_TransactionInfo_subaddrAccount(t: Pointer): Int
    fun MONERO_TransactionInfo_transfers_count(t: Pointer): Int
    fun MONERO_TransactionInfo_transfers_amount(t: Pointer, i: Int): Long
    fun MONERO_TransactionInfo_transfers_address(t: Pointer, i: Int): String?

    fun MONERO_Coins_refresh(c: Pointer)
    fun MONERO_Coins_count(c: Pointer): Int
    fun MONERO_Coins_coin(c: Pointer, i: Int): Pointer?
    fun MONERO_Coins_setFrozenByPublicKey(c: Pointer, pub: String)
    fun MONERO_CoinsInfo_internalOutputIndex(ci: Pointer): NativeLong
    fun MONERO_CoinsInfo_spent(ci: Pointer): Byte
    fun MONERO_CoinsInfo_frozen(ci: Pointer): Byte
    fun MONERO_CoinsInfo_amount(ci: Pointer): Long
    fun MONERO_CoinsInfo_keyImageKnown(ci: Pointer): Byte
    fun MONERO_CoinsInfo_subaddrIndex(ci: Pointer): Int
    fun MONERO_CoinsInfo_subaddrAccount(ci: Pointer): Int
    fun MONERO_CoinsInfo_unlocked(ci: Pointer): Byte
    fun MONERO_CoinsInfo_pubKey(ci: Pointer): String?
}
