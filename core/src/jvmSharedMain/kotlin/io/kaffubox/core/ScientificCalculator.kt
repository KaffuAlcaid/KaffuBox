// 解析有界科学表达式，提供高精度十进制运算、角度模式、百分数、阶乘与组合函数
package io.kaffubox.core

import ch.obermuhlner.math.big.BigDecimalMath
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.serialization.json.*

object ScientificCalculator {
    val functionArities = linkedMapOf(
        "sin" to 1..1, "cos" to 1..1, "tan" to 1..1,
        "asin" to 1..1, "acos" to 1..1, "atan" to 1..1,
        "sinh" to 1..1, "cosh" to 1..1, "tanh" to 1..1,
        "asinh" to 1..1, "acosh" to 1..1, "atanh" to 1..1,
        "sqrt" to 1..1, "cbrt" to 1..1, "root" to 2..2,
        "ln" to 1..1, "log" to 1..2, "log10" to 1..1, "log2" to 1..1, "exp" to 1..1,
        "abs" to 1..1, "floor" to 1..1, "ceil" to 1..1, "round" to 1..2,
        "factorial" to 1..1, "ncr" to 2..2, "npr" to 2..2, "mod" to 2..2,
        "min" to 1..32, "max" to 1..32, "sum" to 1..32, "mean" to 1..32
    )

    fun catalog(): JsonObject = jsonObject(
        "angleModes" to listOf("deg", "rad", "grad"),
        "operators" to listOf("+", "-", "*", "/", "^", "!", "%"),
        "constants" to listOf("pi", "e", "tau"),
        "precision" to jsonObject("minimum" to 16, "maximum" to 100, "default" to 34),
        "functions" to functionArities.map { (name, arity) -> jsonObject("id" to name, "minArgs" to arity.first, "maxArgs" to arity.last) }
    )

    fun evaluate(
        expression: String,
        variables: Map<String, BigDecimal> = emptyMap(),
        angleMode: String = "rad",
        precision: Int = 34,
        checkpoint: () -> Unit = {}
    ): BigDecimal {
        requireArgument(precision in 16..100, "precision")
        requireArgument(angleMode in setOf("deg", "rad", "grad"), "angleMode")
        requireArgument(expression.isNotBlank() && expression.length <= 10_000, "expression")
        variables.values.forEach(::boundedDecimal)
        return ScientificParser(expression, variables, angleMode, precision, checkpoint).evaluate()
    }
}

fun decimalArgument(value: JsonElement?, key: String): BigDecimal {
    val content = (value as? JsonPrimitive)?.content ?: throw ToolFailure("numberRequired", jsonObject("key" to key))
    if (content.length > 10_000) throw ToolFailure("mathMagnitudeLimit")
    return boundedDecimal(content.toBigDecimalOrNull() ?: throw ToolFailure("numberRequired", jsonObject("key" to key)))
}

fun boundedDecimal(value: BigDecimal): BigDecimal {
    if (kotlin.math.abs(value.scale().toLong()) > 4096 || value.precision().toLong() - value.scale() > 4096) throw ToolFailure("mathMagnitudeLimit")
    return value
}

