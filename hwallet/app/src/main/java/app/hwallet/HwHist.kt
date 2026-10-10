package app.hwallet

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * v1.4 (#55): on-chain activity read natively by the live service, so it lands in the activity cache (HwActDb)
 * while Hwallet is closed. Same sources and the same row format as the page's Activity streams (segHist.js):
 * Blockscout (EVM), Solana RPC, mempool.space / Blockstream (BTC), TronGrid, Sui RPC, XRPL, Koios (Cardano).
 * Each stream reads its newest page; when its last-seen cursor (the newest tx it saw last time) is not on that page,
 * it keeps reading older pages (up to 5) to catch up on what happened while the service was not running.
 * All traffic goes through HwNet, so the data speed limit applies.
 */
class HwHist(private val db: HwActDb) {
    private val http by lazy { HwNet.withTimeout(15000) }
    private val jt = "application/json".toMediaType()
    private class Page(val items: List<JSONObject>, val ids: List<String>, val cursor: Any?)

    /* ---------- network ---------- */
    private class Http429 : Exception()
    private fun get(u: String): String? = try {
        http.newCall(Request.Builder().url(u).header("Accept", "application/json").header("User-Agent", "Hwallet/" + BuildConfig.VERSION_NAME).build()).execute().use { r ->
            if (r.code == 429) throw Http429()
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (e: Http429) { throw e } catch (_: Exception) { null }

    private fun post(u: String, body: String): String? = try {
        http.newCall(Request.Builder().url(u).header("Accept", "application/json").header("User-Agent", "Hwallet/" + BuildConfig.VERSION_NAME).post(body.toRequestBody(jt)).build()).execute().use { r ->
            if (r.code == 429) throw Http429()
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (e: Http429) { throw e } catch (_: Exception) { null }

    private fun first(urls: List<String>, f: (String) -> String?): String? { for (u in urls) { val r = try { f(u) } catch (_: Http429) { null }; if (r != null) return r }; return null }
    private fun rpc(urls: List<String>, method: String, params: JSONArray): Any? {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString()
        val r = first(urls) { post(it, body) } ?: return null
        val o = JSONObject(r); if (o.has("error")) return null
        return o.opt("result")
    }

    /* ---------- helpers ---------- */
    private fun itm(o: JSONObject): JSONObject {
        o.put("k", o.optString("ch") + ":" + o.optString("net", "") + ":" + o.optString("hash") + ":" + o.optString("key", "native").ifEmpty { "native" } + ":" + o.optString("dir", ""))
        return o
    }
    private fun row(ch: String, net: String?, hash: String, t: Long, dir: String, key: String, s: String, d: Int, raw: Any?, cg: String, st: String): JSONObject {
        val o = JSONObject().put("ch", ch)
        if (!net.isNullOrEmpty()) o.put("net", net)
        o.put("hash", hash).put("t", t).put("dir", dir).put("key", key).put("s", s).put("d", d).put("raw", raw ?: JSONObject.NULL).put("cg", cg).put("st", st)
        return o
    }
    private fun big(s: Any?): BigInteger = try { BigInteger(s.toString().trim().ifEmpty { "0" }) } catch (_: Exception) { try { BigDecimal(s.toString()).toBigInteger() } catch (_: Exception) { BigInteger.ZERO } }
    private fun isoMs(s: String?): Long {
        if (s.isNullOrEmpty()) return System.currentTimeMillis()
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US); f.timeZone = TimeZone.getTimeZone("UTC")
            f.parse(s.take(19))?.time ?: System.currentTimeMillis()
        } catch (_: Exception) { System.currentTimeMillis() }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun qs(o: JSONObject): String { val p = ArrayList<String>(); val it = o.keys(); while (it.hasNext()) { val k = it.next(); p.add(enc(k) + "=" + enc(o.opt(k).toString())) }; return p.joinToString("&") }
    private fun tok(c: JSONObject, a: String?): JSONObject? {
        if (a.isNullOrEmpty()) return null
        val t = c.optJSONArray("tokens") ?: return null
        for (i in 0 until t.length()) { val x = t.optJSONObject(i) ?: continue; if (x.optString("a").equals(a, ignoreCase = true)) return x }
        return null
    }
    private fun withTok(o: JSONObject, kt: JSONObject): JSONObject {
        if (kt.has("icon")) o.put("icon", kt.opt("icon"))
        if (kt.optBoolean("pin")) o.put("pin", true)
        return o
    }

    /* base58 (TRON addresses) */
    private val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private fun sha(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun b58e(b: ByteArray): String {
        var n = BigInteger(1, b); val sb = StringBuilder(); val f = BigInteger.valueOf(58)
        while (n > BigInteger.ZERO) { val qr = n.divideAndRemainder(f); sb.append(B58[qr[1].toInt()]); n = qr[0] }
        for (x in b) { if (x.toInt() == 0) sb.append('1') else break }
        return sb.reverse().toString()
    }
    private fun b58d(s: String): ByteArray {
        var n = BigInteger.ZERO; val f = BigInteger.valueOf(58)
        for (ch in s) { val i = B58.indexOf(ch); if (i < 0) return ByteArray(0); n = n.multiply(f).add(BigInteger.valueOf(i.toLong())) }
        var raw = n.toByteArray(); if (raw.size > 1 && raw[0].toInt() == 0) raw = raw.copyOfRange(1, raw.size)
        val zeros = s.takeWhile { it == '1' }.length
        return ByteArray(zeros) + raw
    }
    private fun hex(b: ByteArray) = b.joinToString("") { String.format("%02x", it.toInt() and 0xff) }
    private fun trxHex(a: String): String { val b = b58d(a); return if (b.size >= 21) hex(b.copyOfRange(0, 21)) else "" }
    private fun trxB58(h: String?): String {
        val x = h ?: return ""
        if (!Regex("^41[0-9a-fA-F]{40}$").matches(x)) return x
        val b = ByteArray(21) { i -> x.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        return b58e(b + sha(sha(b)).copyOfRange(0, 4))
    }
    private fun xrpCode(c: String): String {
        if (c.length != 40) return c
        val h = c.replace(Regex("(00)+$"), "")
        return try {
            val t = String(ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }, Charsets.ISO_8859_1)
            if (t.isNotEmpty() && t.all { it.code in 0x20..0x7e }) t else c.take(6)
        } catch (_: Exception) { c.take(6) }
    }
    private fun xrpUnits(v: String?): String = try { BigDecimal(v ?: "0").movePointRight(15).toBigInteger().toString() } catch (_: Exception) { "0" }

    /* ---------- one stream with its last-seen cursor ---------- */
    private fun stream(wid: String, sid: String, page: (Any?) -> Page?): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val top = db.cursor(wid, sid)
        var cur: Any? = null
        var newest: String? = null
        val pages = if (top == null) 1 else 5
        for (i in 0 until pages) {
            val p = page(cur) ?: break
            if (i == 0) newest = p.ids.firstOrNull() ?: "-"
            out.addAll(p.items)
            if (top == null || top == "-" || p.ids.contains(top) || p.cursor == null) break
            cur = p.cursor
        }
        val n = newest
        if (n != null) db.setCursor(wid, sid, n)
        return out
    }

    /** reads every stream of one watched chain (a watch entry of the service config) and stores the rows.
     *  Returns how many rows were new or changed. */
    fun sync(wid: String, c: JSONObject): Int {
        val rows = ArrayList<JSONObject>()
        try {
            when (c.optString("k")) {
                "evm" -> evm(wid, c, rows)
                "sol" -> sol(wid, c, rows)
                "btc" -> btc(wid, c, rows)
                "trx" -> trx(wid, c, rows)
                "sui" -> sui(wid, c, rows)
                "xrp" -> xrp(wid, c, rows)
                "ada" -> ada(wid, c, rows)
            }
        } catch (e: Http429) { throw e } catch (_: Exception) {}
        if (rows.isEmpty()) return 0
        return db.put(wid, rows).length()
    }

    private fun urls(c: JSONObject, key: String = "rpc"): List<String> { val a = c.optJSONArray(key) ?: return emptyList(); return (0 until a.length()).map { a.optString(it) }.filter { it.startsWith("https://") } }

    /* ---------- EVM: Blockscout native txs + known-token transfers ---------- */
    private fun evm(wid: String, c: JSONObject, rows: MutableList<JSONObject>) {
        val bs = c.optString("bs"); if (bs.isEmpty()) return
        val a = c.optString("addr"); val me = a.lowercase(); val id = c.optString("id"); val sym = c.optString("sym", "ETH"); val cg = c.optString("cg")
        rows.addAll(stream(wid, "evm:$id:n") { cur ->
            val j = JSONObject(get("$bs/api/v2/addresses/$a/transactions" + (if (cur is JSONObject) "?" + qs(cur) else "")) ?: return@stream null)
            val items = j.optJSONArray("items") ?: JSONArray(); val out = ArrayList<JSONObject>(); val ids = ArrayList<String>()
            for (i in 0 until items.length()) {
                val t = items.optJSONObject(i) ?: continue
                ids.add(t.optString("hash"))
                val from = (t.optJSONObject("from")?.optString("hash") ?: "").lowercase(); val to = (t.optJSONObject("to")?.optString("hash") ?: "").lowercase()
                val v = big(t.optString("value", "0"))
                val types = t.optJSONArray("tx_types") ?: t.optJSONArray("transaction_types") ?: JSONArray()
                var tt = false; for (q in 0 until types.length()) if (types.optString(q) == "token_transfer") tt = true
                if (v.signum() == 0 && (tt || from != me)) continue
                val dir = if (v.signum() == 0) "call" else if (from == me && to == me) "self" else if (from == me) "out" else "in"
                val res = t.optString("result", ""); val status = t.optString("status", "")
                val st = if (status == "error" || res == "error" || (res.isNotEmpty() && res != "success" && res != "pending")) "fail" else if (status.isNotEmpty()) "ok" else "pending"
                val o = row("eth", id, t.optString("hash"), isoMs(t.optString("timestamp")), dir, "native", sym, 18, v.toString(), cg, st).put("cp", if (dir == "in") from else to)
                t.optJSONObject("fee")?.optString("value")?.let { if (it.isNotEmpty()) o.put("fee", it) }
                out.add(itm(o))
            }
            Page(out, ids, j.optJSONObject("next_page_params"))
        })
        if ((c.optJSONArray("tokens")?.length() ?: 0) == 0) return
        rows.addAll(stream(wid, "evm:$id:t") { cur ->
            val j = JSONObject(get("$bs/api/v2/addresses/$a/token-transfers?type=ERC-20" + (if (cur is JSONObject) "&" + qs(cur) else "")) ?: return@stream null)
            val items = j.optJSONArray("items") ?: JSONArray(); val out = ArrayList<JSONObject>(); val ids = ArrayList<String>()
            for (i in 0 until items.length()) {
                val t = items.optJSONObject(i) ?: continue
                val hash = t.optString("transaction_hash", t.optString("tx_hash")); ids.add(hash)
                val tk = t.optJSONObject("token") ?: JSONObject(); val ta = tk.optString("address", tk.optString("address_hash"))
                val kt = tok(c, ta) ?: continue
                val from = (t.optJSONObject("from")?.optString("hash") ?: "").lowercase(); val to = (t.optJSONObject("to")?.optString("hash") ?: "").lowercase()
                val dir = if (from == me && to == me) "self" else if (from == me) "out" else "in"
                val o = row("eth", id, hash, isoMs(t.optString("timestamp")), dir, kt.optString("a"), kt.optString("s"), kt.optInt("d", 18), t.optJSONObject("total")?.optString("value") ?: "0", kt.optString("cg"), "ok").put("cp", if (dir == "in") from else to)
                out.add(itm(withTok(o, kt)))
            }
            Page(out, ids, j.optJSONObject("next_page_params"))
        })
    }

    /* ---------- Solana: signatures + parsed transactions (SOL and known SPL tokens) ---------- */
    private fun sol(wid: String, c: JSONObject, rows: MutableList<JSONObject>) {
        val a = c.optString("addr"); val rpcs = urls(c)
        rows.addAll(stream(wid, "sol") { cur ->
            val opt = JSONObject().put("limit", 15).put("commitment", "confirmed"); if (cur is String) opt.put("before", cur)
            val sigs = rpc(rpcs, "getSignaturesForAddress", JSONArray().put(a).put(opt)) as? JSONArray ?: return@stream null
            if (sigs.length() == 0) return@stream Page(emptyList(), emptyList(), null)
            val calls = JSONArray()
            for (i in 0 until sigs.length()) calls.put(JSONObject().put("jsonrpc", "2.0").put("id", i + 1).put("method", "getTransaction")
                .put("params", JSONArray().put(sigs.getJSONObject(i).optString("signature")).put(JSONObject().put("encoding", "jsonParsed").put("maxSupportedTransactionVersion", 0).put("commitment", "confirmed"))))
            val res = arrayOfNulls<JSONObject>(sigs.length())
            val br = first(rpcs) { post(it, calls.toString()) }
            var okBatch = false
            if (br != null && br.trimStart().startsWith("[")) {
                val arr = JSONArray(br)
                for (i in 0 until arr.length()) { val r = arr.optJSONObject(i) ?: continue; val n = r.optInt("id") - 1; if (n in res.indices) res[n] = r.optJSONObject("result") }
                okBatch = res.any { it != null }
            }
            if (!okBatch) for (i in 0 until sigs.length()) {
                res[i] = rpc(rpcs, "getTransaction", calls.getJSONObject(i).getJSONArray("params")) as? JSONObject
                try { Thread.sleep(150) } catch (_: InterruptedException) {}
            }
            val out = ArrayList<JSONObject>(); val ids = ArrayList<String>()
            for (i in 0 until sigs.length()) {
                val s = sigs.getJSONObject(i); val sig = s.optString("signature"); ids.add(sig)
                val t = if (s.optLong("blockTime", 0) > 0) s.optLong("blockTime") * 1000 else System.currentTimeMillis()
                val st = if (s.isNull("err")) "ok" else "fail"
                val tx = res[i]; val m = tx?.optJSONObject("meta")
                if (tx == null || m == null) { out.add(itm(row("sol", null, sig, t, "tx", "native", "SOL", 9, null, "solana", st))); continue }
                val msg = tx.optJSONObject("transaction")?.optJSONObject("message") ?: JSONObject()
                val ka = msg.optJSONArray("accountKeys") ?: JSONArray()
                val keys = (0 until ka.length()).map { q -> val x = ka.opt(q); if (x is JSONObject) x.optString("pubkey") else x.toString() }
                val ix = keys.indexOf(a); val payer = keys.firstOrNull() == a; val fee = BigInteger.valueOf(m.optLong("fee"))
                var any = false
                val tb = LinkedHashMap<String, BigInteger>()
                val pre = m.optJSONArray("preTokenBalances") ?: JSONArray(); val post = m.optJSONArray("postTokenBalances") ?: JSONArray()
                for (q in 0 until pre.length()) { val b = pre.getJSONObject(q); if (b.optString("owner") == a) tb[b.optString("mint")] = (tb[b.optString("mint")] ?: BigInteger.ZERO) - big(b.optJSONObject("uiTokenAmount")?.optString("amount")) }
                for (q in 0 until post.length()) { val b = post.getJSONObject(q); if (b.optString("owner") == a) tb[b.optString("mint")] = (tb[b.optString("mint")] ?: BigInteger.ZERO) + big(b.optJSONObject("uiTokenAmount")?.optString("amount")) }
                for ((mint, d) in tb) {
                    if (d.signum() == 0) continue
                    val kt = tok(c, mint) ?: continue
                    var other = ""
                    val src = if (d.signum() > 0) pre else post
                    val all = (0 until src.length()).map { src.getJSONObject(it) } + (0 until post.length()).map { post.getJSONObject(it) }
                    for (b in all) if (b.optString("mint") == mint && b.optString("owner").isNotEmpty() && b.optString("owner") != a) { other = b.optString("owner"); break }
                    any = true
                    out.add(itm(withTok(row("sol", null, sig, t, if (d.signum() > 0) "in" else "out", kt.optString("a"), kt.optString("s"), kt.optInt("d", 6), d.abs().toString(), kt.optString("cg"), st).put("cp", other), kt)))
                }
                if (ix >= 0) {
                    val pb = m.optJSONArray("preBalances"); val qb = m.optJSONArray("postBalances")
                    val dl = BigInteger.valueOf((qb?.optLong(ix) ?: 0L) - (pb?.optLong(ix) ?: 0L)) + (if (payer) fee else BigInteger.ZERO)
                    if (dl.signum() != 0 && !(any && dl.signum() < 0 && dl.negate() <= BigInteger.valueOf(2039280L * 2))) {
                        var cp = ""
                        val ins = msg.optJSONArray("instructions") ?: JSONArray()
                        for (q in 0 until ins.length()) {
                            val p = ins.optJSONObject(q)?.optJSONObject("parsed") ?: continue
                            val info = p.optJSONObject("info") ?: continue
                            if (p.optString("type") == "transfer" && info.has("lamports") && cp.isEmpty()) {
                                if (info.optString("source") == a) cp = info.optString("destination") else if (info.optString("destination") == a) cp = info.optString("source")
                            }
                        }
                        any = true
                        out.add(itm(row("sol", null, sig, t, if (dl.signum() > 0) "in" else "out", "native", "SOL", 9, dl.abs().toString(), "solana", st).put("cp", cp)))
                    }
                }
                if (!any && payer) out.add(itm(row("sol", null, sig, t, "call", "native", "SOL", 9, fee.toString(), "solana", st)))
            }
            Page(out, ids, if (sigs.length() >= 15) sigs.getJSONObject(sigs.length() - 1).optString("signature") else null)
        })
    }

    /* ---------- Bitcoin: Esplora address txs ---------- */
    private fun btc(wid: String, c: JSONObject, rows: MutableList<JSONObject>) {
        val a = c.optString("addr"); val rest = urls(c)
        rows.addAll(stream(wid, "btc") { cur ->
            val r = first(rest) { get("$it/address/$a/txs" + (if (cur is String) "/chain/$cur" else "")) } ?: return@stream null
            val txs = JSONArray(r); val out = ArrayList<JSONObject>(); val ids = ArrayList<String>(); var lastConf: String? = null
            for (i in 0 until txs.length()) {
                val t = txs.getJSONObject(i); ids.add(t.optString("txid"))
                var inn = BigInteger.ZERO; var outv = BigInteger.ZERO; var cpIn = ""; var cpOut = ""
                val vo = t.optJSONArray("vout") ?: JSONArray(); val vi = t.optJSONArray("vin") ?: JSONArray()
                for (q in 0 until vo.length()) { val o = vo.getJSONObject(q); if (o.optString("scriptpubkey_address") == a) inn += BigInteger.valueOf(o.optLong("value")) else if (cpOut.isEmpty()) cpOut = o.optString("scriptpubkey_address") }
                for (q in 0 until vi.length()) { val p = vi.getJSONObject(q).optJSONObject("prevout") ?: JSONObject(); if (p.optString("scriptpubkey_address") == a) outv += BigInteger.valueOf(p.optLong("value")) else if (cpIn.isEmpty()) cpIn = p.optString("scriptpubkey_address") }
                val mine = outv.signum() > 0
                val amt = if (mine) outv - inn - BigInteger.valueOf(t.optLong("fee")) else inn - outv
                val stt = t.optJSONObject("status"); val conf = stt?.optBoolean("confirmed") == true
                if (conf) lastConf = t.optString("txid")
                val time = if ((stt?.optLong("block_time") ?: 0L) > 0) stt!!.optLong("block_time") * 1000 else System.currentTimeMillis()
                out.add(itm(row("btc", null, t.optString("txid"), time, if (mine) (if (amt.signum() <= 0) "self" else "out") else "in", "native", "BTC", 8, amt.abs().toString(), "bitcoin", if (conf) "ok" else "pending").put("cp", if (mine) cpOut else cpIn)))
            }
            Page(out, ids, if (txs.length() >= 25) lastConf else null)
        })
    }

    /* ---------- TRON: TronGrid (about 3 requests a second without a key: one at a time, 350 ms apart) ---------- */
    private fun tron(c: JSONObject, path: String): String? {
        val base = urls(c).firstOrNull() ?: return null
        try { Thread.sleep(350) } catch (_: InterruptedException) {}
        return try { get(base + path) } catch (_: Http429) { try { Thread.sleep(1300) } catch (_: InterruptedException) {}; try { get(base + path) } catch (_: Http429) { null } }
    }
    private fun trx(wid: String, c: JSONObject, rows: MutableList<JSONObject>) {
        val a = c.optString("addr"); val me = trxHex(a).lowercase()
        rows.addAll(stream(wid, "trx:n") { cur ->
            val j = JSONObject(tron(c, "/v1/accounts/$a/transactions?limit=20" + (if (cur is String) "&fingerprint=" + enc(cur) else "")) ?: return@stream null)
            val data = j.optJSONArray("data") ?: JSONArray(); val out = ArrayList<JSONObject>(); val ids = ArrayList<String>()
            for (i in 0 until data.length()) {
                val t = data.getJSONObject(i); ids.add(t.optString("txID"))
                val ct = t.optJSONObject("raw_data")?.optJSONArray("contract")?.optJSONObject(0) ?: JSONObject()
                val v = ct.optJSONObject("parameter")?.optJSONObject("value") ?: JSONObject(); val ty = ct.optString("type")
                val ok = t.optJSONArray("ret")?.optJSONObject(0)?.optString("contractRet") ?: ""
                val st = if (ok.isNotEmpty() && ok != "SUCCESS") "fail" else "ok"
                val time = t.optLong("block_timestamp", System.currentTimeMillis())
                if (ty == "TransferContract") {
                    val outd = v.optString("owner_address").lowercase() == me
                    out.add(itm(row("trx", null, t.optString("txID"), time, if (outd) "out" else "in", "native", "TRX", 6, v.optLong("amount").toString(), "tron", st).put("cp", trxB58(if (outd) v.optString("to_address") else v.optString("owner_address")))))
                    continue
                }
                val label = when (ty) { "FreezeBalanceV2Contract" -> "stake"; "UnfreezeBalanceV2Contract" -> "unstake"; "WithdrawExpireUnfreezeContract" -> "withdraw"; else -> null } ?: continue
                val amt = if (v.has("frozen_balance")) v.optLong("frozen_balance") else v.optLong("unfreeze_balance")
                out.add(itm(row("trx", null, t.optString("txID"), time, label, "native", "TRX", 6, amt.toString(), "tron", st).put("res", v.optString("resource", "BANDWIDTH"))))
            }
            Page(out, ids, j.optJSONObject("meta")?.optString("fingerprint")?.ifEmpty { null })
        })
        if ((c.optJSONArray("tokens")?.length() ?: 0) == 0) return
        rows.addAll(stream(wid, "trx:t") { cur ->
            val j = JSONObject(tron(c, "/v1/accounts/$a/transactions/trc20?limit=20" + (if (cur is String) "&fingerprint=" + enc(cur) else "")) ?: return@stream null)
            val data = j.optJSONArray("data") ?: JSONArray(); val out = ArrayList<JSONObject>(); val ids = ArrayList<String>()
            for (i in 0 until data.length()) {
                val t = data.getJSONObject(i); ids.add(t.optString("transaction_id"))
                val kt = tok(c, t.optJSONObject("token_info")?.optString("address")) ?: continue
                val from = t.optString("from"); val to = t.optString("to"); val outd = from == a
                out.add(itm(withTok(row("trx", null, t.optString("transaction_id"), t.optLong("block_timestamp", System.currentTimeMillis()), if (from == to) "self" else if (outd) "out" else "in", kt.optString("a"), kt.optString("s"), kt.optInt("d", 6), t.optString("value", "0"), kt.optString("cg"), "ok").put("cp", if (outd) to else from), kt)))
            }
            Page(out, ids, j.optJSONObject("meta")?.optString("fingerprint")?.ifEmpty { null })
        })
    }

    /* ---------- Sui: transaction blocks from and to the address ---------- */
    private fun sui(wid: String, c: JSONObject, rows: MutableList<JSONObject>) {
        val addr = c.optString("addr"); val a = addr.lowercase(); val rpcs = urls(c)
        for (kind in listOf("FromAddress", "ToAddress")) rows.addAll(stream(wid, "sui:$kind") { cur ->
            val q = JSONObject().put("filter", JSONObject().put(kind, addr)).put("options", JSONObject().put("showBalanceChanges", true).put("showEffects", true))
            val r = rpc(rpcs, "suix_queryTransactionBlocks", JSONArray().put(q).put(cur ?: JSONObject.NULL).put(15).put(true)) as? JSONObject ?: return@stream null
            val data = r.optJSONArray("data") ?: JSONArray(); val out = ArrayList<JSONObject>(); val ids = ArrayList<String>()
            val sender = kind == "FromAddress"
            for (i in 0 until data.length()) {
                val t = data.getJSONObject(i); val dg = t.optString("digest"); ids.add(dg)
                val eff = t.optJSONObject("effects")
                val st = if (eff?.optJSONObject("status")?.optString("status") == "failure") "fail" else "ok"
                val time = t.optString("timestampMs").toLongOrNull() ?: System.currentTimeMillis()
                val gu = eff?.optJSONObject("gasUsed")
                val g = if (gu != null) big(gu.optString("computationCost")) + big(gu.optString("storageCost")) - big(gu.optString("storageRebate")) else BigInteger.ZERO
                val bc = t.optJSONArray("balanceChanges") ?: JSONArray()
                for (q in 0 until bc.length()) {
                    val b = bc.getJSONObject(q)
                    val own = b.optJSONObject("owner")?.optString("AddressOwner")?.lowercase() ?: continue
                    if (own != a) continue
                    var amt = big(b.optString("amount")); val ty = b.optString("coinType"); val isSui = Regex("^0x0*2::sui::SUI$").matches(ty)
                    if (isSui && sender) amt += g
                    if (amt.signum() == 0) continue
                    val kt = if (isSui) null else tok(c, ty)
                    if (!isSui && kt == null) continue
                    var other = ""
                    for (z in 0 until bc.length()) { val x = bc.getJSONObject(z); val ow = x.optJSONObject("owner")?.optString("AddressOwner") ?: continue; if (x.optString("coinType") == ty && ow.lowercase() != a) { other = ow; break } }
                    if (!sender && amt.signum() < 0) continue
                    if (isSui && sender && other.isEmpty() && amt.signum() < 0) { out.add(itm(row("sui", null, dg, time, "call", "native", "SUI", 9, big(b.optString("amount")).negate().toString(), "sui", st))); continue }
                    val o = row("sui", null, dg, time, if (amt.signum() > 0) "in" else "out", if (isSui) "native" else kt!!.optString("a"), if (isSui) "SUI" else kt!!.optString("s"), if (isSui) 9 else kt!!.optInt("d", 9), amt.abs().toString(), if (isSui) "sui" else kt!!.optString("cg"), st).put("cp", other)
                    out.add(itm(if (kt != null) withTok(o, kt) else o))
                }
            }
            Page(out, ids, if (r.optBoolean("hasNextPage")) r.opt("nextCursor") else null)
        })
    }

    /* ---------- XRP Ledger: account_tx ---------- */
    private fun xrp(wid: String, c: JSONObject, rows: MutableList<JSONObject>) {
        val a = c.optString("addr"); val rpcs = urls(c)
        rows.addAll(stream(wid, "xrp") { cur ->
            val p = JSONObject().put("account", a).put("limit", 20).put("ledger_index_min", -1).put("ledger_index_max", -1).put("forward", false)
            if (cur != null) p.put("marker", cur)
            val body = JSONObject().put("method", "account_tx").put("params", JSONArray().put(p)).toString()
            val r = JSONObject(first(rpcs) { post(it, body) } ?: return@stream null).optJSONObject("result") ?: return@stream null
            val txs = r.optJSONArray("transactions") ?: JSONArray(); val out = ArrayList<JSONObject>(); val ids = ArrayList<String>()
            for (i in 0 until txs.length()) {
                val x = txs.getJSONObject(i); val t = x.optJSONObject("tx") ?: x.optJSONObject("tx_json") ?: JSONObject(); val m = x.optJSONObject("meta") ?: JSONObject()
                val hash = t.optString("hash", x.optString("hash")); ids.add(hash)
                val time = if (t.has("date")) (t.optLong("date") + 946684800L) * 1000 else System.currentTimeMillis()
                val tr = m.optString("TransactionResult", "")
                val st = if (tr.isNotEmpty() && tr != "tesSUCCESS") "fail" else if (x.has("validated") && !x.optBoolean("validated")) "pending" else "ok"
                val type = t.optString("TransactionType")
                if (type == "TrustSet") { val la = t.optJSONObject("LimitAmount") ?: JSONObject(); out.add(itm(row("xrp", null, hash, time, "trust", la.optString("currency") + "." + la.optString("issuer"), xrpCode(la.optString("currency")), 15, "0", "", st))); continue }
                if (type != "Payment") { if (t.optString("Account") == a) out.add(itm(row("xrp", null, hash, time, "call", "native", "XRP", 6, t.optString("Fee", "0"), "ripple", st))); continue }
                val amt = m.opt("delivered_amount") ?: t.opt("DeliverMax") ?: t.opt("Amount")
                val outd = t.optString("Account") == a; val self = outd && t.optString("Destination") == a
                val dir = if (self) "self" else if (outd) "out" else "in"
                val cp = if (outd) t.optString("Destination") else t.optString("Account")
                if (amt is String) { val o = row("xrp", null, hash, time, dir, "native", "XRP", 6, amt, "ripple", st).put("cp", cp); if (t.has("DestinationTag")) o.put("tag", t.opt("DestinationTag")); out.add(itm(o)); continue }
                if (amt is JSONObject && amt.has("currency")) {
                    val key = amt.optString("currency") + "." + amt.optString("issuer"); val kt = tok(c, key)
                    val o = row("xrp", null, hash, time, dir, key, kt?.optString("s") ?: xrpCode(amt.optString("currency")), 15, xrpUnits(amt.optString("value")), "", st).put("cp", cp)
                    out.add(itm(if (kt != null) withTok(o, kt) else o))
                }
            }
            Page(out, ids, r.opt("marker"))
        })
    }

    /* ---------- Cardano: Koios address txs + tx info ---------- */
    private fun ada(wid: String, c: JSONObject, rows: MutableList<JSONObject>) {
        val a = c.optString("addr"); val rest = urls(c)
        rows.addAll(stream(wid, "ada") { cur ->
            val off = (cur as? Int) ?: 0
            val lst = JSONArray(first(rest) { post("$it/address_txs?order=block_time.desc&limit=15&offset=$off", JSONObject().put("_addresses", JSONArray().put(a)).toString()) } ?: return@stream null)
            if (lst.length() == 0) return@stream Page(emptyList(), emptyList(), null)
            val hs = JSONArray(); for (i in 0 until lst.length()) hs.put(lst.getJSONObject(i).optString("tx_hash"))
            val info = JSONArray(first(rest) { post("$it/tx_info", JSONObject().put("_tx_hashes", hs).put("_inputs", true).put("_assets", true).toString()) } ?: return@stream null)
            val by = HashMap<String, JSONObject>(); for (i in 0 until info.length()) { val t = info.getJSONObject(i); by[t.optString("tx_hash")] = t }
            val out = ArrayList<JSONObject>(); val ids = ArrayList<String>()
            for (i in 0 until lst.length()) {
                val r = lst.getJSONObject(i); val h = r.optString("tx_hash"); ids.add(h)
                val t = by[h] ?: continue
                var dv = BigInteger.ZERO; val tk = LinkedHashMap<String, BigInteger>(); var cpi = ""; var cpo = ""
                val time = (if (t.optLong("tx_timestamp") > 0) t.optLong("tx_timestamp") else r.optLong("block_time")) * 1000
                val outs = t.optJSONArray("outputs") ?: JSONArray(); val ins = t.optJSONArray("inputs") ?: JSONArray()
                for (q in 0 until outs.length()) { val o = outs.getJSONObject(q); val ad = o.optJSONObject("payment_addr")?.optString("bech32") ?: ""
                    if (ad == a) { dv += big(o.optString("value", "0")); val al = o.optJSONArray("asset_list") ?: JSONArray(); for (z in 0 until al.length()) { val x = al.getJSONObject(z); val k = x.optString("policy_id") + "." + x.optString("asset_name", ""); tk[k] = (tk[k] ?: BigInteger.ZERO) + big(x.optString("quantity")) } }
                    else if (cpo.isEmpty()) cpo = ad }
                for (q in 0 until ins.length()) { val o = ins.getJSONObject(q); val ad = o.optJSONObject("payment_addr")?.optString("bech32") ?: ""
                    if (ad == a) { dv -= big(o.optString("value", "0")); val al = o.optJSONArray("asset_list") ?: JSONArray(); for (z in 0 until al.length()) { val x = al.getJSONObject(z); val k = x.optString("policy_id") + "." + x.optString("asset_name", ""); tk[k] = (tk[k] ?: BigInteger.ZERO) - big(x.optString("quantity")) } }
                    else if (cpi.isEmpty()) cpi = ad }
                val outd = dv.signum() < 0; var any = false
                for ((k, d) in tk) {
                    if (d.signum() == 0) continue
                    val kt = tok(c, k); any = true
                    val o = row("ada", null, h, time, if (d.signum() > 0) "in" else "out", kt?.optString("a") ?: k, kt?.optString("s") ?: "TOKEN", kt?.optInt("d", 0) ?: 0, d.abs().toString(), "", "ok").put("cp", if (d.signum() > 0) cpi else cpo)
                    out.add(itm(if (kt != null) withTok(o, kt) else o))
                }
                val fee = big(t.optString("fee", "0"))
                val adaAmt = if (outd) dv.negate() - fee else dv
                val small = adaAmt.abs() < BigInteger.valueOf(3000000L)
                if (adaAmt.signum() != 0 && !(any && small)) out.add(itm(row("ada", null, h, time, if (adaAmt.signum() > 0) (if (outd) "out" else "in") else "out", "native", "ADA", 6, adaAmt.abs().toString(), "cardano", "ok").put("cp", if (outd) cpo else cpi)))
                else if (!any) out.add(itm(row("ada", null, h, time, "self", "native", "ADA", 6, fee.toString(), "cardano", "ok")))
            }
            Page(out, ids, if (lst.length() >= 15) off + 15 else null)
        })
    }
}
