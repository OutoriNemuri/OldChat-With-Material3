package com.oldchat.material.core.media

import coil.intercept.Interceptor
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.size.Size
import com.oldchat.material.core.network.ServerConfig

/**
 * ALIGN-07：媒体/头像/封面加载失败时按候选线路重试（client-guide §6.1）。
 *
 * 单点实现：所有走 Coil 的图片（消息图片、视频缩略图、头像、音乐封面）都经过这里，
 * 不需要在每个调用点写回退逻辑。
 */
class MediaCandidatesInterceptor(
    private val serverConfig: ServerConfig
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request: ImageRequest = chain.request
        val data = request.data
        if (data !is String || !data.startsWith("http")) {
            return chain.proceed(request)
        }

        val candidates = serverConfig.alternativeOriginsFor(data)
        if (candidates.size <= 1) {
            return chain.proceed(request)
        }

        var last: ImageResult? = null
        for (url in candidates) {
            val result = chain.proceed(request.newBuilder().data(url).build())
            if (result is coil.request.SuccessResult) return result
            last = result
        }
        return last ?: chain.proceed(request)
    }
}
