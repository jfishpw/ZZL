package com.zzl.guardian.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.zzl.guardian.BuildConfig
import com.zzl.guardian.data.api.ApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Retrofit 要求 baseUrl 非空且以 / 结尾。这里给一个占位地址，
     * 真实主机与端口由 [BaseUrlInterceptor] 在每个请求上动态替换。
     * 好处：切换服务器不需要重建 Retrofit / OkHttp 实例。
     */
    private const val PLACEHOLDER_BASE_URL = "http://127.0.0.1/"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(holder: ServerConfigHolder): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(BaseUrlInterceptor(holder))
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS) // WebSocket 保活
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(client: OkHttpClient, json: Json): ApiService =
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
}

/**
 * 把请求的 scheme / host / port 重写为当前运行期配置。
 * 这样「服务器地址可配置」不需要重建任何网络对象。
 */
private class BaseUrlInterceptor(private val holder: ServerConfigHolder) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val config = holder.current
        val original = chain.request()
        val rewrittenUrl = original.url.newBuilder()
            .scheme(config.scheme)
            .host(config.host)
            .port(config.port)
            .build()
        return chain.proceed(original.newBuilder().url(rewrittenUrl).build())
    }
}
