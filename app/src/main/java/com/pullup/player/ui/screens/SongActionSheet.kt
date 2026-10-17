package com.pullup.player.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pullup.player.data.BlacklistOption
import com.pullup.player.data.Track
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.LocalShell
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Stagger
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
 */
private const val SHEET_MIN_HEIGHT_DP = 248.0

/** `MAX_BLACKLIST_OPTIONS := 5`（`ActionSheet.gd:20`）。 */
private const val MAX_BLACKLIST_OPTIONS = 5

/** 副标题上临时反馈的存活时长：原版 `_show_feedback` 的 1.4 秒（`ActionSheet.gd:269`）。 */
private const val FEEDBACK_MS = 1400L

/**
 * 歌曲操作面板，移植自 `scripts/ui/ActionSheet.gd`。
 *
 * 面板有**两种内容共用一个副标题**（原版就是这个结构，收起再弹会闪一下，所以就地重铺）：
 * 1. 主操作列表，副标题＝歌手名；
 * 2. 「加入黑名单」二级面板，副标题「加入黑名单 · 点一下屏蔽或取消」。
 *
 * ★ 用户 m00510 裁定：删掉「添加到歌单」那条 —— 「＋」本来就是给播放列表预留的，
 * 实装时被做成了「添加到歌单」，现在「＋」已改回「追加到播放队列末尾」（`AddToQueue.gd`），
 * 长按菜单里这一条连同它的二级歌单选择器一起移除。
 *
 * 屏蔽的临时反馈写在副标题上，1.4 秒后自己收回去 —— 用代次守卫，
 * 过期那次不许覆盖新内容（原版 `_show_feedback` / `_restore_subtitle` 的 `_feedback_gen`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionSheet(
    song: Track,
    onDismiss: () -> Unit,
) {
    val app = LocalApp.current
    val shell = LocalShell.current
    val c = PullTheme.colors
    val context = LocalContext.current

    // 收藏状态会因本次点击而变，所以要订阅而不是读一次。
    val favorites by app.favorites.songs.collectAsStateWithLifecycle()
    val blacklist by app.blacklist.entries.collectAsStateWithLifecycle()

    /** 面板模式：0 = 主操作，1 = 黑名单。 */
    var mode by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf("") }
    // 代次守卫：只有最后一次反馈的定时器才有权收回副标题。
    var feedbackGen by remember { mutableIntStateOf(0) }

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
        mode == 1 -> "加入黑名单 · 点一下屏蔽或取消"
        else -> song.artist
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .padding(
                start = Dims.PAD_SCREEN.dp,
                end = Dims.PAD_SCREEN.dp,
                bottom = Dims.PAD_SCREEN.dp,
            )
            .heightIn(min = SHEET_MIN_HEIGHT_DP.dp),
        sheetState = sheetState,
        containerColor = c.surface,
        scrimColor = c.scrim.copy(alpha = 0.42f),
        shape = pullShape(Dims.RADIUS),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MARGIN_H_DP.dp, vertical = MARGIN_V_DP.dp),
            verticalArrangement = Arrangement.spacedBy(BLOCK_GAP_DP.dp),
        ) {
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

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
            ) {
                when (mode) {
                    1 -> {
                        val shown = blacklistOptions.size.coerceAtMost(MAX_BLACKLIST_OPTIONS)
                        blacklistOptions.take(shown).forEachIndexed { i: Int, option: BlacklistOption ->
                            val prefix = if (app.blacklist.hasPair(option.kind, option.value)) "取消屏蔽 · " else "屏蔽 · "
                            SheetRow(
                                label = prefix + option.label,
                                height = SUB_ROW_HEIGHT_DP,
                                index = i,
                                durationMs = Stagger.SUB_SHEET_DURATION_MS,
                            ) {
                                app.blacklist.toggle(option.kind, option.value)
                                feedback = ""
                            }
                        }
                        if (blacklistOptions.size > shown) {
                            SheetRow(
                                label = "还有 ${blacklistOptions.size - shown} 条不显示了",
                                muted = true,
                                height = SUB_ROW_HEIGHT_DP,
                                index = shown,
                                durationMs = Stagger.SUB_SHEET_DURATION_MS,
                            ) {}
                        }
                        SheetRow(
                            label = "返回",
                            height = SUB_ROW_HEIGHT_DP,
                            index = shown + 1,
                            durationMs = Stagger.SUB_SHEET_DURATION_MS,
                        ) { mode = 0 }
                        SheetRow(
                            label = "取消",
                            muted = true,
                            height = SUB_ROW_HEIGHT_DP,
                            index = shown + 2,
                            durationMs = Stagger.SUB_SHEET_DURATION_MS,
                        ) { onDismiss() }
                    }

                    else -> {
                        val fav = favorites.any { it.id == song.id && song.id != 0L }
                        SheetRow(label = if (fav) "取消收藏" else "收藏", index = 0) {
                            app.favorites.toggle(song)
                            onDismiss()
                        }
                        if (song.artistId > 0L || song.artist.isNotBlank()) {
                            SheetRow(label = "查看歌手", index = 1) {
                                onDismiss()
                                shell.openArtist(song)
                            }
                        }
                        SheetRow(label = "下一首播放", index = 2) {
                            app.playback.playAfterCurrent(song)
                            onDismiss()
                        }
                        // ★ 新增：分享
                        SheetRow(label = "分享", index = 3) {
                            onDismiss()
                            shareSong(context, song)
                        }
                        SheetRow(label = "加入黑名单", index = 4) {
                            feedback = ""
                            mode = 1
                        }
                        SheetRow(label = "取消", muted = true, index = 5) { onDismiss() }
                    }
                }
            }
        }
    }
}

