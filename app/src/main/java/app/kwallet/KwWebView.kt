package app.kwallet

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/** v3.99.50: Kwallet's WebView. Every text box asks the keyboard for incognito mode (IME_FLAG_NO_PERSONALIZED_LEARNING):
 *  no learning, no suggestions history from seeds, PINs or keys. Typing and pasting work as before. */
class KwWebView(c: Context) : WebView(c) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return ic
    }
}
