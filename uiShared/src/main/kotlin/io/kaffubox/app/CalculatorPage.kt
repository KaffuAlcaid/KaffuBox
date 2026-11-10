// 实现科学计算器的符号键盘、函数选择、角度模式、记忆、精度显示和可复用计算记录
package io.kaffubox.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kaffubox.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

data class CalculationRecord(val expression: String, val value: String, val angle: String)

class CalculatorState(preferences: AppPreferences) {
    var expression by mutableStateOf(TextFieldValue())
    var angleMode by mutableStateOf(preferences.calculatorAngle)
    var displayDigits by mutableStateOf(preferences.calculatorDigits)
    var displayFormat by mutableStateOf(preferences.calculatorFormat)
    var second by mutableStateOf(false)
    var answer by mutableStateOf("0")
    var memory by mutableStateOf("0")
    var preview by mutableStateOf<String?>("0")
    var error by mutableStateOf<String?>(null)
    var justEvaluated by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var functionGroup by mutableStateOf("trig")
    val history = mutableStateListOf<CalculationRecord>()

    fun insert(token: String, kind: String = "text") {
        if (justEvaluated && kind == "function") {
            val suffix = if (token in setOf("root(", "log(", "ncr(", "npr(", "mod(")) "," else ")"
            val text = token + "Ans" + suffix
            expression = TextFieldValue(text, TextRange(text.length))
            justEvaluated = false; error = null
            return
        }
        var text = expression.text
        var start = expression.selection.min
        var end = expression.selection.max
        if (justEvaluated) { text = if (kind in setOf("operator", "postfix")) "Ans" else ""; start = text.length; end = start; justEvaluated = false }
        val selected = text.substring(start, end)
        val inserted = when {
            kind == "function" && selected.isNotEmpty() -> token + selected + ")"
            kind == "postfix" && selected.isNotEmpty() -> "($selected)$token"
            token == "." && (start == 0 || text[start - 1] in "+-−×÷*/(,") -> "0."
            else -> token
        }
        if (text.isEmpty() && kind == "operator" && token !in setOf("+", "-", "−")) { text = "Ans"; start = text.length; end = start }
        expression = TextFieldValue(text.substring(0, start) + inserted + text.substring(end), TextRange(start + inserted.length))
        error = null
    }
    fun backspace() {
        val start = expression.selection.min
        val end = expression.selection.max
        val left = if (start == end) (start - 1).coerceAtLeast(0) else start
        expression = TextFieldValue(expression.text.removeRange(left, end), TextRange(left))
        justEvaluated = false; error = null
    }
    fun clear() { expression = TextFieldValue(); preview = "0"; error = null; justEvaluated = false }
}

fun formatNumber(value: String, digits: Int = 12, mode: String = "auto"): String {
    val decimal = value.toBigDecimalOrNull() ?: return value
    if (decimal.signum() == 0) return "0"
    val rounded = decimal.round(MathContext(digits, RoundingMode.HALF_EVEN)).stripTrailingZeros()
    val exponent = rounded.precision() - rounded.scale() - 1
    return when (mode) {
        "scientific" -> rounded.scaleByPowerOfTen(-exponent).stripTrailingZeros().toPlainString() + "e" + if (exponent >= 0) "+$exponent" else exponent.toString()
        "engineering" -> {
            val engineering = Math.floorDiv(exponent, 3) * 3
            rounded.scaleByPowerOfTen(-engineering).stripTrailingZeros().toPlainString() + if (engineering == 0) "" else "e" + if (engineering >= 0) "+$engineering" else engineering.toString()
        }
        else -> if (exponent in -6..18) rounded.toPlainString() else rounded.toString()
    }
}

private val FunctionGroups = linkedMapOf(
    "trig" to ("三角" to listOf("sin" to "sin(", "cos" to "cos(", "tan" to "tan(", "sin⁻¹" to "asin(", "cos⁻¹" to "acos(", "tan⁻¹" to "atan(", "sinh" to "sinh(", "cosh" to "cosh(", "tanh" to "tanh(")),
    "power" to ("幂与对数" to listOf("√x" to "sqrt(", "∛x" to "cbrt(", "ⁿ√x" to "root(", "ln" to "ln(", "log₁₀" to "log10(", "log₂" to "log2(", "eˣ" to "exp(", "logₐx" to "log(", "|x|" to "abs(")),
    "other" to ("组合与统计" to listOf("n!" to "factorial(", "nCr" to "ncr(", "nPr" to "npr(", "取余" to "mod(", "最小值" to "min(", "最大值" to "max(", "求和" to "sum(", "平均值" to "mean(", "取整" to "round("))
)

