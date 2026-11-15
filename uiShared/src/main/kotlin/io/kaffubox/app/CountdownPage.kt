// 管理倒计时、暂停恢复、设置记忆与双端到时弹窗，计时任务独立于页面生命周期
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext

class CountdownState(options: Map<String, String> = emptyMap(), private val clock: () -> Long = System::nanoTime) {
    var minutes by mutableStateOf(options["minutes"] ?: "5")
    var seconds by mutableStateOf(options["seconds"] ?: "0")
    var remaining by mutableStateOf(0L)
    var deadline = 0L
    var running by mutableStateOf(false)
    var started by mutableStateOf(false)
    var finished by mutableStateOf(false)
    var alertVisible by mutableStateOf(false)
    var durationMillis by mutableStateOf(0L)
    var error by mutableStateOf<String?>(null)
    fun start() {
        try {
            if (!started) {
                val mins = minutes.toLong(); val secs = seconds.toLong()
                require(mins in 0..10080 && secs in 0..59)
                remaining = (mins * 60 + secs) * 1000
                require(remaining in 1..604800000L)
                durationMillis = remaining
            }
            deadline = clock() + remaining * 1_000_000; running = true; started = true; finished = false; alertVisible = false; error = null
        } catch (_: Exception) { error = "请输入有效时长，秒数应在 0 至 59 之间，最长 7 天" }
    }
    fun pause() { remaining = ((deadline - clock()) / 1_000_000).coerceAtLeast(0); running = false }
    fun reset() { running = false; started = false; finished = false; alertVisible = false; remaining = 0; error = null }
    fun tick(): Boolean {
        if (!running) return false
        remaining = ((deadline - clock()) / 1_000_000).coerceAtLeast(0)
        return remaining == 0L && finish()
    }
    fun finish(): Boolean {
        if (!running) return false
        remaining = 0; running = false; started = false; finished = true; alertVisible = true
        return true
    }
    fun restore(deadlineNanos: Long, duration: Long, completed: Boolean) {
        durationMillis = duration; minutes = (duration / 60000).toString(); seconds = (duration / 1000 % 60).toString()
        deadline = deadlineNanos; running = !completed; started = !completed; finished = completed; alertVisible = completed
        remaining = if (completed) 0 else ((deadline - clock()) / 1_000_000).coerceAtLeast(0)
    }
}

fun watchCountdown(controller: AppController) {
    val state = controller.timer
    controller.countdownJob?.cancel()
    controller.countdownJob = controller.launchWork {
        while (currentCoroutineContext().isActive && state.running) {
            if (state.tick()) controller.onCountdownFinished() else delay(100)
        }
    }
}

fun beginCountdown(controller: AppController): Boolean {
    controller.timer.start()
    if (!controller.timer.running) return false
    if (controller.preferences.rememberParameters) controller.updatePreferences(controller.preferences.copy(toolOptions = controller.preferences.toolOptions + ("daily.countdown" to mapOf("minutes" to controller.timer.minutes, "seconds" to controller.timer.seconds))))
    watchCountdown(controller)
    return true
}

@Composable
fun CountdownReminder(controller: AppController) {
    val timer = controller.timer
    if (!timer.alertVisible) return
    AlertDialog(onDismissRequest = controller::dismissCountdown, title = { Text("倒计时结束") }, text = { Text("设定的 ${mediaDuration(timer.durationMillis / 1000.0)} 已结束") },
        confirmButton = { Button(onClick = controller::dismissCountdown) { Text("关闭提醒") } },
        dismissButton = { TextButton(onClick = {
            val duration = timer.durationMillis
            controller.resetCountdown()
            timer.minutes = (duration / 60000).toString(); timer.seconds = (duration / 1000 % 60).toString()
            controller.startCountdown()
        }) { Text("再来一次") } })
}

@Composable
fun CountdownPage(controller: AppController) {
    val state = controller.timer
    Panel(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(state.minutes, { state.minutes = it }, Modifier.weight(1f), label = { Text("分钟") }, enabled = !state.started, singleLine = true)
            OutlinedTextField(state.seconds, { state.seconds = it }, Modifier.weight(1f), label = { Text("秒") }, enabled = !state.started, singleLine = true)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 5, 10, 25, 60).forEach { value -> SuggestionChip(onClick = { state.reset(); state.minutes = value.toString(); state.seconds = "0" }, label = { Text("$value 分钟") }, enabled = !state.running) }
        }
        val total = (state.remaining + 999) / 1000
        Text("%02d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60), Modifier.align(Alignment.CenterHorizontally).padding(vertical = 32.dp), fontSize = if (controller.isTouchDevice) 48.sp else 64.sp, fontFamily = FontFamily.Monospace)
        if (state.finished) Text("时间到", Modifier.align(Alignment.CenterHorizontally), color = MaterialTheme.colorScheme.primary)
        InlineError(state.error)
        controller.countdownPermissionHint?.let { hint ->
            Text(hint, style = MaterialTheme.typography.bodySmall)
            controller.requestCountdownPermissions?.let { request -> TextButton(onClick = request) { Text("设置后台提醒权限") } }
        }
        Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                if (state.running) controller.pauseCountdown() else controller.startCountdown()
            }) { Text(if (state.running) "暂停" else if (state.started) "继续" else "开始") }
            OutlinedButton(onClick = controller::resetCountdown) { Text("重置") }
        }
    }
}