/**
 * ★ 新增：把一首歌通过系统分享面板发出去。
 *
 * **分享内容规则**：
 * - 网易云歌曲（`id > 0` 且非本地）→ 「歌名 - 歌手」+ 网易云**移动端**官方链接。
 *   之所以用 `music.163.com/song?id=<id>`（不带 `#`）而不是桌面版 `#/song`，
 *   是因为移动端地址在微信/QQ 里会展开成带封面和歌名的卡片，桌面版链接在 App 内
 *   往往打不开。用户 m0xxxx 明确要求「分享链接为网易云官方链接」。
 * - 本地歌曲（`local = true` 或 `id <= 0`）→ 只发「歌名 - 歌手」纯文本。
 *   本地文件没有网易云 id、也没网页版，硬拼一个 `id=0` 反而误导。
 */
private fun shareSong(context: Context, song: Track) {
    val header = "${song.name} - ${song.artist}"
    val text = if (song.local || song.id <= 0L) {
        header
    } else {
        "$header\nhttps://music.163.com/song?id=${song.id}"
    }
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, header)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    // FLAG_ACTIVITY_NEW_TASK 是给「从非 Activity Context 起 chooser」兜底的；
    // LocalContext 正常拿到的是 Activity，加了也无害。
    val chooser = Intent.createChooser(sendIntent, "分享歌曲").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}

/**
 * 面板里的一行，对应原版 `_add_action`（`ActionSheet.gd:307-317`）。
 *
 * [index] / [durationMs] 负责**逐项错峰入场**（原版 `Motion.stagger_in`，
 * `Motion.gd:76-97`，调用点 `ActionSheet.gd:102` 主菜单 `0.03, 0.24`、
 * `:234,330,349,360,370` 二级面板 `0.03, 0.18`）：第 i 行延迟 `i * 30ms`，
 * 同时透明度 0→1、缩放 0.97→1。超出 `Stagger.MAX_ITEMS` 的行不做动画直接可见
 * （原版 `Motion.gd:84-88`：屏外的项各建一个 Tween 只是白烧一帧）。
 *
 * 只动 alpha 与 scale、**不动位置**（`Motion.gd:69-71`：父容器每帧重排子节点位置，
 * 动位置会被立刻覆盖）。
 */
@Composable
private fun SheetRow(
    label: String,
    muted: Boolean = false,
    height: Double = ROW_HEIGHT_DP,
    index: Int = 0,
    durationMs: Int = Stagger.SHEET_DURATION_MS,
    onClick: () -> Unit,
) {
    val c = PullTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // 错峰入场：一次性 play 一条「从 0 到 1」的动画，progress 同时驱动 alpha 与 scale。
    val enter = remember { Animatable(0f) }
    LaunchedEffect(index, durationMs) {
        val delay = Stagger.delayFor(index, Stagger.SHEET_STEP_MS)
        val span = Stagger.durationFor(index, durationMs)
        if (span <= 0) {
            enter.snapTo(1f)
            return@LaunchedEffect
        }
        enter.snapTo(0f)
        enter.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = span,
                delayMillis = delay.toInt(),
                easing = FastOutSlowInEasing,
            ),
        )
    }
    val scale = Stagger.FROM_SCALE + (1f - Stagger.FROM_SCALE) * enter.value

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
            .graphicsLayer {
                alpha = enter.value
                scaleX = scale
                scaleY = scale
            }
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