package com.pullup.player.data

/**
 * LRC 解析，移植自 Godot 版 `scripts/Lyrics.gd`（纯函数）。
 *
 * 支持：一行一个时间标签 `[mm:ss.xx]歌词`、一行多个标签 `[00:12.34][01:20.00]歌词`、
 * 毫秒 2~3 位、以及 `[ti:]` / `[ar:]` 这类元信息行（会被忽略）。
 */
object LyricsParser {

    /** 解析成按时间升序排列的行。 */
    fun parse(lrc: String): List<LyricLine> {
        if (lrc.isBlank()) return emptyList()
        val out = mutableListOf<LyricLine>()

        lrc.split('\n').forEach { raw ->
            var rest = raw.trim()
            if (rest.isEmpty()) return@forEach

            val stamps = mutableListOf<Float>()
            while (rest.startsWith("[")) {
                val close = rest.indexOf(']')
                if (close <= 0) break
                val seconds = stampToSeconds(rest.substring(1, close))
                if (seconds >= 0f) stamps += seconds
                rest = rest.substring(close + 1)
            }

            val text = rest.trim()
            if (text.isEmpty()) return@forEach
            stamps.forEach { out += LyricLine(it, text) }
        }

        return out.sortedBy { it.time }
    }

    /** 当前时间应显示第几行；没有匹配返回 -1。 */
    fun lineAt(lines: List<LyricLine>, position: Float): Int {
        var result = -1
        for (i in lines.indices) {
            if (lines[i].time <= position + 0.05f) result = i else break
        }
        return result
    }

    /** `mm:ss.xx` → 秒；不是时间标签（如 `ti:标题`）返回 -1。 */
    private fun stampToSeconds(stamp: String): Float {
        val parts = stamp.split(':')
        if (parts.size != 2) return -1f
        val minutes = parts[0].trim().toFloatOrNull() ?: return -1f
        val seconds = parts[1].trim().toFloatOrNull() ?: return -1f
        return minutes * 60f + seconds
    }
}