private class ScientificParser(
    expression: String,
    private val variables: Map<String, BigDecimal>,
    private val angleMode: String,
    precision: Int,
    private val checkpoint: () -> Unit
) {
    private val source = expression.replace('×', '*').replace('÷', '/').replace('−', '-')
        .replace('（', '(').replace('）', ')').replace('，', ',')
    private val outputContext = MathContext(precision, RoundingMode.HALF_EVEN)
    private val work = MathContext(precision + 12, RoundingMode.HALF_EVEN)
    private val pi by lazy { BigDecimalMath.pi(work) }
    private val epsilon = BigDecimal.ONE.scaleByPowerOfTen(-precision - 4)
    private var position = 0
    private var depth = 0
    private var steps = 0

    fun evaluate(): BigDecimal {
        try {
            val result = sum()
            whitespace()
            if (position != source.length) fail("mathSyntax")
            return boundedDecimal(result.round(outputContext)).stripTrailingZeros()
        } catch (failure: ArithmeticException) {
            throw ToolFailure("mathDomain", jsonObject("position" to position))
        }
    }

    private fun sum(): BigDecimal {
        var value = product()
        while (true) value = when {
            take('+') -> boundedDecimal(value.add(product(), work))
            take('-') -> boundedDecimal(value.subtract(product(), work))
            else -> return value
        }
    }

    private fun product(): BigDecimal {
        var value = unary()
        while (true) {
            value = when {
                take('*') -> boundedDecimal(value.multiply(unary(), work))
                take('/') -> divide(value, unary())
                beginsPrimary() -> boundedDecimal(value.multiply(unary(), work))
                else -> return value
            }
        }
    }

    private fun unary(): BigDecimal {
        step()
        if (++depth > 96) fail("mathDepthLimit")
        return try {
            when {
                take('+') -> unary()
                take('-') -> unary().negate(work)
                take('√') -> BigDecimalMath.sqrt(unary(), work)
                else -> power()
            }
        } finally { depth-- }
    }

    private fun power(): BigDecimal {
        val base = postfix()
        if (!take('^')) return base
        val exponent = unary()
        if (exponent.abs() > BigDecimal(10_000)) fail("mathMagnitudeLimit")
        if (base.signum() == 0 && exponent.signum() <= 0) fail("mathDomain")
        return boundedDecimal(BigDecimalMath.pow(base, exponent, work))
    }

    private fun postfix(): BigDecimal {
        var value = atom()
        while (true) value = when {
            take('!') -> factorial(value)
            take('%') -> value.movePointLeft(2)
            take('²') -> boundedDecimal(value.multiply(value, work))
            take('³') -> boundedDecimal(value.multiply(value, work).multiply(value, work))
            else -> return value
        }
    }

    private fun atom(): BigDecimal {
        step()
        whitespace()
        if (take('(')) {
            val value = sum()
            if (!take(')')) fail("mathClosingParenthesis")
            return value
        }
        if (position >= source.length) fail("mathOperandRequired")
        if (take('π')) return pi
        if (source[position].isLetter() || source[position] == '_') {
            val start = position
            while (position < source.length && (source[position].isLetterOrDigit() || source[position] == '_')) position++
            val name = source.substring(start, position)
            if (!take('(')) return variables[name] ?: variables[name.lowercase()] ?: when (name.lowercase()) {
                "pi" -> pi
                "e" -> BigDecimalMath.e(work)
                "tau" -> pi.multiply(BigDecimal(2), work)
                else -> throw ToolFailure("mathVariableUnknown", jsonObject("name" to name, "position" to start))
            }
            val arguments = mutableListOf<BigDecimal>()
            if (!take(')')) {
                do {
                    if (arguments.size >= 32) fail("mathArgumentLimit")
                    arguments += sum()
                } while (take(','))
                if (!take(')')) fail("mathClosingParenthesis")
            }
            return function(name.lowercase(), arguments)
        }
        val match = numberPattern.find(source, position)
        if (match == null || match.range.first != position) fail("mathSyntax")
        position = match.range.last + 1
        return boundedDecimal(match.value.toBigDecimal()).round(work)
    }

    private fun function(name: String, values: List<BigDecimal>): BigDecimal {
        val arity = ScientificCalculator.functionArities[name] ?: throw ToolFailure("mathFunctionUnknown", jsonObject("name" to name))
        if (values.size !in arity) throw ToolFailure("mathArgumentCount", jsonObject("name" to name, "minimum" to arity.first, "maximum" to arity.last))
        val x = values[0]
        val result = when (name) {
            "sin", "cos", "tan" -> {
                if (x.abs() > BigDecimal("1e12")) fail("mathAngleLimit")
                val angle = toRadians(x)
                val cosine = if (name == "sin") null else BigDecimalMath.cos(angle, work)
                val value = when (name) {
                    "sin" -> BigDecimalMath.sin(angle, work)
                    "cos" -> cosine!!
                    else -> {
                        if (cosine!!.abs() < epsilon) fail("mathDomain")
                        BigDecimalMath.sin(angle, work).divide(cosine, work)
                    }
                }
                if (value.abs() < epsilon && x.abs() > epsilon) BigDecimal.ZERO else value
            }
            "asin" -> fromRadians(BigDecimalMath.asin(x, work))
            "acos" -> fromRadians(BigDecimalMath.acos(x, work))
            "atan" -> fromRadians(BigDecimalMath.atan(x, work))
            "sinh" -> limitedExponential(x) { BigDecimalMath.sinh(it, work) }
            "cosh" -> limitedExponential(x) { BigDecimalMath.cosh(it, work) }
            "tanh" -> limitedExponential(x) { BigDecimalMath.tanh(it, work) }
            "asinh" -> BigDecimalMath.asinh(x, work)
            "acosh" -> BigDecimalMath.acosh(x, work)
            "atanh" -> BigDecimalMath.atanh(x, work)
            "sqrt" -> BigDecimalMath.sqrt(x, work)
            "cbrt" -> root(x, BigDecimal(3))
            "root" -> root(x, values[1])
            "ln" -> BigDecimalMath.log(x, work)
            "log", "log10" -> if (values.size == 1) BigDecimalMath.log10(x, work) else divide(BigDecimalMath.log(x, work), BigDecimalMath.log(values[1], work))
            "log2" -> divide(BigDecimalMath.log(x, work), BigDecimalMath.log(BigDecimal(2), work))
            "exp" -> limitedExponential(x) { BigDecimalMath.exp(it, work) }
            "abs" -> x.abs()
            "floor" -> x.setScale(0, RoundingMode.FLOOR)
            "ceil" -> x.setScale(0, RoundingMode.CEILING)
            "round" -> {
                val scale = if (values.size == 2) integer(values[1], -100, 100) else 0
                x.setScale(scale, RoundingMode.HALF_EVEN)
            }
            "factorial" -> factorial(x)
            "ncr", "npr" -> {
                val n = integer(x, 0, 10_000)
                val r = integer(values[1], 0, n)
                var result = BigInteger.ONE
                val count = if (name == "ncr") minOf(r, n - r) else r
                for (index in 1..count) {
                    step()
                    result = result.multiply(BigInteger.valueOf((n - index + 1).toLong()))
                    if (name == "ncr") result = result.divide(BigInteger.valueOf(index.toLong()))
                }
                BigDecimal(result).round(work)
            }
            "mod" -> {
                if (values[1].signum() == 0) fail("mathDivideByZero")
                x.remainder(values[1], work)
            }
            "min" -> values.minOrNull()!!
            "max" -> values.maxOrNull()!!
            "sum", "mean" -> values.fold(BigDecimal.ZERO) { total, value -> total.add(value, work) }.let { if (name == "mean") divide(it, BigDecimal(values.size)) else it }
            else -> fail("mathFunctionUnknown")
        }
        return boundedDecimal(result)
    }

    private fun root(value: BigDecimal, degree: BigDecimal): BigDecimal {
        val n = integer(degree, 1, 1000)
        if (value.signum() < 0) {
            if (n % 2 == 0) fail("mathDomain")
            return BigDecimalMath.root(value.negate(), BigDecimal(n), work).negate()
        }
        return BigDecimalMath.root(value, BigDecimal(n), work)
    }

    private fun factorial(value: BigDecimal): BigDecimal {
        val n = integer(value, 0, 10_000)
        var result = BigInteger.ONE
        for (index in 2..n) { step(); result = result.multiply(BigInteger.valueOf(index.toLong())) }
        return boundedDecimal(BigDecimal(result).round(work))
    }

    private fun integer(value: BigDecimal, minimum: Int, maximum: Int): Int {
        val number = try { value.intValueExact() } catch (_: ArithmeticException) { fail("mathIntegerRequired") }
        if (number !in minimum..maximum) fail("mathArgumentRange")
        return number
    }

    private fun limitedExponential(value: BigDecimal, calculate: (BigDecimal) -> BigDecimal): BigDecimal {
        if (value.abs() > BigDecimal(9000)) fail("mathMagnitudeLimit")
        return calculate(value)
    }

    private fun toRadians(value: BigDecimal): BigDecimal = when (angleMode) {
        "deg" -> value.multiply(pi, work).divide(BigDecimal(180), work)
        "grad" -> value.multiply(pi, work).divide(BigDecimal(200), work)
        else -> value
    }

    private fun fromRadians(value: BigDecimal): BigDecimal = when (angleMode) {
        "deg" -> value.multiply(BigDecimal(180), work).divide(pi, work)
        "grad" -> value.multiply(BigDecimal(200), work).divide(pi, work)
        else -> value
    }

    private fun divide(left: BigDecimal, right: BigDecimal): BigDecimal {
        if (right.signum() == 0) fail("mathDivideByZero")
        return boundedDecimal(left.divide(right, work))
    }

    private fun beginsPrimary(): Boolean {
        whitespace()
        if (position >= source.length) return false
        val character = source[position]
        return character == '(' || character == 'π' || character == '√' || character.isLetter() || character == '_'
    }

    private fun whitespace() { while (position < source.length && source[position].isWhitespace()) position++ }
    private fun take(character: Char): Boolean { whitespace(); if (position < source.length && source[position] == character) { position++; return true }; return false }
    private fun step() { if (++steps > 40_000) fail("mathOperationLimit"); checkpoint() }
    private fun fail(code: String): Nothing = throw ToolFailure(code, jsonObject("position" to position))

    companion object {
        private val numberPattern = Regex("(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")
    }
}
