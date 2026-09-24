package com.knowyourcase.notice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("notice_id", -1)
        if (id < 0) return Result.success()
        val notice = NoticeDatabase.get(applicationContext).notices().byId(id) ?: return Result.success()
        if (notice.serviceStatus == "SERVED") return Result.success()

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("notice_due", "Notice reminders", NotificationManager.IMPORTANCE_HIGH)
        )

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val text = buildString {
            append(notice.caseNumber.ifBlank { notice.cnr })
            if (notice.caseTitle.isNotBlank()) append(" • ").append(notice.caseTitle)
            if (notice.nextHearing.isNotBlank()) append("\nNext hearing: ").append(notice.nextHearing)
            append("\nNot served")
            if (notice.processServer.isBlank()) append(" • Process server not assigned")
        }

        val notification = NotificationCompat.Builder(applicationContext, "notice_due")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Notice needs service")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(id.toInt(), notification)
        return Result.success()
    }

    companion object {
        private val offsets = intArrayOf(10, 7, 3, 1, 0)

        fun reschedule(context: Context, notice: NoticeEntity) {
            cancel(context, notice.id)
            if (notice.serviceStatus == "SERVED" || notice.nextHearing.isBlank()) return
            val hearing = runCatching { LocalDate.parse(notice.nextHearing) }.getOrNull() ?: return

            offsets.forEach { daysBefore ->
                val trigger = hearing.minusDays(daysBefore.toLong())
                    .atTime(8, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                val delay = Duration.between(Instant.now(), trigger).toMillis()
                if (delay > 0) {
                    val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf("notice_id" to notice.id))
                        .build()
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "notice_" + notice.id + "_" + daysBefore,
                        ExistingWorkPolicy.REPLACE,
                        request
                    )
                }
            }
        }

        fun cancel(context: Context, id: Long) {
            offsets.forEach {
                WorkManager.getInstance(context).cancelUniqueWork("notice_" + id + "_" + it)
            }
        }
    }
}
