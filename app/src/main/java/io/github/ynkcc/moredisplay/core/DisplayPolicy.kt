package io.github.ynkcc.moredisplay.core

/**
 * 某个调用方（按 uid，可附带包名）对屏幕的可见性语义。
 *
 * 设计取舍：**不能**用平台的 `FLAG_PRIVATE` / `ownerUid` 表达任意 per-uid 白黑名单
 * （那只能表达“仅创建者可见”），所以必须在 DisplayManagerService 返回结果处裁剪。
 */
enum class Visibility {
    /** 不做任何过滤（默认；没有策略条目时即此语义）。 */
    ALL,

    /** 白名单：调用方**只能**看到 [DisplayPolicy.displayIds] 里的屏幕。 */
    ONLY,

    /** 黑名单：调用方**看不到** [DisplayPolicy.displayIds] 里的屏幕。 */
    HIDE;

    companion object {
        fun fromName(name: String?): Visibility =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ALL
    }
}

/**
 * 可见性策略条目。以 [uid] 为主键（[packageName] 仅作展示/可读性）。
 *
 * [operatedDisplayId] / [recordDisplayId] 预留给后续的「无障碍屏替换」「录屏屏替换」，
 * 本阶段不参与可见性判断。
 */
data class DisplayPolicy(
    val uid: Int,
    val packageName: String? = null,
    val visibility: Visibility = Visibility.ALL,
    val displayIds: Set<Int> = emptySet(),
    val operatedDisplayId: Int? = null,
    val recordDisplayId: Int? = null
) {
    fun describe(): String {
        val target = packageName?.let { "$it($uid)" } ?: "uid=$uid"
        val ids = displayIds.sorted().joinToString(",")
        val extras = listOfNotNull(
            recordDisplayId?.let { "record=$it" },
            operatedDisplayId?.let { "operated=$it" }
        )
        val suffix = if (extras.isEmpty()) "" else " " + extras.joinToString(" ")
        return "$target ${visibility.name}[$ids]$suffix"
    }
}
