package com.pullup.player.data

/**
 * 音质档位表（用户 m01767 指定）。
 *
 * ## 为什么是一张「key + 中文标签 + 能不能真拿到」的表
 *
 * 旧版把音质当成 `br`（码率）传给老接口 `/song/url`，那是错的方向：实测该端点**完全忽略**
 * `br` 与 `level`，传什么码率都回同一份文件。真正认档位的是 **`/song/url/v1?level=<key>`**，
 * 它按 `level` 下发，并在回包里用 `level` 字段**如实报告实际下发的档位** ——
 * 于是「切音质」这件事才第一次真的有东西可切。
 *
 * ## 实测结论（2026-10-12，id=1330348068 / 569213220 / 347230 三首交叉验证）
 *
 * | 请求的 level | 实际下发 | 说明 |
 * |---|---|---|
 * | `standard` | standard 128k mp3 | 正常 |
 * | `higher`   | higher 192k mp3   | 正常 |
 * | `exhigh`   | exhigh 320k mp3   | 正常 |
 * | `lossless` | **exhigh 320k mp3** | 服务端无 VIP 凭证，静默降级回 exhigh |
 * | `hires`    | **exhigh 320k mp3** | 同上 |
 * | `jyeffect` | **exhigh 320k mp3** | 同上 |
 * | `sky`      | **exhigh 320k mp3** | 同上 |
 * | `jymaster` | **exhigh 320k mp3** | 同上 |
 *
 * 所以前四档（standard / higher / exhigh）是真能拿到的，lossless 起全是画饼 ——
 * 界面上照用户要求把名字标出来，但必须显式标 **未实现**，不能假装能切。
 *
 * [bitrate] 只作展示与容量估算用；真正的请求参数是 [key]。
 */
object Quality {

    /**
     * 一个音质档位。
     *
     * @param key 传给 `/song/url/v1?level=` 的档位标识（网易云口径）。
     * @param label 界面显示名（用户逐字指定）。
     * @param bitrate 该档位对应的码率，仅用于展示/体积估算。
     * @param implemented 服务端是否真能下发这一档（false ⇒ 界面标「未实现」）。
     */
    data class Level(
        val key: String,
        val label: String,
        val bitrate: Int,
        val implemented: Boolean,
    )

    const val STANDARD = "standard"
    const val HIGHER = "higher"
    const val EXHIGH = "exhigh"
    const val LOSSLESS = "lossless"
    const val HIRES = "hires"
    const val JYEFFECT = "jyeffect"
    const val SKY = "sky"
    const val JYMASTER = "jymaster"

    /**
     * 全部档位，从低到高。
     *
     * 说明两点：
     * - `higher`（192k）用户没点名，但实测能真下发，作为能用的中间档一并列出，不浪费。
     * - `exhigh` 的显示名就是用户写的字面量 `exhigh`（其余档位他都给了中文名，只有这档写了
     *   标识符本身）；要改成「极高」只需动这一行的 [Level.label]。
     */
    val LEVELS: List<Level> = listOf(
        Level(STANDARD, "标准", 128_000, true),
        Level(HIGHER, "较高", 192_000, true),
        Level(EXHIGH, "exhigh", 320_000, true),
        Level(LOSSLESS, "无损", 999_000, false),
        Level(HIRES, "Hi-Res", 2_000_000, false),
        Level(JYEFFECT, "高清环绕声", 0, false),
        Level(SKY, "沉浸环绕声", 0, false),
        Level(JYMASTER, "高清母带", 0, false),
    )

    /** 默认档：exhigh（320k），与原版「极高」等价。 */
    const val DEFAULT_INDEX = 2

    /** 拿不到任何档位时的兜底：按 standard 请求。 */
    val FALLBACK: Level = LEVELS[0]

    fun byKey(key: String): Level = LEVELS.firstOrNull { it.key == key } ?: FALLBACK

    fun indexOf(key: String): Int = LEVELS.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: DEFAULT_INDEX

    fun label(key: String): String = byKey(key).label

    /** 服务端实际下发档位的中文名；认不出就把原始 level 原样回给界面（便于排查）。 */
    fun servedLabel(served: String): String =
        if (served.isBlank()) "" else LEVELS.firstOrNull { it.key == served }?.label ?: served

    /**
     * vkeys 第三方接口需要的数字音质参数。
     *
     * vkeys 只区分三档：
     * - 8 = 标准 128k
     * - 12 = 较高 192k
     * - 16 = 极高 320k
     *
     * lossless 及以上档位 vkeys 要么返回「业务运行异常」，要么静默降级，
     * 所以**统一映射到 16**（320k），让 vkeys 尽力给最高能给的。
     *
     * 注意：这是 vkeys 专用的映射，与网易云官方的 level key 不是一个体系，
     * 不要拿它去请求 `/song/url/v1`。
     */
    fun vkeysQuality(key: String): Int = when (key) {
        STANDARD -> 8
        HIGHER -> 12
        else -> 16
    }
}