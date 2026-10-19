package io.github.amatsukiichika.iwaramanager.ui.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import io.github.amatsukiichika.iwaramanager.ui.components.BAR_BOTTOM_INSET
import com.kyant.backdrop.Backdrop
import io.github.amatsukiichika.iwaramanager.ui.components.GLASS_BAR_SIZE
import io.github.amatsukiichika.iwaramanager.ui.components.GlassTabItem
import io.github.amatsukiichika.iwaramanager.ui.components.TabGlassBar
import io.github.amatsukiichika.iwaramanager.ui.components.glassSideInset

/**
 * 底部导航栏：TG 同款悬浮柔光玻璃药丸（[TabGlassBar]，底栏唯一调用点）。
 * 大药丸玻璃容器 + 小药丸选项 + 实心/空心图标过渡 + 单击切换/拖动选择（微动 + 微形变）。
 *
 * 几何（高度/左右内缩，锚点 = 底边中心）与宿主内缩都收进本组件，宿主只传"背景层"；
 * 材质（底色/透明罩/噪点/模糊）由 [io.github.amatsukiichika.iwaramanager.ui.components.LocalGlassSettings] 决定，固定用常量。
 * 背景源接入 AndroidLiquidGlass 的 drawBackdrop 管线，类型为 [Backdrop] 接口 ——
 * 宿主传自有层实现（MainContentBackdrop，拉伸冻结用），不依赖库的 LayerBackdrop。
 */
@Composable
fun IwaraBottomBar(
    current: HomeTab,
    onSelect: (HomeTab) -> Unit,
    /** 液态玻璃背景层（宿主创建）；传 null = 本帧走半透明路径。 */
    backdrop: Backdrop? = null
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            // 左右内缩随「底栏大小」等比联动（锚点 = 底边中心），底部悬浮保持 10dp。
            //   大小固定在 [GLASS_BAR_SIZE]。
            .padding(
                start = glassSideInset(GLASS_BAR_SIZE, screenWidthDp),
                end = glassSideInset(GLASS_BAR_SIZE, screenWidthDp),
                bottom = BAR_BOTTOM_INSET
            )
    ) {
        TabGlassBar(
            items = HomeTab.entries.map { tab ->
                GlassTabItem(label = tab.label, filled = tab.icon, outlined = tab.outlinedIcon())
            },
            selected = current.ordinal,
            onSelect = { onSelect(HomeTab.entries[it]) },
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** HomeTab 的空心图标对（实心 = [HomeTab.icon]；material-icons-extended 现成三对）。 */
private fun HomeTab.outlinedIcon(): ImageVector = when (this) {
    HomeTab.MANAGER -> Icons.Outlined.Folder
    HomeTab.VIDEO -> Icons.Outlined.SmartDisplay
    HomeTab.ME -> Icons.Outlined.Person
}
