// 实现受限数学表达式、单位换算、矩阵运算、数值求根、曲线采样与日期时区计算
package io.kaffubox.core

import kotlinx.serialization.json.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.math.BigDecimal
import java.time.*
import kotlin.math.*

object ScienceTools {
    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("science.calculatorCatalog", Workbench.Science) { _, _ -> ScientificCalculator.catalog() },
        defineTool("science.calculate", Workbench.Science, mapOf("expression" to "string", "variables" to "object", "angleMode" to "string", "precision" to "integer"), listOf("expression")) { args, _ ->
            val variables = args.objectValue("variables").mapValues { decimalArgument(it.value, it.key) }
            val precision = args.int("precision", 34)
            val angleMode = args.string("angleMode", "rad")
            val cancellation = currentCoroutineContext()
            val value = ScientificCalculator.evaluate(args.string("expression"), variables, angleMode, precision) { cancellation.ensureActive() }
            jsonObject("value" to value, "text" to value.toPlainString(), "precision" to precision, "angleMode" to angleMode)
        },
        defineTool("science.unitCatalog", Workbench.Science) { _, _ -> UnitCatalog.catalog() },
        defineTool("science.unit", Workbench.Science, mapOf("value" to "number", "from" to "string", "to" to "string", "precision" to "integer"), listOf("value", "from", "to")) { args, _ ->
            val value = UnitCatalog.convert(decimalArgument(args["value"], "value"), args.string("from"), args.string("to"), args.int("precision", 34))
            jsonObject("value" to value, "text" to value.toPlainString(), "unit" to args.string("to"))
        },
        defineTool("science.matrix", Workbench.Science, mapOf("matrix" to "array", "operation" to "string", "right" to "array", "scalar" to "number", "precision" to "integer"), listOf("matrix", "operation")) { args, _ ->
            val cancellation = currentCoroutineContext()
            MatrixCalculator.calculate(args) { cancellation.ensureActive() }
        },
        defineTool("science.root", Workbench.Science, mapOf("expression" to "string", "lower" to "number", "upper" to "number", "tolerance" to "number"), listOf("expression", "lower", "upper")) { args, context ->
            val expression = args.string("expression")
            var lower = args.number("lower"); var upper = args.number("upper"); val tolerance = args.number("tolerance", 1e-10)
            requireArgument(lower < upper && tolerance > 0 && tolerance < 1, "interval")
            fun value(x: Double) = MathExpression(expression, mapOf("x" to x)).evaluate()
            var lowValue = value(lower); val upperValue = value(upper)
            requireArgument(lowValue == 0.0 || upperValue == 0.0 || sign(lowValue) != sign(upperValue), "rootBracket")
            var root = if (lowValue == 0.0) lower else upper
            var residual = if (lowValue == 0.0) lowValue else upperValue
            var iterations = 0
            if (lowValue != 0.0 && upperValue != 0.0) {
                for (iteration in 1..512) {
                    context.checkpoint()
                    root = lower / 2 + upper / 2; residual = value(root); iterations = iteration
                    if (abs(residual) <= tolerance) break
                    if (root == lower || root == upper) break
                    if (sign(residual) == sign(lowValue)) { lower = root; lowValue = residual } else upper = root
                }
            }
            jsonObject("root" to root, "residual" to residual, "iterations" to iterations, "converged" to (abs(residual) <= tolerance))
        },
        defineTool("science.sampleFunction", Workbench.Science, mapOf("expression" to "string", "start" to "number", "end" to "number", "count" to "integer"), listOf("expression", "start", "end")) { args, context ->
            val count = args.int("count", 201); val start = args.number("start"); val end = args.number("end"); val expression = args.string("expression")
            requireArgument(count in 2..10_000 && start < end, "range")
            val points = (0 until count).map { i ->
                context.checkpoint(); val x = start + (end - start) * i / (count - 1)
                val y = try { MathExpression(expression, mapOf("x" to x)).evaluate() } catch (_: ArithmeticException) { null } catch (failure: ToolFailure) { if (failure.code in setOf("mathDomain", "mathDivideByZero", "mathMagnitudeLimit")) null else throw failure }
                jsonObject("x" to x, "y" to y)
            }
            jsonObject("points" to points)
        },
        defineTool("science.date", Workbench.Science, mapOf("date" to "string", "days" to "integer", "until" to "string"), listOf("date")) { args, _ ->
            val date = LocalDate.parse(args.string("date")); val until = args.optionalString("until")?.let(LocalDate::parse)
            if (until != null) jsonObject("days" to java.time.temporal.ChronoUnit.DAYS.between(date, until))
            else {
                val shifted = date.plusDays(args.long("days", 0))
                jsonObject("date" to shifted.toString(), "dayOfWeek" to shifted.dayOfWeek.value, "leapYear" to shifted.isLeapYear)
            }
        },
        defineTool("science.timezone", Workbench.Science, mapOf("instant" to "string", "zones" to "array"), listOf("zones")) { args, _ ->
            val instant = args.optionalString("instant")?.let(Instant::parse) ?: Instant.now()
            jsonObject("values" to args.strings("zones").associateWith { instant.atZone(ZoneId.of(it)).toString() })
        }
    )

}

class MathExpression(private val expression: String, private val variables: Map<String, Double> = emptyMap()) {
    fun evaluate(): Double {
        val converted = variables.mapValues { (_, value) ->
            if (!value.isFinite()) throw ToolFailure("mathDomain")
            BigDecimal.valueOf(value)
        }
        val value = ScientificCalculator.evaluate(expression, converted).toDouble()
        if (!value.isFinite()) throw ToolFailure("mathMagnitudeLimit")
        return value
    }
}
