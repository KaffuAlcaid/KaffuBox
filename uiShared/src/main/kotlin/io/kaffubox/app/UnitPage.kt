// 提供按物理量分类的实时单位换算、单位交换和精确结果复制
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.kaffubox.core.jsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

class UnitState {
    var dimensions by mutableStateOf<Map<String, List<String>>>(emptyMap())
    var dimension by mutableStateOf("length")
    var from by mutableStateOf("m")
    var to by mutableStateOf("cm")
    var input by mutableStateOf("1")
    var result by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
}

private val DimensionNames = mapOf("length" to "长度", "mass" to "质量", "area" to "面积", "volume" to "体积", "time" to "时间", "speed" to "速度", "pressure" to "压力", "energy" to "能量", "power" to "功率", "frequency" to "频率", "data" to "数据容量", "dataRate" to "传输速率", "temperature" to "温度", "angle" to "角度")
private val UnitNames = mapOf("nm" to "纳米", "um" to "微米", "mm" to "毫米", "cm" to "厘米", "dm" to "分米", "m" to "米", "km" to "千米", "in" to "英寸", "ft" to "英尺", "yd" to "码", "mi" to "英里", "nmi" to "海里", "ug" to "微克", "mg" to "毫克", "g" to "克", "kg" to "千克", "t" to "吨", "oz" to "盎司", "lb" to "磅", "mm2" to "平方毫米", "cm2" to "平方厘米", "m2" to "平方米", "km2" to "平方千米", "ha" to "公顷", "ft2" to "平方英尺", "acre" to "英亩", "mL" to "毫升", "cm3" to "立方厘米", "L" to "升", "m3" to "立方米", "galUS" to "美制加仑", "galUK" to "英制加仑", "us" to "微秒", "ms" to "毫秒", "s" to "秒", "min" to "分钟", "h" to "小时", "day" to "天", "week" to "周", "C" to "摄氏度", "F" to "华氏度", "K" to "开尔文", "rad" to "弧度", "deg" to "度", "grad" to "百分度", "turn" to "圈", "bit" to "比特", "B" to "字节", "kn" to "节", "cal" to "卡路里", "kcal" to "千卡", "hp" to "机械马力")

@Composable
fun UnitPage(controller: AppController) {
    val state = controller.units
    LaunchedEffect(Unit) {
        try {
            val catalog = controller.execute("science.unitCatalog", jsonObject()) as JsonObject
            state.dimensions = catalog.getValue("dimensions").jsonArray.associate { item ->
                item.jsonObject.getValue("id").jsonPrimitive.content to item.jsonObject.getValue("units").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            }
        } catch (failure: Exception) { state.error = UiText.failure(failure) }
    }
    LaunchedEffect(state.input, state.from, state.to) {
        state.result = ""; state.error = null
        if (state.input.isBlank()) return@LaunchedEffect
        delay(120)
        try { state.result = (controller.execute("science.unit", jsonObject("value" to state.input, "from" to state.from, "to" to state.to)) as JsonObject).getValue("text").jsonPrimitive.content }
        catch (_: CancellationException) { } catch (failure: Exception) { state.error = UiText.failure(failure) }
    }
    Panel(Modifier.fillMaxWidth()) {
        ChoiceField("换算类别", state.dimension, state.dimensions.keys.map { it to (DimensionNames[it] ?: it) }, {
            state.dimension = it; val units = state.dimensions.getValue(it); state.from = units.first(); state.to = units.getOrElse(1) { units.first() }
        })
        val choices = state.dimensions[state.dimension].orEmpty().map { it to (UnitNames[it]?.let { name -> "$name  $it" } ?: it) }
        OutlinedTextField(state.input, { state.input = it }, Modifier.fillMaxWidth(), label = { Text("数值") }, singleLine = true, isError = state.error != null)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceField("原单位", state.from, choices, { state.from = it }, Modifier.weight(1f))
            IconButton(onClick = { val old = state.from; state.from = state.to; state.to = old }) { Icon(Icons.Outlined.SwapHoriz, "交换单位") }
            ChoiceField("目标单位", state.to, choices, { state.to = it }, Modifier.weight(1f))
        }
        InlineError(state.error)
        HorizontalDivider()
        Text("换算结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(state.result.ifBlank { "—" }, style = MaterialTheme.typography.headlineMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controller.copy(state.result) }, enabled = state.result.isNotEmpty()) { Text("复制结果") }
            TextButton(onClick = { val value = state.result; val unit = state.to; state.to = state.from; state.from = unit; state.input = value }, enabled = state.result.isNotEmpty()) { Text("使用结果反向换算") }
        }
        if (state.dimension in setOf("data", "dataRate")) Text("KB、MB、GB 按 1000 进位，KiB、MiB、GiB 按 1024 进位", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
