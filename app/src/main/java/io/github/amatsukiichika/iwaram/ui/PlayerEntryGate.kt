package io.github.amatsukiichika.iwaram.ui

/**
 * 入场过渡门（纯逻辑，单测见 PlayerEntryGateTest）。
 *
 * 背景：点进播放页时，整页 UI 树（PlayerView 的 inflate/attach、详情/评论/相关列表）与
 * ExoPlayer 首装挤在滑入动画的第一帧里组合，主线程排不过队，用户看到的正是"进入播放页的
 * 动画本身掉帧"。过渡期间只组合轻壳（黑底视频位 + 封面 + 标题 + 返回），重内容等滑动
 * 落位后再组合 —— 本函数就是"落位"的判据。
 *
 * 为什么是"先见过滑动、再见到静止"，而不是直接看 `!pageAnimating`：
 * 宿主 MainActivity 先把 `showingPlayer` 置 true（播放页开始组合），**之后**才在
 * `LaunchedEffect(showingPlayer)` 里把 `playerSliding` 置 true 并启动动画。因此本页首次
 * 组合读到的 `pageAnimating` 仍是 false；只看 false 会在动画第一帧就放行，等于没推迟。
 *
 * 放行后要**永久保持**由调用点负责（记住的状态一旦置 true 不再复位）：退场动画期间
 * `pageAnimating` 会再次为 true，若退回轻壳就会销毁播放器实例与详情/滚动状态
 * （重置播放位置 = 回归）。
 *
 * @param pageAnimating 宿主下发的"入场/退场滑动是否正在播"
 * @param animationSeen 此前是否已观测到滑动开始（跨帧记忆由调用点保存）
 * @return true = 滑动已落位，可以组合重内容
 */
internal fun entryTransitionSettled(pageAnimating: Boolean, animationSeen: Boolean): Boolean =
    animationSeen && !pageAnimating

/**
 * 入场门的兜底时长（毫秒）。
 *
 * 当前宿主每次打开播放页都会播入场动画（PAGE_SLIDE_MS = 320ms），正常路径在动画结束时
 * 就放行；这个兜底只防"宿主将来不再播动画（pageAnimating 恒 false）"时整页永久停在
 * 轻壳。取 500ms：仍比 320ms 长，正常路径永远先到达；异常路径也不会让用户对着轻壳等太久。
 */
internal const val PLAYER_ENTRY_GATE_FALLBACK_MS = 500L
