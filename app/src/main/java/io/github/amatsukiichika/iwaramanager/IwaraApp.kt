package io.github.amatsukiichika.iwaramanager

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import coil.ImageLoader
import coil.ImageLoaderFactory
import io.github.amatsukiichika.iwaramanager.data.AppDatabase
import io.github.amatsukiichika.iwaramanager.data.IwaraRepository
import io.github.amatsukiichika.iwaramanager.data.IwaraSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * UI 层取仓库的正门：MainActivity 根部下发，界面经它读数据/设置，
 * 不再各自 `(context.applicationContext as IwaraApp).repository` 强转。
 * 仓库是进程级单例，用 static 版本避免读取处失效重组。
 */
val LocalAppRepository = staticCompositionLocalOf<IwaraRepository> {
    error("LocalAppRepository 未提供：应在 MainActivity 根部 CompositionLocalProvider 下发")
}

class IwaraApp : Application(), ImageLoaderFactory {
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val repository: IwaraRepository by lazy { IwaraRepository(this, database) }

    /**
     * 全 app 统一 Coil ImageLoader —— 关 crossfade（列表滚动时逐帧 alpha 动画是无谓开销；
     * VideoCard 已逐请求关掉，这里统一默认值），缓存策略用默认。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).crossfade(false).build()

    /** 只用于「预热」的一次性作用域：进程级、不随任何界面销毁。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        // 会话恢复放在进程起点：下载/扫描服务先于浏览 tab 触发时，元数据请求从第一次起就带可用 token。
        IwaraSession.restore(this)

        // 提前在 IO 线程把两个懒加载单例建出来：此前它们的首次访问发生在 `VideoViewModel.init`
        //   （主线程）——`IwaraRepository` 构造期要读 SharedPreferences，prefs 里还存着下载历史
        //   (≤100)/待续传(50)/播放列表缓存的 JSON，首次访问会触发整份 XML 的解析，那一帧就是
        //   白屏/卡顿。挪到 IO 线程预热后，主线程那一次访问拿到的已经是初始化好的实例。
        appScope.launch { runCatching { repository } }
    }
}
