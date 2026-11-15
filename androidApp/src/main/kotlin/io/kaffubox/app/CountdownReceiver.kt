// 接收系统倒计时闹钟，校验当前计时标识后发出到时通知并保留弹窗状态
package io.kaffubox.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CountdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "io.kaffubox.COUNTDOWN") (context.applicationContext as KaffuApplication).controller.countdownReminder.receive(intent.getStringExtra("timerId"))
    }
}
