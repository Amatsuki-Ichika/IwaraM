package io.github.amatsukiichika.iwaramanager.data

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/* ---------- iwara API DTO ---------- */

data class IwaraVideoResponse(
    val id: String?,
    val title: String?,
    val body: String?,
    val rating: String?,
    val embedUrl: String?,
    val fileUrl: String?,
    @SerializedName("numLikes") val numLikes: Int?,
    @SerializedName("numViews") val numViews: Int?,
    @SerializedName("numComments") val numComments: Int?,
    val createdAt: String?,
    val updatedAt: String?,
    val user: IwaraUser?,
    val tags: List<IwaraTag>?,
    val file: IwaraFile?
)

data class IwaraUser(
    val id: String?,
    val name: String?,
    val username: String?
)

data class IwaraTag(
    val id: String?
)

data class IwaraFile(
    val id: String?,
    val name: String?
)

interface IwaraApiService {
    @GET("video/{id}")
    suspend fun video(@Path("id") id: String): IwaraVideoResponse
}

/* ---------- 服务构造 ---------- */

object IwaraApi {
    const val BASE_URL = "https://api.iwara.tv/"
    private const val THUMBNAIL_TEMPLATE =
        "https://files.iwara.tv/image/thumbnail/%s/thumbnail-00.jpg"

    /**
     * 可选的访问令牌。为 null/空 时即「无 token 公开模式」，只抓取公开视频。
     * 若填写（iwara `user/token` 返回的 accessToken），会以 `Authorization: Bearer` 附加请求头。
     */
    @Volatile
    var accessToken: String? = null

    fun thumbnailUrl(fileId: String): String = String.format(THUMBNAIL_TEMPLATE, fileId)

    val service: IwaraApiService by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .connectionPool(IwaraClient.sharedConnectionPool)
            .dispatcher(IwaraClient.sharedDispatcher)
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .header("User-Agent", "iwara-manager/1.0 (android)")
                accessToken?.takeIf { it.isNotBlank() }?.let {
                    builder.header("Authorization", "Bearer $it")
                }
                chain.proceed(builder.build())
            }
            .build()
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(IwaraApiService::class.java)
    }
}

/** 解析 iwara 返回的 ISO 时间字符串，失败返回 null。 */
fun parseIwaraDate(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return try {
        Instant.parse(raw).toEpochMilli()
    } catch (_: Exception) { // 纯格式解析，吞异常是有意的：两种 ISO 形态依次尝试，都不是则返回 null。
        try {
            java.time.OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant().toEpochMilli()
        } catch (_: Exception) { // 同上，第二形态也失败即按"无时间"处理。
            null
        }
    }
}