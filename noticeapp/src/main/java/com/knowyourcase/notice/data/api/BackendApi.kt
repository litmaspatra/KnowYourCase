package com.knowyourcase.notice.data.api

import android.content.Context
import com.google.gson.JsonObject
import com.knowyourcase.notice.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class ParseRequest(val cnr: String, val html: String)
data class CaptchaRequest(val image_base64: String)
data class CaptchaResponse(val solved: String?)

interface ApiService {
    @GET("health") suspend fun health(): Response<Map<String, Any>>
    @POST("solve-captcha") suspend fun solveCaptcha(@Body request: CaptchaRequest): Response<CaptchaResponse>
    @POST("parse") suspend fun parseCase(@Body request: ParseRequest): Response<JsonObject>
}

object BackendConfig {
    const val DEFAULT_URL = "https://knc-backend.onrender.com"
    private const val PREFS = "notice_tracker_settings"
    private const val KEY_URL = "backend_url"

    fun url(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, DEFAULT_URL).orEmpty().trim()
        return normalize(saved.ifBlank { DEFAULT_URL })
    }

    fun save(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_URL, normalize(value.ifBlank { DEFAULT_URL })).apply()
        RetrofitClient.clear()
    }

    fun reset(context: Context) = save(context, DEFAULT_URL)

    private fun normalize(value: String): String {
        val withScheme = if (value.startsWith("http://") || value.startsWith("https://")) value
            else "https://$value"
        return withScheme.trimEnd('/')
    }
}

object RetrofitClient {
    private var cachedUrl: String? = null
    private var cachedService: ApiService? = null

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun service(context: Context): ApiService {
        val url = BackendConfig.url(context)
        if (cachedService == null || cachedUrl != url) {
            cachedUrl = url
            cachedService = Retrofit.Builder()
                .baseUrl("$url/")
                .client(okHttp)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
        return cachedService!!
    }

    @Synchronized fun clear() {
        cachedUrl = null
        cachedService = null
    }
}
