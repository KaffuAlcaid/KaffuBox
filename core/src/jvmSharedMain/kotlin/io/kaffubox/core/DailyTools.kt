// 实现随机抽取、掷骰和基于目标时刻的计时状态，不依赖界面刷新频率
package io.kaffubox.core

import java.security.SecureRandom
import java.time.Instant

object DailyTools {
    private val random = SecureRandom()
    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("daily.draw", Workbench.Daily, mapOf("items" to "array", "count" to "integer", "replacement" to "boolean"), listOf("items")) { args, _ ->
            val items = args.strings("items"); val count = args.int("count", 1); val replacement = args.flag("replacement")
            requireArgument(items.isNotEmpty() && count in 1..10_000 && (replacement || count <= items.size), "count")
            val result = if (replacement) List(count) { items[random.nextInt(items.size)] } else {
                val pool = items.toMutableList()
                for (i in pool.lastIndex downTo 1) { val j = random.nextInt(i + 1); val temp = pool[i]; pool[i] = pool[j]; pool[j] = temp }
                pool.take(count)
            }
            jsonObject("values" to result)
        },
        defineTool("daily.dice", Workbench.Daily, mapOf("count" to "integer", "sides" to "integer")) { args, _ ->
            val count = args.int("count", 1); val sides = args.int("sides", 6)
            requireArgument(count in 1..10_000 && sides in 2..1_000_000, "dice")
            val values = List(count) { random.nextInt(sides) + 1 }
            jsonObject("values" to values, "total" to values.sumOf(Int::toLong))
        },
        defineTool("daily.countdown", Workbench.Daily, mapOf("target" to "string", "now" to "string"), listOf("target")) { args, _ ->
            val now = args.optionalString("now")?.let(Instant::parse) ?: Instant.now()
            val target = Instant.parse(args.string("target"))
            val millis = java.time.Duration.between(now, target).toMillis()
            jsonObject("remainingMillis" to millis.coerceAtLeast(0), "elapsed" to (millis <= 0), "target" to target.toString())
        }
    )
}
