package com.droidlate.app.core.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.droidlate.app.MainActivity
import com.droidlate.app.R

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_SYNC = "channel_sync_tasks"
        const val CHANNEL_EXPORTS = "channel_exports"

        const val ID_SYNC_ONGOING = 2001
        const val ID_SYNC_RESULT = 2002
        const val ID_EXPORT_RESULT = 2003
        const val ID_IMPORT_RESULT = 2004

        @Volatile
        private var instance: NotificationHelper? = null

        fun getInstance(context: Context): NotificationHelper {
            return instance ?: synchronized(this) {
                instance ?: NotificationHelper(context.applicationContext).also {
                    it.createNotificationChannels()
                    instance = it
                }
            }
        }
    }

    init {
        createNotificationChannels()
    }

    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val syncChannel = NotificationChannel(
                CHANNEL_SYNC,
                "Sync & Upstream Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for GitHub upstream repository synchronization"
                enableLights(true)
            }

            val exportsChannel = NotificationChannel(
                CHANNEL_EXPORTS,
                "Exports & Backups",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when translation ZIP archives are exported"
                enableLights(true)
            }

            notificationManager.createNotificationChannel(syncChannel)
            notificationManager.createNotificationChannel(exportsChannel)
        }
    }

    fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    private fun getAppLaunchPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getAppIconBitmap(): Bitmap? {
        return try {
            val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                ?: context.packageManager.getApplicationIcon(context.packageName)
            val w = drawable.intrinsicWidth.coerceAtLeast(108)
            val h = drawable.intrinsicHeight.coerceAtLeast(108)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    fun showSyncOngoing(projectName: String, detail: String = "Syncing with GitHub upstream...") {
        if (!hasPermission()) return

        val builder = NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Syncing $projectName")
            .setContentText(detail)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setContentIntent(getAppLaunchPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)

        getAppIconBitmap()?.let { builder.setLargeIcon(it) }

        try {
            NotificationManagerCompat.from(context).notify(ID_SYNC_ONGOING, builder.build())
        } catch (_: SecurityException) {
            // Permission not granted
        }
    }

    fun showSyncSuccess(projectName: String, summary: String) {
        cancel(ID_SYNC_ONGOING)
        if (!hasPermission()) return

        val builder = NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sync Complete · $projectName")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setContentIntent(getAppLaunchPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        getAppIconBitmap()?.let { builder.setLargeIcon(it) }

        try {
            NotificationManagerCompat.from(context).notify(ID_SYNC_RESULT, builder.build())
        } catch (_: SecurityException) {
            // Permission not granted
        }
    }

    fun showSyncFailed(projectName: String, errorMessage: String) {
        cancel(ID_SYNC_ONGOING)
        if (!hasPermission()) return

        val builder = NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sync Failed · $projectName")
            .setContentText(errorMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(errorMessage))
            .setAutoCancel(true)
            .setContentIntent(getAppLaunchPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        getAppIconBitmap()?.let { builder.setLargeIcon(it) }

        try {
            NotificationManagerCompat.from(context).notify(ID_SYNC_RESULT, builder.build())
        } catch (_: SecurityException) {
            // Permission not granted
        }
    }

    fun showExportSuccess(projectName: String, fileName: String, pendingIntent: PendingIntent? = null) {
        if (!hasPermission()) return

        val builder = NotificationCompat.Builder(context, CHANNEL_EXPORTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Translations Exported")
            .setContentText("$projectName: $fileName")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Exported $fileName for $projectName"))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent ?: getAppLaunchPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        getAppIconBitmap()?.let { builder.setLargeIcon(it) }

        try {
            NotificationManagerCompat.from(context).notify(ID_EXPORT_RESULT, builder.build())
        } catch (_: SecurityException) {
            // Permission not granted
        }
    }

    fun showImportSuccess(projectName: String) {
        if (!hasPermission()) return

        val builder = NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Project Imported")
            .setContentText("$projectName is ready for translation.")
            .setAutoCancel(true)
            .setContentIntent(getAppLaunchPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        getAppIconBitmap()?.let { builder.setLargeIcon(it) }

        try {
            NotificationManagerCompat.from(context).notify(ID_IMPORT_RESULT, builder.build())
        } catch (_: SecurityException) {
            // Permission not granted
        }
    }

    fun cancel(notificationId: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (_: Exception) {
            // Ignored
        }
    }
}
