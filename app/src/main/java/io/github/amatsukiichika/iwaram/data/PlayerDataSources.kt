package io.github.amatsukiichika.iwaram.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource

/**
 * 播放器的数据源工厂。
 *
 * HTTP(S) 播放地址走 [OkHttpDataSource] + [IwaraClient.mediaHttp]：与播放地址解析共享同一条
 * OkHttp 网络栈与浏览器头，并对带 expires 的签名地址动态附加 X-Version（Iwara 的 CDN 对
 * HttpURLConnection 的指纹不友好，DefaultHttpDataSource 在设备端普遍 4004）；本地
 * `content://` 由 [DefaultDataSource] 分派给 ContentDataSource，不受这些头影响。
 *
 * 放在 data 层而不是播放页：网络栈（OkHttp、签名、重定向策略）属于数据层细节，UI 只应拿到
 * "一个 DataSource.Factory"；播放器实例本身仍由播放页创建/释放（生命周期属于 UI）。
 */
@OptIn(UnstableApi::class)
internal fun playerDataSourceFactory(context: Context): DataSource.Factory =
    DefaultDataSource.Factory(context, OkHttpDataSource.Factory(IwaraClient.mediaHttp))
