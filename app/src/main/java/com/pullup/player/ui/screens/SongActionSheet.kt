package com.pullup.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pullup.player.data.BlacklistOption
import com.pullup.player.data.LocalPlaylist
import com.pullup.player.data.TextGuard
import com.pullup.player.data.Track
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.LocalShell
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo
import com.pullup.player.ui.theme.pullShape
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// 与 `ActionSheet.gd` 对齐的几何（画布 1080px = 432dp，即 1dp = 2.5px，
// 原值见 `scripts/ui/AppTheme.gd:64`）。下列数值都是 px ÷ 2.5 的**精确值** ——
// 38.4 / 28.8 / 7.2 / 10.4 都不是整数 dp，不要再除一次 2.5，也不要取整。
// ---------------------------------------------------------------------------

/** `ActionSheet.gd:17` `ROW_HEIGHT = 96` → 38.4dp（歌曲动作行高）。 */
private const val ROW_HEIGHT_DP = 38.4

/**
 * `ActionSheet.gd:18` `BLACKLIST_ROW_HEIGHT = 72` 与 `:22` `PICKER_ROW_HEIGHT = 72` → 28.8dp。
 * 二级面板是**刻意压矮**的：黑名单一屏要铺 7 行（含「返回/取消」），歌单选择器也要多塞几个。
 */
private const val SUB_ROW_HEIGHT_DP = 28.8

/** `ActionSheet.gd:178` `_actions.separation = 10` → 4dp（操作行之间）。 */
private const val ROW_GAP_DP = 4.0

/** `ActionSheet.gd:163` `Column.separation = 18` → 7.2dp（标题 / 副标题 / 操作组之间）。 */
private const val BLOCK_GAP_DP = 7.2

/** `ActionSheet.gd:155-156` `Margin.margin_left` / `margin_right = 30` → 12dp（面板内边距）。 */
private const val MARGIN_H_DP = 12.0

/** `ActionSheet.gd:157-158` `Margin.margin_top` / `margin_bottom = 26` → 10.4dp。 */
private const val MARGIN_V_DP = 10.4

/**
 * `ActionSheet.gd:15` `SHEET_HEIGHT = 620.0` → 248dp，`_fit_sheet` 的**高度地板**
 * （`offset_top = -maxf(needed, SHEET_HEIGHT)`，`:85`）。
 *
 * 地板大多数时候够不到：`needed = 52+56+36 + rows*行高 + (rows-1)*10 + 40`，主菜单 5 行就是
 * 704px、黑名单 7 行 748px。**只有歌单选择器行数少时才会撞上它** —— 0 个歌单（提示/新建/取消
 * 3 行）只要 420px、1 个歌单 502px，都被抬到 620px。所以这个地板不是装饰，少了它
 * 「还没有自建歌单，先新建一个」那张面板会比原版矮一截。
 */
private const val SHEET_MIN_HEIGHT_DP = 248.0

/** `MAX_PICKER_LISTS := 6`（`ActionSheet.gd:24`）。 */
private const val MAX_PICKER_LISTS = 6

/** `MAX_BLACKLIST_OPTIONS := 5`（`ActionSheet.gd:20`）。 */
private const val MAX_BLACKLIST_OPTIONS = 5

/** 副标题上临时反馈的存活时长：原版 `_show_feedback` 的 1.4 秒（`ActionSheet.gd:269`）。 */
private const val FEEDBACK_MS = 1400L

