package io.github.amatsukiichika.iwaram.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 用系统浏览器打开链接，失败静默忽略（本应用只负责把地址交给系统）。
 *
 * 播放详情页的「网页」入口与作者页的「主页 ↗」入口共用这一份实现：以前两处各自内联，
 * 行为一旦分叉（例如漏加 NEW_TASK）就只有一处能生效。
 */
internal fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
