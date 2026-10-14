package dev.pocketrun.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.pocketrun.MainActivity
import dev.pocketrun.R

/**
 * Heads-up notification for a turn that finished while the app was in the
 * background, so a long build or a CI wait does not go unnoticed. The channel
 * is created lazily and the permission is checked before posting.
 */
object TurnNotifier {

    private const val CHANNEL_ID = "turns"
    private const val NOTIFICATION_ID = 1001

    /** Whether the UI is visible; a turn finishing hidden posts a heads-up. */
    @Volatile
    var inForeground: Boolean = true

    fun setForeground(foreground: Boolean) { inForeground = foreground }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ходы агента",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Завершение фоновых ходов агента" }
        manager.createNotificationChannel(channel)
    }

    fun notifyTurnFinished(context: Context, title: String, body: String) {
        if (inForeground) return
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}