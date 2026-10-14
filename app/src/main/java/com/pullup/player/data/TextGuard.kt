package com.pullup.player.data

/**
 * 敏感措辞替换：只动文本、不碰控件与样式。
 *
 * 移植自 Godot 版 scripts/TextGuard.gd。**替换表顺序是承重的**：必须长的在前，
 * 否则 `版权` 会先把 `版权归原作者与版权方所有` 切碎，后面的整句规则永远命中不上。
 *
 * 调用点：所有要显示给用户的接口错误文本（渲染前过一道）。
 */
object TextGuard {

    /** 顺序即优先级：逐条 `replace`，长句在前。 */
    private val REPLACEMENTS: List<Pair<String, String>> = listOf(
        "无版权或需要会员" to "暂不可播放或需要会员",
        "版权归原作者与版权方所有" to "相关权利归原作者所有",
        "音乐授权" to "音频来源",
        "授权" to "来源",
        "版权" to "相关权利",
    )

    fun mask(text: String): String {
        var out = text
        REPLACEMENTS.forEach { (from, to) -> out = out.replace(from, to) }
        return out
    }

    /** 自检：还有哪些被禁词竟然漏过去了。 */
    fun leftovers(text: String): List<String> {
        // 必须查**替换后**的文本：直接查原文会把「已被整句规则吃掉的词」也报出来
        // （如 `版权归原作者与版权方所有` 已换成 `相关权利归原作者所有`，里面仍含 `版权` 二字）
        // —— Godot 版 scripts/TextGuard.gd:36-43 就是先 mask 再查。
        val masked = mask(text)
        return REPLACEMENTS.map { it.first }.filter { masked.contains(it) }
    }
}
