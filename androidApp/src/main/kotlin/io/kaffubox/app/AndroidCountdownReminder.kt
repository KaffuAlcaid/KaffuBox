// 使用系统闹钟与高优先级通知维持后台倒计时，并恢复未确认的到时弹窗
package io.kaffubox.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.util.UUID

class AndroidCountdownReminder(private val controller: AppController) {
    private val context = controller.context
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val saved = context.getSharedPreferences("CountdownReminder", Context.MODE_PRIVATE)
    private val channel = "Countdown"
    private val notificationId = 2401
    private fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private fun intent() = Intent(context, CountdownReceiver::class.java).setAction("io.kaffubox.COUNTDOWN")
    private fun cancelAlarm() {
        PendingIntent.getBroadcast(context, notificationId, intent(), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { alarms.cancel(it); it.cancel() }
    }
    fun refreshPermissions() {
        controller.countdownPermissionHint = when {
            !notifications.areNotificationsEnabled() || Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> "前台到时会弹窗，允许通知后才能在后台显示到时提醒"
            Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms() -> "前台到时会弹窗，后台尚未允许精确闹钟，省电模式下提醒可能延迟"
            notifications.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE -> "倒计时通知频道已关闭，可在系统通知设置中重新开启"
            else -> null
        }
    }
    fun openPermissionSettings() {
        val target = if (!notifications.areNotificationsEnabled() || notifications.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)) else return
        context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun schedule() {
        cancelAlarm(); notifications.cancel(notificationId)
        val id = UUID.randomUUID().toString()
        saved.edit().putString("activeId", id).putLong("deadline", controller.timer.deadline / 1_000_000).putLong("duration", controller.timer.durationMillis).putInt("boot", bootCount()).putBoolean("completed", false).commit()
        val pending = PendingIntent.getBroadcast(context, notificationId, intent().putExtra("timerId", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val deadline = controller.timer.deadline / 1_000_000
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            try { alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pending) }
            catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pending) }
        } else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pending)
        refreshPermissions()
    }
    fun pause() { cancelAlarm(); saved.edit().remove("activeId").putBoolean("completed", false).commit(); notifications.cancel(notificationId) }
    fun dismiss() { controller.timer.alertVisible = false; saved.edit().putBoolean("completed", false).commit(); notifications.cancel(notificationId) }
    fun restore() {
        val duration = saved.getLong("duration", 0)
        if (duration !in 1..604800000L) return
        if (saved.getBoolean("completed", false)) controller.timer.restore(0, duration, true)
        else if (saved.contains("activeId")) {
            if (saved.getInt("boot", -1) != bootCount()) { pause(); controller.message = "设备已重启，之前的倒计时已停止"; return }
            controller.timer.restore(saved.getLong("deadline", 0) * 1_000_000, duration, false)
            watchCountdown(controller)
        }
        refreshPermissions()
    }
    fun receive(id: String?) {
        if (id == null || id != saved.getString("activeId", null)) return
        if (!controller.timer.running) controller.timer.restore(saved.getLong("deadline", 0) * 1_000_000, saved.getLong("duration", 0), false)
        if (controller.timer.finish()) controller.onCountdownFinished()
    }
    fun complete() {
        cancelAlarm(); saved.edit().remove("activeId").putBoolean("completed", true).putLong("duration", controller.timer.durationMillis).commit()
        if (controller.appVisible) { controller.playTimerSound(); return }
        notifications.createNotificationChannel(NotificationChannel(channel, "倒计时到时提醒", NotificationManager.IMPORTANCE_HIGH).apply { description = "倒计时结束时显示提醒"; enableVibration(true) })
        val open = PendingIntent.getActivity(context, notificationId, Intent(context, MainActivity::class.java).setAction("io.kaffubox.COUNTDOWN_FINISHED").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notifications.notify(notificationId, Notification.Builder(context, channel).setSmallIcon(R.drawable.app_icon).setContentTitle("倒计时结束").setContentText("设定的 ${mediaDuration(controller.timer.durationMillis / 1000.0)} 已结束，点击查看").setCategory(Notification.CATEGORY_ALARM).setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
