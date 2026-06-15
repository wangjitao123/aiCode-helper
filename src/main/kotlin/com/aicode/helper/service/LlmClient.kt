package com.aicode.helper.service

import com.aicode.helper.agent.AgentMessage
import com.aicode.helper.agent.Role
import com.aicode.helper.settings.AiCodeSettings
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 上下文超限异常（HTTP 413 或 “context length exceeded” 类错误）。 */
class ContextOverflowException(message: String) : IOException(message)

/**
 * 决策 #1 / #2 / #4 的底层支撑：一个支持 tool-calling 的流式 LLM 客户端。
 *
 * streamCompletion 返回冷 Flow，逐事件流出：文本增量、工具调用就绪、完成。
 * 这样调用方（QueryEngine）就能在模型还在输出时把已就绪的 tool_use 块交给
 * StreamingToolExecutor 立即执行（决策 #4）。
 *
 * 与既有的 [AiApiService] 并存：AiApiService 负责右键菜单/补全等一次性场景，
 * 本类负责 agent 主循环。
 */
class LlmClient {

    private val log = Logger.getInstance(LlmClient::class.java)
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS) // long-running agent，读超时放宽
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed interface StreamEvent {
        data class TextDelta(val text: String) : StreamEvent
        data class ToolCallReady(val id: String, val name: String, val argumentsJson: String) : StreamEvent
        data class Completed(
            val finishReason: String?,
            val promptTokens: Int?,
            val completionTokens: Int?
        ) : StreamEvent
    }

    private class ToolAccumulator(
        var id: String? = null,
        var name: String? = null,
        val args: StringBuilder = StringBuilder()
    )

    /**
     * 流式调用补全接口。
     * @param messages 完整对话历史（含工具调用与工具结果）
     * @param tools OpenAI function 工具定义列表（可空）
     */
    fun streamCompletion(
        messages: List<AgentMessage>,
        tools: List<JsonObject>
    ): Flow<StreamEvent> = flow {
        val settings = AiCodeSettings.getInstance()
        val requestBody = buildRequestBody(messages, tools, settings, settings.modelName, stream = true)
        val request = buildRequest(requestBody, settings)

        val call = client.newCall(request)
        // 协程被取消（用户点“停止”）时取消底层 HTTP 调用，解开阻塞的读。
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        call.execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "无响应内容"
                if (response.code == 413 || errorBody.contains("context", ignoreCase = true) &&
                    errorBody.contains("length", ignoreCase = true)
                ) {
                    throw ContextOverflowException("上下文超限 (${response.code}): $errorBody")
                }
                throw IOException("API 请求失败 (${response.code}): $errorBody")
            }

            val source = response.body?.source() ?: throw IOException("响应内容为空")
            val reader = BufferedReader(source.inputStream().reader())

            val toolAcc = sortedMapOf<Int, ToolAccumulator>()
            val emittedTools = mutableSetOf<Int>()
            var finishReason: String? = null
            var promptTokens: Int? = null
            var completionTokens: Int? = null

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                currentCoroutineContext().ensureActive()
                val trimmed = line!!.trim()
                if (!trimmed.startsWith("data:")) continue
                val data = trimmed.removePrefix("data:").trim()
                if (data == "[DONE]") break

                val json = try {
                    gson.fromJson(data, JsonObject::class.java)
                } catch (e: Exception) {
                    log.debug("无法解析流式行: $data", e)
                    continue
                }

                json.getAsJsonObject("usage")?.let { usage ->
                    promptTokens = usage.get("prompt_tokens")?.takeIf { !it.isJsonNull }?.asInt
                    completionTokens = usage.get("completion_tokens")?.takeIf { !it.isJsonNull }?.asInt
                }

                val choice = json.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: continue
                choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.let { finishReason = it.asString }

                val delta = choice.getAsJsonObject("delta") ?: continue

                // 文本增量
                delta.get("content")?.takeIf { !it.isJsonNull }?.asString?.let { content ->
                    if (content.isNotEmpty()) emit(StreamEvent.TextDelta(content))
                }

                // 工具调用增量（按 index 累积）
                delta.getAsJsonArray("tool_calls")?.forEach { tcEl ->
                    val tc = tcEl.asJsonObject
                    val index = tc.get("index")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                    val acc = toolAcc.getOrPut(index) { ToolAccumulator() }
                    tc.get("id")?.takeIf { !it.isJsonNull }?.asString?.let { acc.id = it }
                    tc.getAsJsonObject("function")?.let { fn ->
                        fn.get("name")?.takeIf { !it.isJsonNull }?.asString?.let { acc.name = it }
                        fn.get("arguments")?.takeIf { !it.isJsonNull }?.asString?.let { acc.args.append(it) }
                    }
                    // 决策 #4：一旦某个工具调用的参数已是合法 JSON，立刻就绪 → 可在模型仍在输出时执行
                    if (index !in emittedTools && acc.name != null && isCompleteJson(acc.args.toString())) {
                        emittedTools.add(index)
                        emit(
                            StreamEvent.ToolCallReady(
                                id = acc.id ?: "call_$index",
                                name = acc.name!!,
                                argumentsJson = acc.args.toString().ifBlank { "{}" }
                            )
                        )
                    }
                }
            }

            // 冲刷尚未就绪的工具调用（参数为空或无大括号的场景）
            for ((index, acc) in toolAcc) {
                if (index in emittedTools || acc.name == null) continue
                emit(
                    StreamEvent.ToolCallReady(
                        id = acc.id ?: "call_$index",
                        name = acc.name!!,
                        argumentsJson = acc.args.toString().ifBlank { "{}" }
                    )
                )
            }

            emit(StreamEvent.Completed(finishReason, promptTokens, completionTokens))
        }
    }

    /**
     * 非流式摘要调用——供上下文压缩（决策 #3 的 L3/L4）使用。
     * 使用更便宜的 summaryModelName（对应源码里 fork 出 Haiku 子 agent 做摘要）。
     */
    fun summarize(instruction: String, content: String): String {
        val settings = AiCodeSettings.getInstance()
        val model = settings.summaryModelName.ifBlank { settings.modelName }
        val messages = listOf(
            AgentMessage(Role.SYSTEM, instruction),
            AgentMessage(Role.USER, content)
        )
        val body = buildRequestBody(
            messages = messages,
            tools = emptyList(),
            settings = settings,
            model = model,
            stream = false,
            // 实测压缩摘要的 p99.99 输出约 17,387 tokens，因此预算取 20k（决策 #6）
            maxTokensOverride = minOf(com.aicode.helper.agent.context.CompactionConstants.MAX_OUTPUT_TOKENS_FOR_SUMMARY, settings.maxTokens.coerceAtLeast(1024))
        )
        val request = buildRequest(body, settings)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: "无响应内容"
                throw IOException("摘要请求失败 (${response.code}): $err")
            }
            val responseBody = response.body?.string() ?: throw IOException("摘要响应为空")
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            return json.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("message")?.get("content")?.asString
                ?: ""
        }
    }

    private fun isCompleteJson(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        return try {
            gson.fromJson(t, JsonObject::class.java) != null
        } catch (e: Exception) {
            false
        }
    }

    private fun buildRequestBody(
        messages: List<AgentMessage>,
        tools: List<JsonObject>,
        settings: AiCodeSettings,
        model: String,
        stream: Boolean,
        maxTokensOverride: Int? = null
    ): okhttp3.RequestBody {
        val messagesArray = JsonArray()
        for (msg in messages) {
            val obj = JsonObject()
            obj.addProperty("role", msg.role.api)
            when (msg.role) {
                Role.ASSISTANT -> {
                    obj.addProperty("content", msg.content)
                    if (msg.toolCalls.isNotEmpty()) {
                        val arr = JsonArray()
                        for (tc in msg.toolCalls) {
                            val tcObj = JsonObject()
                            tcObj.addProperty("id", tc.id)
                            tcObj.addProperty("type", "function")
                            val fn = JsonObject()
                            fn.addProperty("name", tc.name)
                            fn.addProperty("arguments", tc.argumentsJson)
                            tcObj.add("function", fn)
                            arr.add(tcObj)
                        }
                        obj.add("tool_calls", arr)
                    }
                }
                Role.TOOL -> {
                    obj.addProperty("content", msg.content)
                    obj.addProperty("tool_call_id", msg.toolCallId ?: "")
                }
                else -> obj.addProperty("content", msg.content)
            }
            messagesArray.add(obj)
        }

        val body = JsonObject()
        body.addProperty("model", model)
        body.add("messages", messagesArray)
        body.addProperty("max_tokens", maxTokensOverride ?: settings.maxTokens)
        body.addProperty("temperature", settings.temperature)
        body.addProperty("stream", stream)
        if (tools.isNotEmpty()) {
            val toolsArray = JsonArray()
            tools.forEach { toolsArray.add(it) }
            body.add("tools", toolsArray)
            body.addProperty("tool_choice", "auto")
        }

        return gson.toJson(body).toRequestBody("application/json; charset=utf-8".toMediaType())
    }

    private fun buildRequest(requestBody: okhttp3.RequestBody, settings: AiCodeSettings): Request {
        val baseUrl = settings.apiUrl.trimEnd('/')
        return Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()
    }
}

