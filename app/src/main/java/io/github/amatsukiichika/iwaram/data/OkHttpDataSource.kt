package io.github.amatsukiichika.iwaram.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * OkHttp 版 HTTP 数据源：让 ExoPlayer 的媒体请求与播放地址解析（IwaraSign.fetchQualities）
 * 走同一个网络栈、同一套浏览器头。Iwara 的 CDN/反爬对 DefaultHttpDataSource 底层的
 * HttpURLConnection 指纹不友好，媒体请求会收到非 2xx（4004）；依赖中没有
 * media3-datasource-okhttp，因此参照官方实现自写这份精简版。
 */
@UnstableApi
// 这是走 HTTP 的网络数据源，`isNetwork` 必须为 true，否则播放器的带宽统计与缓冲估算
// 会漏掉这一段（transferStarted/transferEnded 上报的数据不参与计量）。
class OkHttpDataSource(private val callFactory: Call.Factory) : BaseDataSource(/* isNetwork= */ true) {

    /** 播放/seek 时各自创建新数据源，工厂本身在 PlayerScreen 中只创建一次。 */
    class Factory(private val callFactory: Call.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = OkHttpDataSource(callFactory)
    }

    private var response: Response? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()
    private var uri: Uri? = null
    private var stream: InputStream? = null
    private var bytesToSkip = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)

        val resp: Response = try {
            callFactory.newCall(buildRequest(dataSpec)).execute()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException(
                e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }

        if (!resp.isSuccessful) {
            val code = resp.code
            val message = resp.message
            val headers = resp.headers.toMultimap()
            resp.close()
            throw HttpDataSource.InvalidResponseCodeException(
                code, message, null, headers, dataSpec, ByteArray(0)
            )
        }

        // 206 表示服务器接受了 Range 请求：起点必须等于请求的 position，否则读到的字节与
        // 播放器认为的位置错位（花屏 / 拖动后画面与进度条对不上）。只看 code 不够，必须
        // 校验 Content-Range 的起点；头缺失、起点错位、区间越界都按"不能安全续读"处理。
        if (resp.code == 206) {
            val requestedFrom = dataSpec.position
            if (resumeRange(resp.header("Content-Range"), requestedFrom) == null) {
                val contentRange = resp.header("Content-Range")
                resp.close()
                throw HttpDataSource.HttpDataSourceException(
                    DownloadRangeMismatchException(contentRange, requestedFrom),
                    dataSpec,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
        }

        response = resp
        responseHeaders = resp.headers.toMultimap()
        uri = Uri.parse(resp.request.url.toString())
        // OkHttp 5 的 Response.body 非空：正常 2xx 一定带回可读的 body。
        stream = resp.body.byteStream()

        // 服务器不支持 Range 而返回整段 200 时，读之前先丢弃 position 之前的字节。
        bytesToSkip = if (dataSpec.position > 0 && resp.code != 206) dataSpec.position else 0L

        val contentLength = resp.body.contentLength().takeIf { it > 0 } ?: C.LENGTH_UNSET.toLong()
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            contentLength != C.LENGTH_UNSET.toLong() -> (contentLength - bytesToSkip).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        // stream 只在 open() 成功后赋值、close() 清空；此时再读是调用方状态机出错，
        // 报文要能直接指向"没打开/已关闭"，而不是让人误以为响应体为空。
        val input = stream
            ?: throw IOException("OkHttpDataSource 未打开或已关闭：read() 必须在 open() 成功之后调用")
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        while (bytesToSkip > 0) {
            val skipped = input.skip(bytesToSkip)
            if (skipped > 0) {
                bytesToSkip -= skipped
            } else if (input.read() == -1) {
                return C.RESULT_END_OF_INPUT
            } else {
                bytesToSkip -= 1
            }
        }

        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) readLength
        else minOf(readLength.toLong(), bytesRemaining).toInt()
        val read = input.read(buffer, offset, toRead)
        if (read == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    override fun close() {
        try {
            response?.body?.close()
        } catch (_: IOException) {
            // 纯资源清理：close 失败不影响播放器状态推进，也无数据一致性风险，吞异常是有意的。
        } finally {
            response = null
            stream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun buildRequest(dataSpec: DataSpec): Request {
        val builder = Request.Builder().url(dataSpec.uri.toString())
        if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) ""
            else (dataSpec.position + dataSpec.length - 1).toString()
            builder.header("Range", "bytes=${dataSpec.position}-$end")
        }
        dataSpec.httpRequestHeaders.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }
}
