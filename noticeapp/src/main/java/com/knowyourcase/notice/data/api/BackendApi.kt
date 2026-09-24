package com.knowyourcase.notice.data.api

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
    @GET("health")
    suspend fun health(): Response<Map<String, Any>>

    @POST("solve-captcha")
    suspend fun solveCaptcha(@Body request: CaptchaRequest): Response<CaptchaResponse>

    @POST("parse")
    suspend fun parseCase(@Body request: ParseRequest): Response<JsonObject>
}

object RetrofitClient {
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .build()

    val service: ApiService = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL + "/")
        .client(okHttp)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ApiService::class.java)
}
