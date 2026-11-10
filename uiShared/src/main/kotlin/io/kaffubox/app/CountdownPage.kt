// 提供基于单调时钟的倒计时与暂停恢复，并在到时通知用户
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

class CountdownState {
    var minutes by mutableStateOf("5")
    var seconds by mutableStateOf("0")
    var remaining by mutableStateOf(0L)
    var deadline = 0L
    var running by mutableStateOf(false)
    var started by mutableStateOf(false)
    var finished by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    fun start() {
        try {
            if (!started) {
                val mins = minutes.toLong(); val secs = seconds.toLong()
                require(mins in 0..10080 && secs in 0..59)
                remaining = (mins * 60 + secs) * 1000
                require(remaining > 0)
            }
            deadline = System.nanoTime() + remaining * 1_000_000; running = true; started = true; finished = false; error = null
        } catch (_: Exception) { error = "请输入有效时长，秒数应在 0 至 59 之间，最长 7 天" }
    }
    fun pause() { remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0); running = false }
    fun reset() { running = false; started = false; finished = false; remaining = 0; error = null }
}

@Composable
fun CountdownTicker(controller: AppController) {
    val state = controller.timer
    LaunchedEffect(state.running) {
        while (state.running) {
            state.remaining = ((state.deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
            if (state.remaining == 0L) {
                state.running = false; state.started = false; state.finished = true
                controller.message = "倒计时结束"; controller.notify?.invoke("倒计时结束")
                controller.playTimerSound()
            } else delay(100)
        }
    }
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
        Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { if (state.running) state.pause() else state.start() }) { Text(if (state.running) "暂停" else if (state.started) "继续" else "开始") }
            OutlinedButton(onClick = state::reset) { Text("重置") }
        }
    }
}
