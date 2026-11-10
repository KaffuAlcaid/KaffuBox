// 让正在执行的文件和媒体任务在切换应用后继续处理并显示进度通知
package io.kaffubox.app

import android.app.*
import android.content.Intent
import android.os.IBinder
import io.kaffubox.core.TaskState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class TaskForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("Processing", "处理任务", NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun notification(count: Int) = Notification.Builder(this, "Processing").setSmallIcon(io.kaffubox.app.R.drawable.app_icon).setContentTitle("KaffuBox 正在处理").setContentText("$count 个任务正在进行").setContentIntent(intent).setOngoing(true).build()
        startForeground(1, notification(1))
        scope.launch {
            val controller = (application as KaffuApplication).controller
            controller.host.tasks.snapshots.collectLatest { tasks ->
                val active = tasks.count { it.state in setOf(TaskState.Queued, TaskState.Running, TaskState.Cancelling) }
                if (active == 0) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                else if (android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) manager.notify(1, notification(active))
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
