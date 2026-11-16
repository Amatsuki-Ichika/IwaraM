package io.github.amatsukiichika.iwaram.ui

/**
 * 全屏播放控制条四边的安全内缩（单位 dp）。纯数据，单测见 PlayerControlInsetsTest。
 */
internal data class PlayerControlBarInsets(
    val start: Float,
    val top: Float,
    val end: Float,
    val bottom: Float
)

/** 全屏控制条内缩下限（dp）：按钮不贴屏幕边，也不被屏幕圆角/手势条压住。 */
internal const val PLAYER_BAR_MIN_SIDE_DP = 16f
internal const val PLAYER_BAR_MIN_TOP_DP = 12f
internal const val PLAYER_BAR_MIN_BOTTOM_DP = 16f

/**
 * 全屏控制条四边内缩（纯函数，单测见 PlayerControlInsetsTest）。
 *
 * 用户要求"按钮内缩、避让刘海和手势条、画面不缩"——这里只回答"控制条内容要内缩多少"，
 * 视频画面本身不使用这套内缩（见 PlayerScreenContent 的视频区），所以画面不缩。
 * - 左右 = max(16dp, 刘海左/右内边距)：横屏时挖孔常在侧边，按钮不能被它挡住；
 * - 上 = max(12dp, 刘海顶部内边距)：竖屏挖孔/刘海区；
 * - 下 = max(16dp, 导航栏底边距, 系统手势区底边距)：三键导航与手势导航都照顾到，防误触。
 * 沉浸全屏隐藏系统栏后导航栏内边距可能归零，固定下限仍保证按钮离底边 16dp。
 */
internal fun fullscreenControlBarInsets(
    cutoutStartDp: Float,
    cutoutTopDp: Float,
    cutoutEndDp: Float,
    navigationBarBottomDp: Float,
    systemGestureBottomDp: Float
): PlayerControlBarInsets = PlayerControlBarInsets(
    start = maxOf(PLAYER_BAR_MIN_SIDE_DP, cutoutStartDp),
    top = maxOf(PLAYER_BAR_MIN_TOP_DP, cutoutTopDp),
    end = maxOf(PLAYER_BAR_MIN_SIDE_DP, cutoutEndDp),
    bottom = maxOf(PLAYER_BAR_MIN_BOTTOM_DP, navigationBarBottomDp, systemGestureBottomDp)
)
