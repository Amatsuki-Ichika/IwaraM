package io.github.amatsukiichika.iwaram.data

import java.util.concurrent.ConcurrentHashMap

/**
 * 下载任务的**一次性控制意图**：UI/系统回调置入，下载线程**恰好消费一次**。
 *
 * 与持久状态的分工（审查报告 4.3「下载队列用一次性标记集合，建议显式状态机」）：
 * - 任务的「现在是什么状态」由 io.github.amatsukiichika.iwaram.DownloadState 的 Task.state
 *   与落盘的 PendingDownload.state 表达，二者是唯一真源；
 * - 这里只承载跨线程的**事件**（「用户刚点了暂停/取消」），事件必须被消费一次且不能丢失：
 *   暂停要保留半成品等下次续传、取消要删半成品与记录，二者都不该重复触发收尾。
 * 旧实现是 pauseRequested / cancelRequested 两个独立的 CopyOnWriteArraySet，消费点分散在
 * 四处（出队检查、读循环、退避轮询、重试判定），「先看取消再看暂停」的顺序靠调用点自己保证；
 * 这里把两种意图收进同一个 map，由 consumeStop 集中决定优先级（取消 > 暂停），
 * 消费即移除，避免同一事件被两个检查点各消费一次。
 *
 * 线程安全：置入来自 UI 线程/系统回调，消费来自 IO 线程；ConcurrentHashMap 保证可见性。
 * 纯逻辑无 Android 依赖，可直接 JVM 单测。
 */
internal class DownloadControl {

    /** 用户对一支视频的意图。必须被处理，不能静默丢弃。 */
    enum class Intent { CANCEL, PAUSE }

    private val intents = ConcurrentHashMap<String, Intent>()

    /**
     * 取消：优先级高于暂停。已排队的暂停意图被取消覆盖（取消要清掉文件与记录，
     * 不能因为先点了暂停就变成「只暂停」）。
     */
    fun requestCancel(videoId: String) {
        intents[videoId] = Intent.CANCEL
    }

    /** 暂停：不覆盖已有的取消意图（取消更彻底，语义更强）。 */
    fun requestPause(videoId: String) {
        intents.putIfAbsent(videoId, Intent.PAUSE)
    }

    /** 新任务开始/同一视频重新入队时清掉旧意图，避免上次的暂停/取消误伤这次下载。 */
    fun clear(videoId: String) {
        intents.remove(videoId)
    }

    /** 是否有任何未消费意图（退避轮询用：有意图就提前结束等待）。 */
    fun hasPending(videoId: String): Boolean = intents.containsKey(videoId)

    /**
     * 只消费取消意图（出队检查用）：返回 true 表示排队期间被取消。
     * 不动暂停意图 —— 已出队的 worker 可能还会把它当暂停处理。
     */
    fun consumeCancel(videoId: String): Boolean {
        if (intents[videoId] != Intent.CANCEL) return false
        intents.remove(videoId, Intent.CANCEL)
        return true
    }

    /**
     * 取出并消费一支视频的停止意图（读取循环/重试收尾用）。取消优先于暂停；
     * 返回 null 表示用户没要求停止，继续下载。
     */
    fun consumeStop(videoId: String): Intent? = intents.remove(videoId)
}
