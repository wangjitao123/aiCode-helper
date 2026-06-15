package com.aicode.helper.agent

import com.aicode.helper.agent.context.ContextManager
import com.aicode.helper.agent.event.AgentEvent
import com.aicode.helper.agent.hooks.HookManager
import com.aicode.helper.agent.tools.ToolExecutionContext
import com.aicode.helper.agent.tools.ToolRegistry
import com.aicode.helper.service.ContextOverflowException
import com.aicode.helper.service.LlmClient
import com.aicode.helper.settings.AiCodeSettings
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.cancellation.CancellationException

/**
 * 决策 #1 + #2：查询引擎。
 *
 * query() 不是“发请求拿响应”，而是一个冷 Flow（≈ AsyncGenerator），
 * 内部跑 while(true) 的 ReAct（Reason + Act）主循环，每轮：
 *   (a) 上下文预处理（五层压缩）
 *   (b) 流式调用模型
 *   (c) 解析响应、收集 tool_use 块
 *   (d) 流式并发执行工具
 *   (e) 把结果追加到消息历史
 *   (f) 没有工具调用 → 终止；有 → 进入下一轮
 *
 * [AgentState] 携带迭代间的可变状态，transition 字段作为防止死循环的断路器。
 */
class QueryEngine(
    private val project: Project,
    private val state: AgentState,
    private val registry: ToolRegistry,
    private val hooks: HookManager,
    private val context: ContextManager,
    private val llm: LlmClient
) {
    private val toolCtx = ToolExecutionContext(project)

    fun query(userInput: String): Flow<AgentEvent> = flow {
        state.ensureSystemPrompt(SYSTEM_PROMPT)
        state.addUser(userInput)
        state.resetPerQueryFlags()

        val settings = AiCodeSettings.getInstance()
        val maxIterations = settings.maxAgentIterations
        val parentJob = currentCoroutineContext()[Job]

        var iteration = 0
        var lastAssistantText = ""

        while (true) {
            iteration++
            if (iteration > maxIterations) {
                emit(AgentEvent.ErrorEvent("已达到最大迭代次数 $maxIterations，自动停止。"))
                break
            }
            emit(AgentEvent.IterationStart(iteration))

            // (a) 上下文预处理（L1/L2/L3）
            context.preprocess(state) { emit(it) }

            val toolsJson = if (settings.enableTools) registry.toolsJson() else emptyList()
            val assistantText = StringBuilder()
            val pendingCalls = ArrayList<ToolCall>()
            val executor = StreamingToolExecutor(parentJob, registry, hooks, toolCtx)
            // 流式过滤器：把“文本协议工具调用”的标记从展示中隐藏，气泡保持干净
            val streamFilter = ToolCallStreamFilter()
            var finishReason: String? = null

            // (b)(c)(d) 流式调用 + 收集 tool_use + 边流边执行
            try {
                llm.streamCompletion(state.messages, toolsJson).collect { ev ->
                    when (ev) {
                        is LlmClient.StreamEvent.TextDelta -> {
                            assistantText.append(ev.text)
                            val safe = streamFilter.feed(ev.text)
                            if (safe.isNotEmpty()) emit(AgentEvent.AssistantTextDelta(safe))
                        }
                        is LlmClient.StreamEvent.ToolCallReady -> {
                            val call = ToolCall(ev.id, ev.name, ev.argumentsJson)
                            pendingCalls.add(call)
                            emit(AgentEvent.ToolUseRequested(call.id, call.name, call.argumentsJson))
                            executor.submit(call) // 决策 #4：模型还在输出，工具已开始跑
                        }
                        is LlmClient.StreamEvent.Completed -> {
                            finishReason = ev.finishReason
                        }
                    }
                }
                // 冲刷过滤器里暂留的安全文本
                val tail = streamFilter.flush()
                if (tail.isNotEmpty()) emit(AgentEvent.AssistantTextDelta(tail))
            } catch (ce: CancellationException) {
                executor.cancelAll()
                throw ce
            } catch (e: ContextOverflowException) {
                // 决策 #2 + #3：413 恢复，用 transition 做断路器，避免重复恢复
                executor.cancelAll()
                if (!state.hasAttemptedReactiveCompact) {
                    state.hasAttemptedReactiveCompact = true
                    state.transition = Transition.ReactiveCompactRetry
                    emit(AgentEvent.Transitioned(Transition.ReactiveCompactRetry.reason))
                    context.reactiveCompact(state) { emit(it) }
                    continue
                } else {
                    emit(AgentEvent.ErrorEvent("上下文超出限制，压缩后仍失败：${e.message}"))
                    break
                }
            } catch (e: Exception) {
                executor.cancelAll()
                emit(AgentEvent.ErrorEvent("模型调用失败：${e.message}"))
                break
            }

            // 文本协议回退：很多模型（DeepSeek / 本地 Ollama / 各类开源模型）并不走
            // OpenAI 原生 function-calling，而是把工具调用以文本形式写在回答里
            // （如 <tool_call>{...}</tool_call>）。此时 pendingCalls 为空，会被误判为
            // “没有工具调用 → 结束”。这里解析助手文本中的工具调用，补齐 ReAct 主循环。
            var textProtocol = false
            if (pendingCalls.isEmpty() && settings.enableTools) {
                val textCalls = TextToolCallParser.parse(assistantText.toString(), registry)
                if (textCalls.isNotEmpty()) {
                    textProtocol = true
                    for (c in textCalls) {
                        pendingCalls.add(c)
                        emit(AgentEvent.ToolUseRequested(c.id, c.name, c.argumentsJson))
                        executor.submit(c) // 与原生路径一致，交给并发执行器
                    }
                }
            }

            // 追加助手消息到规范历史：
            // - 原生 function-calling：带 toolCalls，结果用 tool 角色回填；
            // - 文本协议：去掉工具调用标记，结果改用 user 文本回填
            //   （只走文本协议的端点未必支持 tool 角色消息，这样兼容性最好）。
            val assistantForHistory = if (textProtocol)
                TextToolCallParser.strip(assistantText.toString()).ifBlank { "（正在调用工具…）" }
            else assistantText.toString()
            state.addAssistant(assistantForHistory, if (textProtocol) emptyList() else pendingCalls)
            lastAssistantText = if (textProtocol) "" else assistantText.toString()

            // 决策 #5：PostSampling hook（错误被吞掉，不影响主循环）
            hooks.firePostSampling(state, assistantForHistory, pendingCalls)

            // (e) 执行工具并把结果写回历史
            val results = executor.complete()
            if (textProtocol) {
                val sb = StringBuilder("以下是工具调用结果，请据此继续推理；若信息足够请直接给出最终回答：\n\n")
                for (r in results) {
                    emit(AgentEvent.ToolResultEvent(r.toolCallId, r.toolName, r.output, r.isError))
                    sb.append("【工具 ").append(r.toolName).append(if (r.isError) " · 出错" else "").append("】\n")
                        .append(r.output).append("\n\n")
                }
                state.addUser(sb.toString())
            } else {
                for (r in results) {
                    state.addToolResult(r.toolCallId, r.output)
                    emit(AgentEvent.ToolResultEvent(r.toolCallId, r.toolName, r.output, r.isError))
                }
            }

            when {
                // 有工具调用 → 进入下一轮（continue）
                pendingCalls.isNotEmpty() -> {
                    state.transition = null
                    continue
                }
                // 模型因 max_output_tokens 截断 → 请求继续（断路器限制次数）
                finishReason == "length" &&
                        state.maxOutputTokensRecoveryCount < MAX_OUTPUT_TOKENS_RECOVERY_LIMIT -> {
                    state.maxOutputTokensRecoveryCount++
                    state.transition = Transition.MaxOutputTokensRecovery
                    emit(AgentEvent.Transitioned(Transition.MaxOutputTokensRecovery.reason))
                    state.addUser("请接着上文继续。")
                    continue
                }
                // (f) 没有工具调用 → 终止
                else -> {
                    emit(AgentEvent.QueryComplete(lastAssistantText))
                    break
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        /** max_output_tokens 恢复的最大尝试次数（断路器）。 */
        const val MAX_OUTPUT_TOKENS_RECOVERY_LIMIT = 3

        val SYSTEM_PROMPT = """
            你是嵌入在 IntelliJ IDEA 中的 AI 编程助手 Agent（基于类 Claude Code 的 harness）。
            你可以通过调用工具来探索当前项目并回答用户问题，而不是凭空猜测。

            可用工具：
            - project_structure：获取项目目录结构与统计（无参数）
            - list_directory：列出某个目录内容（参数 path，相对项目根目录）
            - read_file：读取某个文件内容（参数 path）
            - grep_search：在源码中搜索文本（参数 query，可选 extension）
            - write_file：写文件（需用户授权，可能被拒绝）

            工作方式：
            1. 先判断是否需要读取项目信息；需要就调用工具，可一次请求多个只读工具并行执行。
            2. 拿到工具结果后再决定下一步，必要时继续调用工具。
            3. 信息足够后，用简洁清晰的中文给出最终回答；涉及代码时使用 Markdown 代码块。
            4. 不要编造文件内容或路径；不确定就用工具去查。

            调用工具的格式：
            - 如果你的运行环境支持原生函数调用（function calling），直接发起原生工具调用即可。
            - 如果不支持，则当你需要调用工具时，请“只输出”如下标记块（不要在同一条消息里附加多余解释），
              系统会自动解析并执行，然后把结果回传给你：
              <tool_call>{"name": "工具名", "arguments": {参数对象}}</tool_call>
              示例：
              <tool_call>{"name": "project_structure", "arguments": {}}</tool_call>
              <tool_call>{"name": "read_file", "arguments": {"path": "build.gradle.kts"}}</tool_call>
              可一次输出多个 <tool_call> 块表示并行调用。收到“工具调用结果”后再继续推理或作答。
        """.trimIndent()
    }
}

