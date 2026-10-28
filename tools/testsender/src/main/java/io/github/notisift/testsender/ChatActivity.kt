package io.github.notisift.testsender

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView

/** The "chat screen". Logs which chat opened so tests can assert the deep link landed in the right place. */
class ChatActivity : Activity() {
    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        label = TextView(this).apply { textSize = 28f; setPadding(48, 200, 48, 48) }
        setContentView(label)
        show(intent)
    }

    // Already open (an earlier test chat): Android hands the new chat to this screen instead.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        val chat = intent.getStringExtra("chat")
        Log.i("SiftTest", "OPENED chat=$chat")
        label.text = "Chat with $chat"
    }
}
