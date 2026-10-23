package app.kwallet

import android.graphics.Color
import android.util.Size
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
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v3.99.14: Kwallet's QR scanner for the air-gap link (a port of Hwallet's HwScan, no photo decoding).
 * A CameraX preview sits behind the transparent page, which draws the scanner frame over it; frames are decoded on the
 * phone with ZXing (bundled, pure Java, no Play services, no network). Air-gap requests are animated multi-part QRs:
 * after each decoded frame the page calls resume() and scanning goes on until the page has every part. The same frame
 * twice in a row is not reported again (the page only needs new parts).
 */
class KwScan(private val act: FragmentActivity, private val root: FrameLayout, private val onHit: (String) -> Unit) {
    private var preview: PreviewView? = null
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val done = AtomicBoolean(false)
    @Volatile private var last = ""
    @Volatile var running = false; private set

    private fun reader() = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8"))
    }

    fun start() {
        if (running) { done.set(false); return }
        running = true; done.set(false); last = ""
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
            if (done.get() || !running) return
            val pl = img.planes[0]; val buf = pl.buffer; val w = img.width; val h = img.height; val rs = pl.rowStride
            val y = ByteArray(w * h)
            if (rs == w) { buf.rewind(); buf.get(y, 0, minOf(y.size, buf.remaining())) }
            else for (r in 0 until h) { buf.position(r * rs); buf.get(y, r * w, w) }
            val src = PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false)
            val t = decode(rd, src)
            // continuous multi-frame scanning: report each new frame once, then wait for the page's resume()
            if (t != null && t != last && done.compareAndSet(false, true)) { last = t; act.runOnUiThread { if (running) onHit(t) } }
        } catch (_: Throwable) {} finally { img.close() }
    }

    private fun decode(rd: MultiFormatReader, src: LuminanceSource): String? {
        try { return rd.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text } catch (_: Throwable) {} finally { rd.reset() }
        try { return rd.decodeWithState(BinaryBitmap(HybridBinarizer(src.invert()))).text } catch (_: Throwable) {} finally { rd.reset() }
        return null
    }

    fun torch(on: Boolean): Boolean = try { val c = camera; if (c != null && c.cameraInfo.hasFlashUnit()) { c.cameraControl.enableTorch(on); true } else false } catch (_: Throwable) { false }

    fun resume() { done.set(false) }

    fun stop() {
        running = false; done.set(true); last = ""
        try { camera?.cameraControl?.enableTorch(false) } catch (_: Throwable) {}
        try { provider?.unbindAll() } catch (_: Throwable) {}
        camera = null
        preview?.let { try { root.removeView(it) } catch (_: Throwable) {} }
        preview = null
    }

    /** the activity is going away: stop and let the analyzer thread end */
    fun release() { stop(); try { exec.shutdown() } catch (_: Throwable) {} }

    var state: (String) -> Unit = {}
}
