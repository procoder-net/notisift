package io.github.notisift.testsender

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.os.Bundle

/** Posts one chat notification whose tap opens [ChatActivity] for exactly that chat, like a messaging app. */
class PostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chat = intent.getStringExtra("chat") ?: "Alice"
        val text = intent.getStringExtra("text") ?: "Are we still on for tonight?"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("chats", "Chats", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            this, chat.hashCode(),
            Intent(this, ChatActivity::class.java).putExtra("chat", chat)
                // A fresh chat screen per tap, like a real messenger opening that conversation.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, "chats")
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(chat)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
        // --ez plain true: an ordinary notification (no chat style), so NotiSift has to ask its model.
        if (!intent.getBooleanExtra("plain", false)) {
            val sender = Person.Builder().setName(chat).build()
            builder.setStyle(Notification.MessagingStyle(Person.Builder().setName("Me").build()).addMessage(text, System.currentTimeMillis(), sender))
                .setCategory(Notification.CATEGORY_MESSAGE)
        }
        val n = builder.build()
        // --es tag X posts the same chat under another key, like an app repeating an alert.
        manager.notify(intent.getStringExtra("tag") ?: chat, 1, n)
        finish()
    }
}
