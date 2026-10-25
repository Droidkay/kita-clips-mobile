package com.kita.clips

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible activity. Android only lets an app read the clipboard while one of its windows has focus,
 * so this flashes up for a moment, reads the clipboard, sends it, and closes.
 * Triggered from the notification action, the Quick Settings tile, or Share > Kita Clips.
 */
class ClipCaptureActivity : Activity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)

        if (intent?.action == Intent.ACTION_SEND) {
            handled = true
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            val ok = !shared.isNullOrBlank() && Hub.addLocal(shared)
            toast(if (ok) sentMessage() else "Nothing to send")
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !handled) {
            handled = true
            val ok = Hub.captureClipboard(this)
            toast(if (ok) sentMessage() else "Nothing new on the clipboard")
            finish()
        }
    }

    private fun sentMessage(): String =
        if (Hub.settings.value.paired) "Sent to your other devices" else "Saved to Kita Clips"

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
}
