package com.aicode.helper.service

import com.aicode.helper.settings.AiCodeSettings
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit

class AiApiService {

    private val log = Logger.getInstance(AiApiService::class.java)

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Message(val role: String, val content: String)

    fun chat(messages: List<Message>): String {
        val settings = AiCodeSettings.getInstance()
        val requestBody = buildRequestBody(messages, settings, stream = false)
        val request = buildRequest(requestBody, settings)

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "无响应内容"
                throw IOException("API 请求失败 (${response.code}): $errorBody")
            }
            val responseBody = response.body?.string()
                ?: throw IOException("响应内容为空")
            return parseNonStreamResponse(responseBody)
        }
    }

    fun chatStream(
        messages: List<Message>,
        onChunk: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val settings = AiCodeSettings.getInstance()
        val requestBody = buildRequestBody(messages, settings, stream = true)
        val request = buildRequest(requestBody, settings)

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "无响应内容"
                    onError("API 请求失败 (${response.code}): $errorBody")
                    return
                }
                response.body?.source()?.let { source ->
                    val reader = BufferedReader(source.inputStream().reader())
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line!!.trim()
                        if (trimmed.startsWith("data: ")) {
                            val data = trimmed.removePrefix("data: ").trim()
                            if (data == "[DONE]") break
                            try {
                                val chunk = parseStreamChunk(data)
                                if (chunk.isNotEmpty()) {
                                    onChunk(chunk)
                                }
                            } catch (ex: Exception) {
                                log.debug("无法解析流式响应行: $data", ex)
                            }
                        }
                    }
                }
                onComplete()
            }
        } catch (e: IOException) {
            onError("网络错误: ${e.message}")
        }
    }

    private fun buildRequestBody(
        messages: List<Message>,
        settings: AiCodeSettings,
        stream: Boolean
    ): okhttp3.RequestBody {
        val messagesArray = JsonArray()
        messages.forEach { msg ->
            val obj = JsonObject()
            obj.addProperty("role", msg.role)
            obj.addProperty("content", msg.content)
            messagesArray.add(obj)
        }

        val body = JsonObject()
        body.addProperty("model", settings.modelName)
        body.add("messages", messagesArray)
        body.addProperty("max_tokens", settings.maxTokens)
        body.addProperty("temperature", settings.temperature)
        body.addProperty("stream", stream)

        return gson.toJson(body).toRequestBody("application/json; charset=utf-8".toMediaType())
    }

    private fun buildRequest(
        requestBody: okhttp3.RequestBody,
        settings: AiCodeSettings
    ): Request {
        val baseUrl = settings.apiUrl.trimEnd('/')
        return Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()
    }

    private fun parseNonStreamResponse(responseBody: String): String {
        val json = gson.fromJson(responseBody, JsonObject::class.java)
        return json.getAsJsonArray("choices")
            ?.get(0)?.asJsonObject
            ?.getAsJsonObject("message")
            ?.get("content")?.asString
            ?: "无法解析响应内容"
    }

    private fun parseStreamChunk(data: String): String {
        val json = gson.fromJson(data, JsonObject::class.java)
        return json.getAsJsonArray("choices")
            ?.get(0)?.asJsonObject
            ?.getAsJsonObject("delta")
            ?.get("content")?.asString
            ?: ""
    }
}
