package app.hwallet

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * v1.8.8 (push 13, R9a / R11a): the H -> K payload channel. A request too large for an Intent extra (a Monero unsigned set
 * plus exported outputs is often far over the ~400 kB a Binder transaction can carry) is written to Hwallet's private
 * storage and handed to Kwallet's SignActivity as a content:// URI with a one-shot read grant (and a write grant on the
 * matching answer file). Not exported: nobody can open these files without a grant that only Hwallet gives, and only
 * to the Kwallet package it has already verified. Files live in filesDir/kwx and are deleted after use (or after 1 h).
 */
class KwxProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val c = context ?: return null
        val seg = uri.pathSegments
        if (seg.size != 2) throw java.io.FileNotFoundException("bad path")
        val kind = seg[0]; val id = seg[1]
        if (!ID.matches(id) || (kind != "req" && kind != "res")) throw java.io.FileNotFoundException("bad name")
        val f = file(c, kind, id)
        val write = mode.contains('w')
        /* a request is read-only; only the answer file may be written (by the grant holder) */
        if (kind == "req" && write) throw SecurityException("read only")
        if (!write && !f.exists()) throw java.io.FileNotFoundException("gone")
        return ParcelFileDescriptor.open(f, if (write) ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE else ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/json"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        const val AUTH = "app.hwallet.kwx"
        val ID = Regex("^[A-Za-z0-9_-]{4,64}$")
        fun dir(c: Context): File = File(c.filesDir, "kwx").apply { mkdirs() }
        fun file(c: Context, kind: String, id: String): File = File(dir(c), "$kind-$id.json")
        fun uri(kind: String, id: String): Uri = Uri.parse("content://$AUTH/$kind/$id")
        /** drops every handoff file older than an hour (a request that was never answered) */
        fun clean(c: Context) { try { val old = System.currentTimeMillis() - 3600_000L; dir(c).listFiles()?.forEach { if (it.lastModified() < old) it.delete() } } catch (_: Throwable) {} }
    }
}
