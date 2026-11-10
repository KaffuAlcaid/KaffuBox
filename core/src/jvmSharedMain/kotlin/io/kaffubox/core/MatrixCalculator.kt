// 执行高精度矩阵运算、秩和线性方程求解，使用带主元选择的消元并检查维度
package io.kaffubox.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.serialization.json.*

object MatrixCalculator {
    fun calculate(arguments: JsonObject, checkpoint: () -> Unit = {}): JsonObject {
        val precision = arguments.int("precision", 34)
        requireArgument(precision in 16..100, "precision")
        val context = MathContext(precision + 12, RoundingMode.HALF_EVEN)
        val output = MathContext(precision, RoundingMode.HALF_EVEN)
        val matrix = parse(arguments["matrix"], precision, checkpoint)
        fun asJson(result: Array<Array<BigDecimal>>): JsonObject = jsonObject(
            "matrix" to result.map { row -> row.map { boundedDecimal(it.round(output)).stripTrailingZeros() } },
            "cells" to result.map { row -> row.map { boundedDecimal(it.round(output)).stripTrailingZeros().toPlainString() } },
            "rows" to result.size, "columns" to result[0].size, "precision" to precision
        )
        fun right(): Array<Array<BigDecimal>> = parse(arguments["right"], precision, checkpoint)
        return when (arguments.string("operation")) {
            "transpose" -> asJson(Array(matrix[0].size) { column -> Array(matrix.size) { row -> matrix[row][column] } })
            "add", "subtract" -> {
                val right = right()
                requireArgument(matrix.size == right.size && matrix[0].size == right[0].size, "dimensions")
                val subtract = arguments.string("operation") == "subtract"
                asJson(Array(matrix.size) { row ->
                    checkpoint()
                    Array(matrix[0].size) { column -> if (subtract) matrix[row][column].subtract(right[row][column], context) else matrix[row][column].add(right[row][column], context) }
                })
            }
            "scale" -> {
                val scale = decimalArgument(arguments["scalar"], "scalar")
                asJson(Array(matrix.size) { row -> checkpoint(); Array(matrix[0].size) { column -> boundedDecimal(matrix[row][column].multiply(scale, context)) } })
            }
            "multiply" -> {
                val right = right()
                requireArgument(matrix[0].size == right.size, "dimensions")
                asJson(Array(matrix.size) { row ->
                    checkpoint()
                    Array(right[0].size) { column -> matrix[row].indices.fold(BigDecimal.ZERO) { sum, index -> sum.add(matrix[row][index].multiply(right[index][column], context), context) } }
                })
            }
            "trace" -> {
                requireArgument(matrix.size == matrix[0].size, "squareMatrix")
                jsonObject("value" to matrix.indices.fold(BigDecimal.ZERO) { sum, index -> sum.add(matrix[index][index], context) }.round(output))
            }
            "rank" -> jsonObject("value" to reduce(matrix, null, context, checkpoint).rank)
            "determinant" -> {
                requireArgument(matrix.size == matrix[0].size, "squareMatrix")
                val reduction = reduce(matrix, null, context, checkpoint)
                jsonObject("value" to if (reduction.rank < matrix.size) BigDecimal.ZERO else reduction.determinant.round(output).stripTrailingZeros())
            }
            "inverse", "solve" -> {
                requireArgument(matrix.size == matrix[0].size, "squareMatrix")
                val size = matrix.size
                val extra = if (arguments.string("operation") == "inverse") Array(size) { row -> Array(size) { column -> if (row == column) BigDecimal.ONE else BigDecimal.ZERO } } else right()
                requireArgument(extra.size == size, "dimensions")
                val reduction = reduce(matrix, extra, context, checkpoint)
                if (reduction.rank < size) throw ToolFailure("matrixSingular", jsonObject("rank" to reduction.rank, "size" to size))
                asJson(extra)
            }
            else -> throw ToolFailure("matrixOperationInvalid")
        }
    }

    private data class Reduction(val rank: Int, val determinant: BigDecimal)

    private fun reduce(matrix: Array<Array<BigDecimal>>, extra: Array<Array<BigDecimal>>?, context: MathContext, checkpoint: () -> Unit): Reduction {
        val rows = matrix.size
        val columns = matrix[0].size
        val scale = matrix.maxOf { row -> row.maxOf { it.abs() } }
        val threshold = scale.multiply(BigDecimal.ONE.scaleByPowerOfTen(-minOf(context.precision, 100) + 4), context)
        var pivotRow = 0
        var determinant = BigDecimal.ONE
        for (column in 0 until columns) {
            checkpoint()
            if (pivotRow == rows) break
            val best = (pivotRow until rows).maxBy { matrix[it][column].abs() }
            if (matrix[best][column].abs() <= threshold) continue
            if (best != pivotRow) {
                val row = matrix[pivotRow]; matrix[pivotRow] = matrix[best]; matrix[best] = row
                if (extra != null) { val right = extra[pivotRow]; extra[pivotRow] = extra[best]; extra[best] = right }
                determinant = determinant.negate()
            }
            val pivot = matrix[pivotRow][column]
            determinant = boundedDecimal(determinant.multiply(pivot, context))
            for (index in column until columns) matrix[pivotRow][index] = matrix[pivotRow][index].divide(pivot, context)
            extra?.get(pivotRow)?.indices?.forEach { index -> extra[pivotRow][index] = extra[pivotRow][index].divide(pivot, context) }
            for (row in 0 until rows) {
                checkpoint()
                if (row == pivotRow) continue
                val factor = matrix[row][column]
                if (factor.signum() == 0) continue
                for (index in column until columns) matrix[row][index] = matrix[row][index].subtract(factor.multiply(matrix[pivotRow][index], context), context)
                extra?.get(row)?.indices?.forEach { index -> extra[row][index] = extra[row][index].subtract(factor.multiply(extra[pivotRow][index], context), context) }
            }
            pivotRow++
        }
        return Reduction(pivotRow, determinant)
    }

    private fun parse(value: JsonElement?, precision: Int, checkpoint: () -> Unit): Array<Array<BigDecimal>> {
        val rows = value as? JsonArray ?: throw ToolFailure("matrixRequired")
        requireArgument(rows.size in 1..64, "rows")
        val matrix = rows.map { item ->
            checkpoint()
            val row = item as? JsonArray ?: throw ToolFailure("matrixRowInvalid")
            requireArgument(row.size in 1..64, "columns")
            row.map { cell ->
                val primitive = cell as? JsonPrimitive ?: throw ToolFailure("matrixCellInvalid")
                if (primitive.isString) ScientificCalculator.evaluate(primitive.content, precision = minOf(precision + 12, 100), checkpoint = checkpoint) else decimalArgument(cell, "matrix")
            }.toTypedArray()
        }.toTypedArray()
        requireArgument(matrix.all { it.size == matrix[0].size }, "columns")
        return matrix
    }
}