/**
 * 歌曲操作面板，移植自 `scripts/ui/ActionSheet.gd`。
 *
 * 面板有**三种内容共用一个副标题**（原版就是这个结构，收起再弹会闪一下，所以就地重铺）：
 * 1. 主操作列表，副标题＝歌手名；
 * 2. 「添加到歌单」二级面板，副标题「把这首放进哪个歌单？」；
 * 3. 「加入黑名单」二级面板，副标题「加入黑名单 · 点一下屏蔽或取消」。
 * 加歌/屏蔽的临时反馈写在副标题上，1.4 秒后自己收回去 —— 用代次守卫，
 * 过期那次不许覆盖新内容（原版 `_show_feedback` / `_restore_subtitle` 的 `_feedback_gen`）。
 *
 * 逐字照抄的文案：收藏 / 取消收藏 / 查看歌手 / 下一首播放 / 添加到歌单 / 加入黑名单 / 取消 /
 * 把这首放进哪个歌单？ / 还没有自建歌单，先新建一个 / 还有 N 个歌单没显示 / 新建歌单 /
 * 已加入「X」 / 没能加进去 / 加入黑名单 · 点一下屏蔽或取消 / 屏蔽 · X / 取消屏蔽 · X /
 * 还有 N 条不显示了 / 返回。
 *
 * 骨架逐项照抄 `ActionSheet.gd:127-179`：遮罩（`SCRIM` 强制 alpha 0.42）→ 贴底面板
 * （左右各留 `PAD_SCREEN`、下边距 `PAD_SCREEN`、高度地板 `SHEET_HEIGHT`、四角全圆
 * `RADIUS`、无拖拽把手）→ `Margin`（左右 30px、上下 26px）→ `Column`（间距 18px）
 * ＝ 标题 + 副标题 + `Actions`（另一个 VBox，间距 10px）。**原版没有封面**，标题就是一行
 * `FONT_SUB` 的歌名、副标题是一行 `FONT_META` 的说明 —— 这一版按原版把 Kotlin 侧多加的封面去掉了。
 *
 * 行样式见 [SheetRow]：`button_box(SURFACE_HI)`，**只有填充、没有发丝描边**。
 * 原版的 ActionSheet 从头到尾**没有调用过 `SubScreen.make_row`**（那个是带 1px 描边 +
 * 行尾 `♪` 的设置行，见 `SubScreen.gd:60-69`），所以这里保留自定义行而不是改造成它。
 *
 * 与原版的三处差异，都是接口/运行时形状决定的：
 * - 原版「新建歌单」是先 `close()` 再打开外壳上常驻的 `PlaylistEditorScreen`，并记住
 *   「建好后把这首放进去」。Kotlin 侧编辑页是随栈推入的浮层，这里关面板后直接
 *   `openEditor()`，等价路径。
 * - 原版点歌单行只做「加进去」；本面板同时拿到 `onOpenPlaylist`，用在**这首歌已经在该
 *   歌单里**的那一次点击上 —— 此时再加是空操作，把用户送进那个歌单看现状才是有用的下一步。
 * - 弹出/收起与「操作项错峰浮现」（`_present` / `close` / `Motion.stagger_in`）由
 *   Material3 的 `ModalBottomSheet` 自带，不再自己补间。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionSheet(
    song: Track,
    onDismiss: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    val app = LocalApp.current
    val shell = LocalShell.current
    val c = PullTheme.colors

    // 收藏状态会因本次点击而变，所以要订阅而不是读一次。
    val favorites by app.favorites.songs.collectAsStateWithLifecycle()
    val blacklist by app.blacklist.entries.collectAsStateWithLifecycle()
    val playlists by app.playlists.playlists.collectAsStateWithLifecycle()

    /** 面板模式：0 = 主操作，1 = 选歌单，2 = 黑名单。 */
    var mode by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf("") }
    // 代次守卫：只有最后一次反馈的定时器才有权收回副标题。
    var feedbackGen by remember { mutableIntStateOf(0) }

    val playlistOptions = remember(playlists) { app.playlists.addable() }
    val blacklistOptions = remember(song, blacklist) { app.blacklist.optionsFor(song) }

    // 反馈到点收回（原版 tree.create_timer(1.4)）：代次对不上就什么都不做。
    LaunchedEffect(feedbackGen) {
        if (feedback.isEmpty()) return@LaunchedEffect
        val token = feedbackGen
        delay(FEEDBACK_MS)
        if (token == feedbackGen) feedback = ""
    }

    /** 副标题：临时反馈优先，否则按当前模式给默认文案（原版 `_restore_subtitle`）。 */
    val subtitle = when {
        feedback.isNotEmpty() -> feedback
        mode == 1 -> "把这首放进哪个歌单？"
        mode == 2 -> "加入黑名单 · 点一下屏蔽或取消"
        else -> song.artist
    }

    fun showFeedback(text: String) {
        feedback = TextGuard.mask(text)
        feedbackGen += 1
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 原版 `Sheet` 左右各留 `PAD_SCREEN`(40px→16dp)、`offset_bottom = -PAD_SCREEN`、
        // `offset_top = -max(needed, SHEET_HEIGHT)`（`ActionSheet.gd:146-149`）：面板是
        // 「四周留白、四角全圆」的一张卡，不是贴满屏幕的整宽抽屉。
        // 高度按内容算（`_fit_sheet`，`:82-85`）；`heightIn(min = ...)` 补上那个
        // `maxf(needed, 620)` 的地板 —— 歌单选择器行数少时它会真的生效（见
        // [SHEET_MIN_HEIGHT_DP]）。原版是显式设 `offset_top`（顶边不动、往内容方向长），
        // Compose 里由 ModalBottomSheet 贴底布局 + 最小高即可等价。
        modifier = Modifier
            .padding(
                start = Dims.PAD_SCREEN.dp,
                end = Dims.PAD_SCREEN.dp,
                bottom = Dims.PAD_SCREEN.dp,
            )
            .heightIn(min = SHEET_MIN_HEIGHT_DP.dp),
        sheetState = sheetState,
        containerColor = c.surface,
        // 原版 `Color(AppTheme.SCRIM, 0.42)`：遮罩色就是 SCRIM，但 alpha 被强制成 0.42
        // （深色模式 SCRIM 自带 0.42，浅色模式是 0.32，这一句把两者拉平）。
        scrimColor = c.scrim.copy(alpha = 0.42f),
        shape = pullShape(Dims.RADIUS),
        // 原版没有拖拽把手，那根横杠是 M3 的默认装饰。
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 原版 `Margin`：左右 30px→12dp、上下 26px→10.4dp（`ActionSheet.gd:153-159`）。
                .padding(horizontal = MARGIN_H_DP.dp, vertical = MARGIN_V_DP.dp),
            // 原版 `Column.separation = 18px→7.2dp`（`ActionSheet.gd:163`）。
            verticalArrangement = Arrangement.spacedBy(BLOCK_GAP_DP.dp),
        ) {
            // 标题 + 副标题是 Column 的直接子项（原版 `_title` / `_subtitle`，`ActionSheet.gd:166-174`）。
            // 原版两个 Label 都没开 `autowrap`，所以这里按单行处理（超出给省略号，
            // 原版是直接撑出去，那在窄屏上会把面板顶宽）。
            Text(
                text = song.name,
                color = c.text,
                fontSize = Typo.SUB.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = c.textDim,
                fontSize = Typo.META.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // 原版 `Actions`：另一个 VBoxContainer，separation 10px→4dp（`ActionSheet.gd:176-179`）。
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
            ) {
                when (mode) {
                    1 -> {
                        val shown = playlistOptions.size.coerceAtMost(MAX_PICKER_LISTS)
                        playlistOptions.take(shown).forEach { pl ->
                            SheetRow(
                                label = playlistLabel(pl),
                                height = SUB_ROW_HEIGHT_DP,
                            ) {
                                val ok = app.playlists.addSong(pl.id, song)
                                if (ok) {
                                    showFeedback("已加入「${app.playlists.find(pl.id)?.name ?: "歌单"}」")
                                } else {
                                    // 失败文案就是数据层写的原文（「这首歌没有 id，加不进去」/
                                    // 「「X」里不能手动加歌」/「「X」里已经有这首了」）。
                                    val err = app.playlists.lastError.ifEmpty { "没能加进去" }
                                    showFeedback(err)
                                    // 已经在这张歌单里：再去加是空操作，把用户送进歌单看现状，
                                    // 这才是他下一步想干的事（原版这里只会重复一句提示）。
                                    if (err.endsWith("里已经有这首了")) onOpenPlaylist(pl.id)
                                }
                            }
                        }
                        if (playlistOptions.isEmpty()) {
                            SheetRow("还没有自建歌单，先新建一个", muted = true, height = SUB_ROW_HEIGHT_DP) {}
                        } else if (playlistOptions.size > shown) {
                            SheetRow(
                                "还有 ${playlistOptions.size - shown} 个歌单没显示",
                                muted = true,
                                height = SUB_ROW_HEIGHT_DP,
                            ) {}
                        }
                        SheetRow("新建歌单", height = SUB_ROW_HEIGHT_DP) {
                            onDismiss()
                            // 带着这首歌进编辑页：建好就自动放进去（原版 `ActionSheet.gd:248-258`）。
                            shell.openEditorFor(song)
                        }
                        SheetRow("取消", muted = true, height = SUB_ROW_HEIGHT_DP) { onDismiss() }
                    }

                    2 -> {
                        val shown = blacklistOptions.size.coerceAtMost(MAX_BLACKLIST_OPTIONS)
                        blacklistOptions.take(shown).forEach { option: BlacklistOption ->
                            // 前缀随当前屏蔽状态翻转：点一下就在「屏蔽 / 取消屏蔽」之间切换。
                            val prefix = if (app.blacklist.hasPair(option.kind, option.value)) "取消屏蔽 · " else "屏蔽 · "
                            SheetRow(prefix + option.label, height = SUB_ROW_HEIGHT_DP) {
                                app.blacklist.toggle(option.kind, option.value)
                                // 就地重铺，方便连点几条（不收起面板）。
                                feedback = ""
                            }
                        }
                        if (blacklistOptions.size > shown) {
                            SheetRow(
                                "还有 ${blacklistOptions.size - shown} 条不显示了",
                                muted = true,
                                height = SUB_ROW_HEIGHT_DP,
                            ) {}
                        }
                        SheetRow("返回", height = SUB_ROW_HEIGHT_DP) { mode = 0 }
                        SheetRow("取消", muted = true, height = SUB_ROW_HEIGHT_DP) { onDismiss() }
                    }

                    else -> {
                        val fav = favorites.any { it.id == song.id && song.id != 0L }
                        SheetRow(if (fav) "取消收藏" else "收藏") {
                            app.favorites.toggle(song)
                            onDismiss()
                        }
                        if (song.artistId > 0L || song.artist.isNotBlank()) {
                            SheetRow("查看歌手") {
                                onDismiss()
                                shell.openArtist(song)
                            }
                        }
                        SheetRow("下一首播放") {
                            app.playback.playAfterCurrent(song)
                            onDismiss()
                        }
                        SheetRow("添加到歌单") {
                            feedback = ""
                            mode = 1
                        }
                        SheetRow("加入黑名单") {
                            feedback = ""
                            mode = 2
                        }
                        SheetRow("取消", muted = true) { onDismiss() }
                    }
                }
            }
        }
    }
}

