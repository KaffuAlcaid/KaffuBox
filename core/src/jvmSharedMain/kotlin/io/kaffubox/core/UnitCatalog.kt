// 定义可查询的单位分类与精确换算关系，处理温度偏移和角度中的圆周率
package io.kaffubox.core

import ch.obermuhlner.math.big.BigDecimalMath
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.serialization.json.*

object UnitCatalog {
    private data class UnitDefinition(val id: String, val dimension: String, val factor: String, val offset: String = "0", val piBased: Boolean = false)
    private val definitions = buildList {
        fun add(dimension: String, vararg units: Pair<String, String>) { units.forEach { (id, factor) -> add(UnitDefinition(id, dimension, factor)) } }
        add("length", "nm" to "0.000000001", "um" to "0.000001", "mm" to "0.001", "cm" to "0.01", "dm" to "0.1", "m" to "1", "km" to "1000", "in" to "0.0254", "ft" to "0.3048", "yd" to "0.9144", "mi" to "1609.344", "nmi" to "1852")
        add("mass", "ug" to "0.000000001", "mg" to "0.000001", "g" to "0.001", "kg" to "1", "t" to "1000", "oz" to "0.028349523125", "lb" to "0.45359237")
        add("area", "mm2" to "0.000001", "cm2" to "0.0001", "m2" to "1", "km2" to "1000000", "ha" to "10000", "ft2" to "0.09290304", "acre" to "4046.8564224")
        add("volume", "mL" to "0.000001", "cm3" to "0.000001", "L" to "0.001", "m3" to "1", "galUS" to "0.003785411784", "galUK" to "0.00454609")
        add("time", "us" to "0.000001", "ms" to "0.001", "s" to "1", "min" to "60", "h" to "3600", "day" to "86400", "week" to "604800")
        add("speed", "m/s" to "1", "km/h" to "1/3.6", "mph" to "0.44704", "kn" to "1852/3600")
        add("pressure", "Pa" to "1", "kPa" to "1000", "MPa" to "1000000", "bar" to "100000", "atm" to "101325", "psi" to "6894.757293168361")
        add("energy", "J" to "1", "kJ" to "1000", "MJ" to "1000000", "cal" to "4.184", "kcal" to "4184", "Wh" to "3600", "kWh" to "3600000")
        add("power", "W" to "1", "kW" to "1000", "MW" to "1000000", "hp" to "745.69987158227022")
        add("frequency", "Hz" to "1", "kHz" to "1000", "MHz" to "1000000", "GHz" to "1000000000")
        add("data", "bit" to "0.125", "B" to "1", "KB" to "1000", "MB" to "1000000", "GB" to "1000000000", "TB" to "1000000000000", "KiB" to "1024", "MiB" to "1048576", "GiB" to "1073741824", "TiB" to "1099511627776")
        add("dataRate", "bps" to "1", "kbps" to "1000", "Mbps" to "1000000", "Gbps" to "1000000000", "B/s" to "8", "KB/s" to "8000", "MB/s" to "8000000", "MiB/s" to "8388608")
        add(UnitDefinition("K", "temperature", "1"))
        add(UnitDefinition("C", "temperature", "1", "273.15"))
        add(UnitDefinition("F", "temperature", "5/9", "459.67"))
        add(UnitDefinition("rad", "angle", "1"))
        add(UnitDefinition("deg", "angle", "1/180", piBased = true))
        add(UnitDefinition("grad", "angle", "1/200", piBased = true))
        add(UnitDefinition("turn", "angle", "2", piBased = true))
    }
    private val byId = definitions.associateBy(UnitDefinition::id)

    fun catalog(): JsonObject = jsonObject(
        "dimensions" to definitions.groupBy(UnitDefinition::dimension).map { (dimension, units) ->
            jsonObject("id" to dimension, "units" to units.map { jsonObject("id" to it.id) })
        },
        "dataBase" to jsonObject("decimal" to 1000, "binary" to 1024)
    )

    fun convert(value: BigDecimal, fromId: String, toId: String, precision: Int = 34): BigDecimal {
        requireArgument(precision in 16..100, "precision")
        val from = byId[fromId] ?: throw ToolFailure("unitUnknown", jsonObject("unit" to fromId))
        val to = byId[toId] ?: throw ToolFailure("unitUnknown", jsonObject("unit" to toId))
        requireArgument(from.dimension == to.dimension, "dimension")
        val work = MathContext(precision + 12, RoundingMode.HALF_EVEN)
        val base = when (from.id) {
            "F" -> value.add(BigDecimal(from.offset)).multiply(BigDecimal(5)).divide(BigDecimal(9), work)
            else -> value.multiply(factor(from, work), work).add(BigDecimal(from.offset), work)
        }
        if (from.dimension == "temperature" && base.signum() < 0) throw ToolFailure("temperatureBelowAbsoluteZero")
        val result = when (to.id) {
            "F" -> base.multiply(BigDecimal(9)).divide(BigDecimal(5), work).subtract(BigDecimal(to.offset), work)
            else -> base.subtract(BigDecimal(to.offset), work).divide(factor(to, work), work)
        }
        return boundedDecimal(result.round(MathContext(precision, RoundingMode.HALF_EVEN))).stripTrailingZeros()
    }

    private fun factor(unit: UnitDefinition, context: MathContext): BigDecimal {
        val fraction = unit.factor.split('/')
        val factor = if (fraction.size == 1) fraction[0].toBigDecimal() else fraction[0].toBigDecimal().divide(fraction[1].toBigDecimal(), context)
        return if (unit.piBased) factor.multiply(BigDecimalMath.pi(context), context) else factor
    }
}
