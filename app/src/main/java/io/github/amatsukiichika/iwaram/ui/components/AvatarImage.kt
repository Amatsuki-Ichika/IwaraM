package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import io.github.amatsukiichika.iwaram.R

/**
 * 全仓库**唯一**的头像组件（播放页作者行 / 「我的」页账号卡共用）。
 *
 * - [url] 非空 → Coil 异步加载（离线时自动落磁盘缓存，历史头像仍可显示）；
 * - [url] 为空 → 打包进 app 的 iwara 风格默认头像
 *   （`res/drawable-nodpi/default_avatar.png`；在线但作者没传头像时同样落到这里，与 iwara 网站行为一致）。
 *
 * 圆形裁剪在组件内做，调用点只给尺寸。替换默认头像图 = 同名覆盖 PNG，代码零改动。
 */
@Composable
fun AvatarImage(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val clipModifier = modifier.size(size).clip(CircleShape)
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            modifier = clipModifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Image(
            painter = painterResource(R.drawable.default_avatar),
            contentDescription = contentDescription,
            modifier = clipModifier,
            contentScale = ContentScale.Crop
        )
    }
}
