package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * 横滑切页的统一转场规格（把多处重复的 slide+fade 收口到一处）。
 * 动画参数与既有实现一致（进 = 300ms 滑入 + 220ms 淡入，出 = 300ms 滑出 + 220ms 淡出），
 * 只把"方向怎么算"留给调用方 —— 各页的状态形态不同（enum.ordinal / 自定义 index / Comparable）。
 */
internal fun <T> horizontalSlideSpec(
    dirOf: (target: T, initial: T) -> Int
): AnimatedContentTransitionScope<T>.() -> ContentTransform = {
    val dir = dirOf(targetState, initialState)
    (slideInHorizontally(tween(300)) { full -> dir * full } + fadeIn(tween(220))) togetherWith
        (slideOutHorizontally(tween(300)) { full -> -dir * full } + fadeOut(tween(220)))
}