@Composable
fun CalculatorPage(controller: AppController) {
    val state = controller.calculator
    val compact = controller.isTouchDevice
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    suspend fun evaluate(expression: String = state.expression.text, angle: String = state.angleMode): String = (controller.execute("science.calculate", jsonObject("expression" to expression, "angleMode" to angle, "precision" to 34, "variables" to jsonObject("ans" to state.answer, "m" to state.memory))) as JsonObject).getValue("text").jsonPrimitive.content
    fun calculate() {
        if (state.busy || state.expression.text.isBlank()) return
        val missing = state.expression.text.count { it == '(' } - state.expression.text.count { it == ')' }
        if (missing > 0) { val text = state.expression.text + ")".repeat(missing); state.expression = TextFieldValue(text, TextRange(text.length)) }
        state.busy = true
        val expression = state.expression.text
        val angle = state.angleMode
        scope.launch {
            try {
                val value = evaluate(expression, angle)
                state.answer = value
                if (state.expression.text == expression && state.angleMode == angle) { state.preview = value; state.error = null; state.justEvaluated = true }
                state.history.add(0, CalculationRecord(expression, value, angle))
                if (state.history.size > 100) state.history.removeAt(state.history.lastIndex)
            } catch (_: CancellationException) { } catch (failure: Exception) { state.error = UiText.failure(failure) }
            finally { state.busy = false }
        }
    }
    fun insert(token: String, kind: String = "text") { state.insert(token, kind); focus.requestFocus() }
    LaunchedEffect(state.expression.text, state.angleMode) {
        if (state.expression.text.isBlank()) { state.preview = "0"; return@LaunchedEffect }
        delay(150)
        try { state.preview = evaluate() } catch (_: CancellationException) { } catch (_: Exception) { state.preview = null }
    }
    @Composable fun Key(label: String, modifier: Modifier = Modifier, accent: Boolean = false, primary: Boolean = false, action: () -> Unit) {
        FilledTonalButton(onClick = action, modifier = modifier.height(if (compact) 40.dp else 42.dp), contentPadding = PaddingValues(2.dp), shape = RoundedCornerShape(11.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = when { primary -> MaterialTheme.colorScheme.primary; accent -> MaterialTheme.colorScheme.primaryContainer; else -> MaterialTheme.colorScheme.surfaceVariant }, contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
            Text(label, fontSize = if (label.length <= 2) 19.sp else 14.sp, maxLines = 1)
        }
    }
    @Composable fun MainPanel(modifier: Modifier) {
        Panel(modifier, spacing = if (compact) 6.dp else 8.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("deg" to "DEG", "rad" to "RAD", "grad" to "GRAD").forEach { (mode, label) -> FilterChip(selected = state.angleMode == mode, onClick = { state.angleMode = mode; state.error = null; controller.rememberCalculator() }, label = { Text(label) }) }
                }
                if (state.memory.toBigDecimal().signum() != 0) Text("M", color = MaterialTheme.colorScheme.primary)
                if (compact) IconButton(onClick = { focus.requestFocus(); keyboard?.show() }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Keyboard, "输入表达式") }
                IconButton(onClick = { state.preview?.let(controller::copy) }, modifier = Modifier.size(if (compact) 36.dp else 48.dp), enabled = state.preview != null) { Icon(Icons.Outlined.ContentCopy, "复制结果") }
            }
            OutlinedTextField(state.expression, onValueChange = { state.expression = it; state.error = null; state.justEvaluated = false },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                        Key.Enter, Key.NumPadEnter -> { calculate(); true }
                        Key.Escape -> { state.clear(); true }
                        else -> false
                    }
                }, keyboardOptions = KeyboardOptions(showKeyboardOnFocus = !compact), singleLine = true, placeholder = { Text("0") }, isError = state.error != null, textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.End), shape = RoundedCornerShape(13.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                SelectionContainer {
                    Text(state.preview?.let { formatNumber(it, state.displayDigits, state.displayFormat) } ?: "—",
                        Modifier.padding(vertical = if (compact) 4.dp else 8.dp).horizontalScroll(rememberScrollState()), fontSize = if (compact) 30.sp else 32.sp, textAlign = TextAlign.End, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }
            InlineError(state.error)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth()) {
                listOf("MC", "MR", "M+", "M−", "MS").forEach { command ->
                    TextButton(onClick = {
                        when (command) {
                            "MC" -> state.memory = "0"
                            "MR" -> insert("M", "constant")
                            else -> scope.launch {
                                try {
                                    val value = if (state.expression.text.isBlank()) BigDecimal.ZERO else evaluate().toBigDecimal()
                                    state.memory = boundedDecimal(when (command) { "M+" -> state.memory.toBigDecimal().add(value, MathContext(34)); "M−" -> state.memory.toBigDecimal().subtract(value, MathContext(34)); else -> value }).stripTrailingZeros().toPlainString()
                                } catch (failure: Exception) { state.error = UiText.failure(failure) }
                            }
                        }
                    }, modifier = Modifier.weight(1f).then(if (compact) Modifier.height(36.dp) else Modifier), contentPadding = PaddingValues(2.dp)) { Text(command) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Key("2nd", Modifier.weight(1f), accent = state.second) { state.second = !state.second }
                    Key("π", Modifier.weight(1f)) { insert("π", "constant") }
                    Key("e", Modifier.weight(1f)) { insert("e", "constant") }
                    Key("(", Modifier.weight(1f)) { insert("(") }
                    Key(")", Modifier.weight(1f)) { insert(")") }
                    Key("⌫", Modifier.weight(1f)) { state.backspace(); focus.requestFocus() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    val functions = if (state.second) listOf("sin⁻¹" to "asin(", "cos⁻¹" to "acos(", "tan⁻¹" to "atan(", "eˣ" to "exp(", "10ˣ" to "10^(") else listOf("sin" to "sin(", "cos" to "cos(", "tan" to "tan(", "ln" to "ln(", "log" to "log10(")
                    functions.forEach { (label, value) -> Key(label, Modifier.weight(1f)) { insert(value, "function") } }
                    Key("AC", Modifier.weight(1f)) { state.clear(); focus.requestFocus() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Key(if (state.second) "x³" else "x²", Modifier.weight(1f)) { insert(if (state.second) "³" else "²", "postfix") }
                    Key("xʸ", Modifier.weight(1f)) { insert("^(", "postfix") }
                    Key(if (state.second) "∛x" else "√x", Modifier.weight(1f)) { insert(if (state.second) "cbrt(" else "sqrt(", "function") }
                    Key("n!", Modifier.weight(1f)) { insert("!", "postfix") }
                    Key("1/x", Modifier.weight(1f)) { insert("1/(", "function") }
                    Key("%", Modifier.weight(1f)) { insert("%", "postfix") }
                }
            }
            listOf(listOf("7", "8", "9", "÷"), listOf("4", "5", "6", "×"), listOf("1", "2", "3", "−"), listOf("±", "0", ".", "+"), listOf("Ans", "EXP", ",", "=")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    row.forEach { label ->
                        Key(label, Modifier.weight(1f), accent = label in setOf("÷", "×", "−", "+"), primary = label == "=") {
                            when (label) {
                                "=" -> calculate()
                                "±" -> { val text = if (state.justEvaluated) "-Ans" else if (state.expression.text.isBlank()) "-" else "-(" + state.expression.text + ")"; state.expression = TextFieldValue(text, TextRange(text.length)); state.justEvaluated = false }
                                "EXP" -> insert("×10^(", "operator")
                                "Ans" -> insert("Ans", "constant")
                                "," -> insert(",")
                                "÷", "×", "−", "+" -> insert(label, "operator")
                                else -> insert(label, "number")
                            }
                        }
                    }
                }
            }
        }
    }
    @Composable fun Aside(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(title = "函数") {
                ChoiceField("", state.functionGroup, FunctionGroups.map { it.key to it.value.first }, { state.functionGroup = it })
                FunctionGroups.getValue(state.functionGroup).second.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        row.forEach { (label, value) -> Key(label, Modifier.weight(1f)) { insert(value, "function") } }
                    }
                }
                ChoiceField("结果格式", state.displayFormat, listOf("auto" to "自动", "scientific" to "科学计数", "engineering" to "工程计数"), { state.displayFormat = it; controller.rememberCalculator() })
                ChoiceField("显示有效位数", state.displayDigits.toString(), listOf(8, 12, 16, 20, 34).map { it.toString() to "$it 位" }, { state.displayDigits = it.toInt(); controller.rememberCalculator() })
                Text("多参数函数用逗号分隔，例如 root(81,4)、log(8,2)、ncr(52,5)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("计算记录", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { state.history.clear() }, enabled = state.history.isNotEmpty()) { Icon(Icons.Outlined.DeleteOutline, "清空计算记录") }
                }
                if (state.history.isEmpty()) Text("计算后可在这里再次使用结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.history.take(12).forEach { record ->
                    Column(Modifier.fillMaxWidth().clickable { insert(record.value, "constant") }.padding(vertical = 9.dp)) {
                        Text(record.expression, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatNumber(record.value, state.displayDigits, state.displayFormat), Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                            IconButton(onClick = { controller.copy(record.value) }) { Icon(Icons.Outlined.ContentCopy, "复制结果", Modifier.size(17.dp)) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 800.dp) Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) { MainPanel(Modifier.weight(1.65f)); Aside(Modifier.weight(1f)) }
        else Column(verticalArrangement = Arrangement.spacedBy(18.dp)) { MainPanel(Modifier.fillMaxWidth()); Aside(Modifier.fillMaxWidth()) }
    }
}
