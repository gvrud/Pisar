package ru.pisar.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import ru.pisar.app.R
import ru.pisar.app.model.Job
import ru.pisar.app.ui.JobActivity
import ru.pisar.app.work.TranscribeService

object Notifications {

    const val CHANNEL_PROGRESS = "progress"
    const val CHANNEL_DONE = "done"
    const val PROGRESS_ID = 1001
    const val DONE_ID = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val progress = NotificationChannel(
            CHANNEL_PROGRESS,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }

        val done = NotificationChannel(
            CHANNEL_DONE,
            context.getString(R.string.notif_channel_done),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_done_desc)
        }

        manager.createNotificationChannel(progress)
        manager.createNotificationChannel(done)
    }

    fun progress(context: Context, job: Job): Notification {
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, JobActivity::class.java).apply {
                putExtra(JobActivity.EXTRA_JOB_ID, job.id)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            flags(),
        )
        val cancel = PendingIntent.getService(
            context,
            2,
            Intent(context, TranscribeService::class.java).apply {
                action = TranscribeService.ACTION_CANCEL
                putExtra(TranscribeService.EXTRA_JOB_ID, job.id)
            },
            flags(),
        )

        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.notif_progress_title, job.title))
            .setContentText(job.stage)
            .setProgress(100, job.progress.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.action_cancel), cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun starting(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.status_preparing))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun done(context: Context, job: Job): Notification {
        val open = PendingIntent.getActivity(
            context,
            3,
            Intent(context, JobActivity::class.java).apply {
                putExtra(JobActivity.EXTRA_JOB_ID, job.id)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            flags(),
        )
        return NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.notif_done_title))
            .setContentText(job.title)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }

    fun error(context: Context, job: Job): Notification {
        val open = PendingIntent.getActivity(
            context,
            4,
            Intent(context, JobActivity::class.java).apply {
                putExtra(JobActivity.EXTRA_JOB_ID, job.id)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            flags(),
        )
        return NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.notif_error_title))
            .setContentText(job.error ?: job.title)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }

    private fun flags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