/** 「名字 · N 首」(+「 · 私密」)—— 原版 `_fill_playlist_picker` 的标签拼法（`ActionSheet.gd:208-211`）。 */
private fun playlistLabel(pl: LocalPlaylist): String = buildString {
    append(pl.name.ifEmpty { "未命名歌单" })
    append(" · ")
    append(pl.trackCount.coerceAtLeast(pl.songs.size))
    append(" 首")
    if (pl.isPrivate) append(" · 私密")
}

/**
 * 面板里的一行，对应原版 `_add_action`（`ActionSheet.gd:307-317`）。
 *
 * 样式逐项照抄：`button_box(SURFACE_HI)` —— **只有填充、没有发丝描边**（`button_box` 就是
 * `panel_box` 的转发，`AppTheme.gd:580-581`），圆角 `RADIUS_SMALL`(32px)、最小高 = 行高、
 * `HORIZONTAL_ALIGNMENT_LEFT`、字号 `FONT_BODY`。
 *
 * 两个容易做错、这里按原版纠正的点：
 * - `muted` **只换字色**（`TEXT_DIM`），底色照样是 `SURFACE_HI`（`ActionSheet.gd:314-315`
 *   只对 `font_color` 做三元，`normal` 样式框永远是 `SURFACE_HI`）。
 * - `button_box` 的内容边距是 0（`panel_box` 的 `pad` 缺省 0，`AppTheme.gd:550`），
 *   所以文字**贴着行的左边缘**，行内没有左右内边距。
 *
 * 按下态 = `button_box(LINE)`（`:317`）：原版是**整块底色换成 LINE**，不是涟漪，
 * 所以这里关掉 M3 的 ripple 自己换底色。原版没有 `Card.add_press_feedback` 的缩放
 * ——那个是 `SubScreen.make_row` 才挂的。
 */
@Composable
private fun SheetRow(
    label: String,
    muted: Boolean = false,
    height: Double = ROW_HEIGHT_DP,
    onClick: () -> Unit,
) {
    val c = PullTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(if (pressed) c.line else c.surfaceHi)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            color = if (muted) c.textDim else c.text,
            fontSize = Typo.BODY.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
