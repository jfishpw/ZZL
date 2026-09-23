package com.zzl.guardian.data

import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 带鉴权的图片加载器。
 *
 * 为什么不用现成的图片库：本项目对图片只有一个需求 ——
 * 从自己的服务器带家长令牌取一张 JPEG。为此引入一个图片库
 * （Coil/Glide，连同一串传递依赖）并不划算，而 OkHttp 已经在依赖里了。
 *
 * 两个必须自己处理的点：
 *
 * 1. **Authorization 头**。截屏接口需要家长令牌，而通用图片库默认不带鉴权信息
 *    —— 少了它图片会静默加载失败（界面上只显示一个空白框），排查很费时间。
 *
 * 2. **URL 走共享的 OkHttpClient**。它的 [com.zzl.guardian.di.AppModule] 里那个
 *    拦截器会把 scheme/host/port 重写成当前服务器，因此换服务器后图片地址
 *    自动跟着变，不需要在这里重复实现地址解析。
 */
@Singleton
class AuthedImageLoader @Inject constructor(
    private val client: OkHttpClient,
) {

    /**
     * 内存缓存。
     *
     * 截屏单张最大 1 MB，因此缓存张数必须设上限 ——
     * 无上限的缓存等于把整个相册放进内存里。
     * 用 LinkedHashMap 的 accessOrder 做 LRU：访问过的移到队尾，淘汰队首。
     */
    private val cache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > MAX_CACHED
    }

    private val lock = Any()

    /**
     * 取图片原始字节。
     *
     * 缓存字节而不是解码后的 Bitmap：Bitmap 的内存占用远大于 JPEG 字节
     * （一张 1080x1920 的位图约 8 MB，而 JPEG 只有几百 KB），
     * 缓存字节可以用同样的内存存下十几倍的图。
     *
     * @return 失败返回 null（鉴权过期、被清理、网络不通都走这里）
     */
    suspend fun loadBytes(path: String, authHeader: String): ByteArray? {
        synchronized(lock) {
            cache[path]?.let { return it }
        }

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                // 路径由调用方给出（形如 api/devices/1/screenshots/2/image），
                // host 由 BaseUrlInterceptor 重写
                .url("http://placeholder/$path")
                .header("Authorization", authHeader)
                .build()

            val bytes = runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "加载图片失败 HTTP ${response.code}：$path")
                        return@use null
                    }
                    response.body?.bytes()
                }
            }.onFailure { Log.w(TAG, "加载图片异常：${it.message}") }
                .getOrNull()

            if (bytes != null) {
                synchronized(lock) { cache[path] = bytes }
            }
            bytes
        }
    }

    /** 解码成 Bitmap。解码放在 IO 线程，避免阻塞界面。 */
    suspend fun decode(bytes: ByteArray) = withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            .onFailure { Log.w(TAG, "解码图片失败", it) }
            .getOrNull()
    }

    /** 删除某张截屏后清掉缓存，否则重开还会看到已删除的图 */
    fun invalidate(path: String) {
        synchronized(lock) { cache.remove(path) }
    }

    fun clear() {
        synchronized(lock) { cache.clear() }
    }

    private companion object {
        const val TAG = "AuthedImageLoader"

        /** 约 12 MB 上限，够缓存十几张典型截屏 */
        const val MAX_CACHED = 16
    }
}
