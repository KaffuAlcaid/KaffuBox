// 在应用主进程内持有工具状态，避免界面旋转或后台任务丢失宿主
package io.kaffubox.app

import android.app.Application
import android.os.Build

class KaffuApplication : Application() {
    lateinit var controller: AppController
        private set
    override fun onCreate() {
        super.onCreate()
        val process = if (Build.VERSION.SDK_INT >= 28) getProcessName() else java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
        if (process == packageName) controller = AppController(this)
    }
}
