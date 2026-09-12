package com.knowyourcase.app.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {
    @GET("health")
    suspend fun health(): Response<Map<String, Any>>

    @POST("solve-captcha")
    suspend fun solveCaptcha(@Body request: CaptchaRequest): Response<CaptchaResponse>

    @POST("parse")
    suspend fun parseCase(@Body request: ParseRequest): Response<CaseResponse>
}
