package com.pullup.player.data

/**
 * 口味模型：把「节奏感 / 旋律性 / 人声 + 语种 + 自定义标签」算成一个可解释的排序分。
 *
 * 移植自 Godot 版 scripts/TasteModel.gd。**纯函数、无持久化** —— 偏好由 [Preferences] 提供，
 * 本类只负责算分与排序，因此可以直接单测。
 *
 * 接口不给节奏/旋律/人声数据，所以用「时长 + 关键词 + 语种」近似判断。
 */
object TasteModel {

    // ---- 关键词表（小写匹配） ------------------------------------------------

    val RHYTHM_KEYWORDS = listOf(
        "remix", "舞曲", "dance", "电音", "edm", "说唱", "rap", "hiphop", "hip-hop",
        "迪斯科", "disco", "摇滚", "rock", "trap", "house", "dj ",
    )

    val MELODY_KEYWORDS = listOf(
        "钢琴", "吉他", "弦乐", "轻音乐", "民谣", "抒情", "acoustic", "unplugged",
        "现场", "live", "ost", "原声", "协奏", "交响", "lofi", "lo-fi",
    )

    val INSTRUMENTAL_KEYWORDS = listOf(
        "伴奏", "纯音乐", "off vocal", "offvocal", "instrumental", "karaoke",
        "卡拉ok", "无人声", "inst.", "演奏",
    )

    /** 语种加权/降权幅度。 */
    private const val LANGUAGE_BOOST = 0.6f
    private const val LANGUAGE_PENALTY = -1.2f

    /** 单个标签的加权，命中数封顶 3 个（避免标签刷分）。 */
    private const val TAG_BOOST = 2.0f
    private const val TAG_MAX_HITS = 3

    // ---- 语种判定 -----------------------------------------------------------

    /**
     * 按码点判断语种：谚文→ko，假名→ja，汉字多于拉丁→zh，否则有拉丁→en，兜底 zh。
     *
     * 已知局限（Godot 版注释里也写了）：纯汉字写的日文歌会被判成华语。
     */
    fun detectLanguage(title: String, artist: String): String {
        val text = title + " " + artist
        var han = 0
        var latin = 0
        var kana = false
        var hangul = false
        for (c in text) {
            val code = c.code
            when {
                (code in 0xAC00..0xD7A3) || (code in 0x1100..0x11FF) || (code in 0x3130..0x318F) -> hangul = true
                code in 0x3040..0x30FF -> kana = true
                (code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF) -> han++
                (code in 0x41..0x5A) || (code in 0x61..0x7A) -> latin++
            }
        }
        return when {
            hangul -> "ko"
            kana -> "ja"
            han > latin -> "zh"
            latin > 0 -> "en"
            else -> "zh"
        }
    }

    // ---- 算分 ---------------------------------------------------------------

    /** `(权重 - 50) / 50` ⇒ −1.0 … +1.0。 */
    private fun weightFactor(weights: Map<String, Int>, kind: String): Float =
        ((weights[kind] ?: Preferences.DEFAULT_WEIGHT) - 50).toFloat() / 50f

    /**
     * 给一首歌打分，返回填好 `taste_*` 三项的副本。
     *
     * 三个维度的分乘各自权重因子后相加；标签按命中数直接加分；语种按勾选加减。
     */
    fun scoreSong(
        song: Track,
        weights: Map<String, Int>,
        languages: Map<String, Boolean>,
        tags: List<String>,
    ): Track {
        val title = song.name
        val haystack = (song.name + " " + song.artist + " " + song.album).lowercase()
        val seconds = song.duration / 1000f
        val reasons = mutableListOf<String>()

        // 节奏：命中类目直接满档，否则按「时长短 = 更靠前」。
        val rhythm: Float = RHYTHM_KEYWORDS.firstOrNull { haystack.contains(it) }?.let { kw ->
            reasons.add("节奏类目「$kw」")
            1f
        } ?: if (seconds > 0f) ((210f - seconds) / 90f).coerceIn(-0.6f, 1f) else 0f

        // 旋律：命中类目满档，否则「更长 = 更靠前」。
        val melody: Float = MELODY_KEYWORDS.firstOrNull { haystack.contains(it) }?.let { kw ->
            reasons.add("旋律类目「$kw」")
            1f
        } ?: if (seconds > 0f) ((seconds - 240f) / 90f).coerceIn(-0.6f, 1f) else 0f

        // 人声：默认轻微正向，命中器乐/伴奏词直接压到底。
        val instrumental = INSTRUMENTAL_KEYWORDS.firstOrNull { haystack.contains(it) }
        val vocal: Float = if (instrumental != null) {
            reasons.add("器乐/伴奏「$instrumental」")
            -1f
        } else 0.35f

        var score = rhythm * weightFactor(weights, "rhythm") +
            melody * weightFactor(weights, "melody") +
            vocal * weightFactor(weights, "vocal")

        // 语种：勾选加分、取消降权。
        val language = detectLanguage(title, song.artist)
        if (languages[language] == false) {
            reasons.add("语种未勾选")
            score += LANGUAGE_PENALTY
        } else {
            score += LANGUAGE_BOOST
        }

        // 标签：同一首歌命中多个标签会叠加，但封顶 3 个。
        val hit = tags.filter { it.isNotBlank() && haystack.contains(it.lowercase()) }
        if (hit.isNotEmpty()) {
            score += TAG_BOOST * minOf(hit.size, TAG_MAX_HITS).toFloat()
            reasons.add("标签「${hit.joinToString("、")}」")
        }

        if (reasons.isEmpty()) reasons.add("时长/语种中性")

        return song.copy(
            tasteLanguage = language,
            tasteScore = score,
            tasteReason = reasons.joinToString("、"),
        )
    }

    /**
     * 按分数降序排（同分保持接口原序）。
     *
     * Godot 版手写了稳定插入排序；Kotlin 的 `sortedByDescending` 本身就是稳定排序，
     * 语义等价，就不重复造轮子了。
     */
    fun rankSongs(songs: List<Track>, prefs: Preferences): List<Track> {
        if (songs.isEmpty()) return songs
        val scored = songs.map { scoreSong(it, prefs.weights(), prefs.languages(), prefs.tags()) }
        return scored.sortedByDescending { it.tasteScore }
    }

    /**
     * 歌单排序：只看标签与语种（歌单没有时长）。
     * 注意这里的标签命中**不封顶**，与单曲口径不同 —— 与 Godot 版一致。
     */
    fun rankPlaylists(playlists: List<LocalPlaylist>, prefs: Preferences): List<LocalPlaylist> {
        if (playlists.isEmpty()) return playlists
        val tags = prefs.tags().map { it.lowercase() }.filter { it.isNotBlank() }
        val languages = prefs.languages()

        val scored = playlists.map { pl ->
            val haystack = (pl.name + " " + pl.desc + " " + pl.creator).lowercase()
            var score = 0f
            val reasons = mutableListOf<String>()
            val hit = tags.filter { haystack.contains(it) }
            if (hit.isNotEmpty()) {
                score += TAG_BOOST * hit.size
                reasons.add("标签「${hit.joinToString("、")}」")
            }
            val lang = detectLanguage(pl.name, pl.creator)
            if (languages[lang] == false) {
                score += LANGUAGE_PENALTY
                reasons.add("语种未勾选")
            } else {
                score += LANGUAGE_BOOST
            }
            if (reasons.isEmpty()) reasons.add("默认顺序")
            pl.copy(tasteScore = score, tasteReason = reasons.joinToString("、"))
        }
        return scored.sortedByDescending { it.tasteScore }
    }
}
