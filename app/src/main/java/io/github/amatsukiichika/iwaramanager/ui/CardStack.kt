package io.github.amatsukiichika.iwaramanager.ui

import android.os.Build
import android.util.Log
import android.view.RoundedCorner
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 二级页"卡片化让位"的**共享规格**（全局一处定义，不在各页各写一套数字）。
 *
 * 观感：推入二级页时，被压在下面的那一层**向左轻移 + 变暗**；返回时向右回到原位、变亮；
 * 预测式返回手势拖动期间，下层跟着手指进度**实时**回位（不是松手后才动）。
 * 页面圆角**只在看得见的时候存在**（见 [rememberAdaptivePageShape]）。
 *
 * 位移与压暗都是"被盖住的程度 c"的函数（c=0 完全露出，c=1 被完全盖住），
 * 各层只负责把自己的 c 算对，动画时长统一用 [COVER_MOTION_MS]。
 *
 * [COVER_SHIFT_DP] 为**固定 dp**（而非屏宽比例）：口径 = 条目卡的"图标右边第一个字"恰被屏幕
 * 左缘盖住 —— 16dp 列表边距 + 16dp 卡内边距 + 22dp 图标 + 12dp 间距 = 66dp 至首字左缘
 * （右缘 ≈80dp），取中点 76dp ⇒ 图标整体没入屏缘、第一个字恰被压在下面。所有层的让位共用
 * 这一个数字。
 */
val COVER_SHIFT_DP = 76.dp

/**
 * 被盖住时的**最大**压暗强度（cover = 1 时；拖动期间按 c 线性缩放）。
 *
 * 全局唯一的压暗规格，各消费点（MainActivity 主内容压暗层、IwaraOverlayHost 被盖页面
 * 内部压暗层）都乘这个常量，改一处即全局生效。
 */
const val COVER_DIM_ALPHA = 0.40f

/** 让位/回位动画时长；与二级页进出场的 320ms 同源，才能"同步滑回"。 */
const val COVER_MOTION_MS = 320

private const val TAG = "ScreenCorner"

/**
 * 二级页圆角方案：**四角自适应屏幕物理圆角 + 看不见时不裁弧**。
 *
 * - **四角分别读**：API 31+ `WindowInsets.getRoundedCorner(POSITION_*)` 返回每角的 radius + 圆心，
 *   各家 ROM 的真实弧度都能读到；不少机型上下圆角半径不同，必须四角分开取值（只读左上角、
 *   四角共用是错的）。
 * - **看不见时不裁**：页面静止铺满时（弧线与玻璃角重合、本来不可见），页面是**实心直角矩形**，
 *   和任何普通全屏 app 一样，截图零穿帮；"恒裁物理圆角"会连同弧线外的黑垫一起进截图。
 * - 兜底：API<31 无官方 API、或 ROM 不上报（返回 null/0）→ 该角用 [SECONDARY_PAGE_RADIUS]
 *   （28dp，M3 BottomSheet 规范值）。
 */
val SECONDARY_PAGE_RADIUS = 28.dp

/** 合理上限：个别 ROM 会报离谱值；Compose 本身也会按边长收缩，这里只是提前拦一道。 */
private val MAX_CORNER_DP = 60.dp

/** 四角圆角半径（dp）。左上/右上/右下/左下**分别取值**——上下可以不同。 */
data class PageCornerRadii(
    val topLeft: Dp,
    val topRight: Dp,
    val bottomRight: Dp,
    val bottomLeft: Dp
)

private val FALLBACK_RADII = PageCornerRadii(
    SECONDARY_PAGE_RADIUS, SECONDARY_PAGE_RADIUS, SECONDARY_PAGE_RADIUS, SECONDARY_PAGE_RADIUS
)

/**
 * 进程内缓存上一次取到的真实圆角。
 *
 * 页面每次重新进入组合都会新起一个 `remember`，若以兜底值起步，圆角会先"缩一下"再跳回来；
 * 播放页每秒刷新进度会反复重入组合，缓存住之后重入也直接是正确值。
 */
private var cachedRadii: PageCornerRadii? = null

/**
 * 读屏幕**四角**物理圆角（dp）。API 31+ 分别读四个 `RoundedCorner`；ROM 不上报的角单独兜底，
 * 读到的真值绝不丢弃。
 */
@Composable
fun rememberPageCornerRadii(): PageCornerRadii {
    val view = LocalView.current
    val density = LocalDensity.current
    var radii by remember { mutableStateOf(cachedRadii ?: FALLBACK_RADII) }
    LaunchedEffect(view) {
        // rootWindowInsets 在 attach 之前是 null —— 轮询几次（最多约 0.5s），拿不到就用兜底值。
            repeat(10) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val ins = view.rootWindowInsets
                    if (ins != null) {
                        val tl = ins.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
                        val tr = ins.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0
                        val br = ins.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
                        val bl = ins.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
                        if (tl > 0 || tr > 0 || br > 0 || bl > 0) {
                            fun px(r: Int): Dp = if (r > 0) {
                                with(density) { r.toDp() }.coerceAtMost(MAX_CORNER_DP)
                            } else {
                                SECONDARY_PAGE_RADIUS
                            }
                            val next = PageCornerRadii(px(tl), px(tr), px(br), px(bl))
                            // 只在值真的变化时打日志：每次重入组合都打会把诊断信息淹掉。
                            if (cachedRadii != next) {
                                Log.i(TAG, "屏幕物理圆角 TL${tl} TR${tr} BR${br} BL${bl}px → $next")
                                DiagLog.log(TAG, "屏幕物理圆角四角 $next")
                            }
                            cachedRadii = next
                            radii = next
                            return@LaunchedEffect
                        }
                    }
                }
                delay(50)
            }
        Log.i(TAG, "系统未上报屏幕圆角，四角用兜底 $SECONDARY_PAGE_RADIUS")
        DiagLog.log(TAG, "系统未上报屏幕圆角，四角兜底 $SECONDARY_PAGE_RADIUS")
    }
    return radii
}

/**
 * 二级页自适应形状：**圆角只在看得见的时候存在**。
 *
 * - [exposed] = true（页面进出场动画中 / 预测式返回跟手中 / 被上层盖住让位中——即弧线露出来的
 *   所有场合）→ 四角**立即**裁成屏幕物理圆角（[rememberPageCornerRadii]，拿不到的角用 28dp 兜底）；
 * - [exposed] = false（页面静止铺满屏幕）→ **实心直角矩形**。静止时弧线与玻璃角重合、本来就
 *   看不见，直角让页面与任何普通全屏 app 无异 —— **截图零穿帮**，也与设备圆角报告是否准确无关。
 *
 * 收放**不做任何过渡动画** —— 第一帧运动弧线就必须在位，320ms 渐变期间方角露馅。
 * exposed 翻转即整形状瞬切（重组当帧生效，无补间）。
 */
@Composable
fun rememberAdaptivePageShape(exposed: Boolean): Shape {
    val radii = rememberPageCornerRadii()
    // 把 Shape **remember 住**：否则每次重组都 new 一个 RoundedCornerShape，
    //   `Surface(shape = …)` 的参数每次都"变了"，平白多出无效的绘制失效。
    return remember(exposed, radii) {
        val f = if (exposed) 1f else 0f
        RoundedCornerShape(
            topStart = radii.topLeft * f,
            topEnd = radii.topRight * f,
            bottomEnd = radii.bottomRight * f,
            bottomStart = radii.bottomLeft * f
        )
    }
}
