package app.hwallet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Size
import android.view.View
import android.widget.FrameLayout
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.5 (#61/#65): Hwallet's own QR scanner, Gem style. No Google code scanner, no Play services:
 * a CameraX preview sits behind the (transparent) page, which draws Gem's scanner UI over it, and frames are
 * decoded on the phone with ZXing (bundled, pure Java). Torch through CameraX, photos decoded the same way.
 */
class HwScan(private val act: FragmentActivity, private val root: FrameLayout, private val onHit: (String) -> Unit) {
    private var preview: PreviewView? = null
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val done = AtomicBoolean(false)
    @Volatile var running = false; private set

    private fun reader() = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8"))
    }

    fun start() {
        if (running) { done.set(false); return }
        running = true; done.set(false)
        val pv = PreviewView(act).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE   // a TextureView: draws correctly under the WebView
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(Color.BLACK)
        }
        preview = pv
        root.addView(pv, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val f = ProcessCameraProvider.getInstance(act)
        f.addListener({
            try {
                if (!running) return@addListener
                val p = f.get(); provider = p
                val pr = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                @Suppress("DEPRECATION")
                val an = ImageAnalysis.Builder().setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                val rd = reader()
                an.setAnalyzer(exec) { img -> analyze(rd, img) }
                p.unbindAll()
                camera = p.bindToLifecycle(act, CameraSelector.DEFAULT_BACK_CAMERA, pr, an)
                state("ready:" + (if (camera?.cameraInfo?.hasFlashUnit() == true) "1" else "0"))
            } catch (e: Throwable) { state("error:" + (e.message ?: "camera")) }
        }, ContextCompat.getMainExecutor(act))
    }

    private fun analyze(rd: MultiFormatReader, img: ImageProxy) {
        try {
            if (done.get()) return
            val pl = img.planes[0]; val buf = pl.buffer; val w = img.width; val h = img.height; val rs = pl.rowStride
            val y = ByteArray(w * h)
            if (rs == w) { buf.rewind(); buf.get(y, 0, minOf(y.size, buf.remaining())) }
            else for (r in 0 until h) { buf.position(r * rs); buf.get(y, r * w, w) }
            val src = PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false)
            val t = decode(rd, src)
            if (t != null && done.compareAndSet(false, true)) act.runOnUiThread { onHit(t) }
        } catch (_: Throwable) {} finally { img.close() }
    }

    private fun decode(rd: MultiFormatReader, src: LuminanceSource): String? {
        try { return rd.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text } catch (_: Throwable) {} finally { rd.reset() }
        try { return rd.decodeWithState(BinaryBitmap(HybridBinarizer(src.invert()))).text } catch (_: Throwable) {} finally { rd.reset() }
        return null
    }

    /** a photo from the picker: decoded on the phone, result (or "") through the callback */
    fun decodeImage(uri: Uri, cb: (String) -> Unit) {
        exec.execute {
            var out = ""
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                act.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                var s = 1; while (o.outWidth / s > 2000 || o.outHeight / s > 2000) s *= 2
                val bm: Bitmap? = act.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
                if (bm != null) {
                    val px = IntArray(bm.width * bm.height); bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
                    out = decode(reader(), RGBLuminanceSource(bm.width, bm.height, px)) ?: ""
                    bm.recycle()
                }
            } catch (_: Throwable) {}
            act.runOnUiThread { cb(out) }
        }
    }

    fun torch(on: Boolean): Boolean = try { val c = camera; if (c != null && c.cameraInfo.hasFlashUnit()) { c.cameraControl.enableTorch(on); true } else false } catch (_: Throwable) { false }

    fun resume() { done.set(false) }

    fun stop() {
        running = false; done.set(true)
        try { camera?.cameraControl?.enableTorch(false) } catch (_: Throwable) {}
        try { provider?.unbindAll() } catch (_: Throwable) {}
        camera = null
        preview?.let { try { root.removeView(it) } catch (_: Throwable) {} }
        preview = null
    }

    var state: (String) -> Unit = {}
}
