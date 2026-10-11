package io.github.ynkcc.moredisplay.ui

import android.content.Context
import io.github.ynkcc.moredisplay.App

/** 一组用户最近使用过的虚拟屏尺寸（宽 × 高 @ dpi）。 */
data class DisplaySizeHistory(val width: Int, val height: Int, val dpi: Int) {
    override fun toString(): String = "$width×$height@$dpi"
}

/**
 * 创建虚拟屏的历史记录：持久化用户最近最多 2 组 尺寸/dpi，
 * 不预设任何默认值，仅记录用户实际创建过的组合。
 */
object CreateHistoryStore {

    private const val PREFS_NAME = "create_history"
    private const val KEY_COUNT = "count"
    private const val KEY_WIDTH = "w%d"
    private const val KEY_HEIGHT = "h%d"
    private const val KEY_DPI = "dpi%d"

    /** 最多保留的历史条数。 */
    const val MAX_ENTRIES = 2

    /** 读取全部历史，最新在前。 */
    fun load(): List<DisplaySizeHistory> {
        val prefs = prefs() ?: return emptyList()
        val count = (prefs.getInt(KEY_COUNT, 0)).coerceIn(0, MAX_ENTRIES)
        return (0 until count).mapNotNull { i ->
            val w = prefs.getInt(KEY_WIDTH.format(i), 0)
            val h = prefs.getInt(KEY_HEIGHT.format(i), 0)
            val dpi = prefs.getInt(KEY_DPI.format(i), 0)
            if (w in 1..7680 && h in 1..7680 && dpi in 10..2000) {
                DisplaySizeHistory(w, h, dpi)
            } else null
        }
    }

    /** 记录一次创建；重复项上移到最前，超出 [MAX_ENTRIES] 的淘汰。 */
    fun record(width: Int, height: Int, dpi: Int) {
        val prefs = prefs() ?: return
        val existing = load().toMutableList()
        existing.removeAll { it.width == width && it.height == height && it.dpi == dpi }
        existing.add(0, DisplaySizeHistory(width, height, dpi))
        while (existing.size > MAX_ENTRIES) existing.removeAt(existing.lastIndex)

        prefs.edit().apply {
            putInt(KEY_COUNT, existing.size)
            existing.forEachIndexed { i, e ->
                putInt(KEY_WIDTH.format(i), e.width)
                putInt(KEY_HEIGHT.format(i), e.height)
                putInt(KEY_DPI.format(i), e.dpi)
            }
        }.apply()
    }

    private fun prefs() = try {
        App.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Exception) {
        null
    }
}
