package com.example.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object NotificationHelper {
    const val CHANNEL_LOCATION = "familytrack_location"
    const val CHANNEL_MESSAGES = "familytrack_messages"
    const val CHANNEL_CALLS = "familytrack_calls"
    const val CHANNEL_GAMES = "familytrack_games"
    const val CHANNEL_HUB = "familytrack_hub"
    const val NOTIF_ID_LOCATION_FGS = 1001

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val channels = listOf(
            NotificationChannel(
                CHANNEL_LOCATION,
                context.getString(R.string.notif_channel_location_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notif_channel_location_desc)
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.notif_channel_messages_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_messages_desc)
            },
            NotificationChannel(
                CHANNEL_CALLS,
                context.getString(R.string.notif_channel_calls_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_calls_desc)
            },
            NotificationChannel(
                CHANNEL_GAMES,
                context.getString(R.string.notif_channel_games_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_games_desc)
            },
            NotificationChannel(
                CHANNEL_HUB,
                context.getString(R.string.notif_channel_hub_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_hub_desc)
            }
        )
        channels.forEach { nm.createNotificationChannel(it) }
    }

    fun buildLocationForegroundNotification(context: Context, statusLine: String): android.app.Notification {
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, LocationForegroundService::class.java).setAction(LocationForegroundService.ACTION_PAUSE_SHARING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_LOCATION)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentTitle("FamilyTrack BD • Location Sharing Active")
            .setContentText(statusLine)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Pause Sharing", pauseIntent)
            .build()
    }

    fun showAlertNotification(context: Context, channelId: String, notifId: Int, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setPriority(if (channelId == CHANNEL_CALLS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        }
    }
}
