package com.aicode.helper.agent

/**
 * 决策 #2：Agentic Loop 的可变状态。
 *
 * 对应源码里的 State 类型，记录每轮迭代间需要携带的可变状态。
 * [transition] 字段记录“为什么进入下一轮”，它不只是调试信息——
 * 而是防止死循环的**断路器**：如果上一轮已经因某种原因重试过，
 * 就不再重复同一种恢复策略，而是进入下一个策略或直接终止。
 */
sealed class Transition(val reason: String) {
    /** 因 413 / 上下文超限，主动压缩后重试。 */
    data object ReactiveCompactRetry : Transition("reactive_compact_retry")

    /** 先消耗已暂存的折叠段落再重试。 */
    data object CollapseDrainRetry : Transition("collapse_drain_retry")

    /** 模型因 max_output_tokens 截断（finish_reason=length），请求其继续。 */
    data object MaxOutputTokensRecovery : Transition("max_output_tokens_recovery")
}

/**
 * Agent 会话的可变状态。一个 ChatPanel 会话共享一个 AgentState，从而支持多轮对话。
 */
class AgentState {

    /** 规范的、包含工具调用与工具结果的完整历史（送给模型的就是它）。 */
    val messages: MutableList<AgentMessage> = mutableListOf()

    /** max_output_tokens 恢复已尝试的次数（断路器计数）。 */
    var maxOutputTokensRecoveryCount: Int = 0

    /** 本轮 query 是否已经尝试过 reactiveCompact（断路器）。 */
    var hasAttemptedReactiveCompact: Boolean = false

    /** 上一轮为何 continue。null 表示正常推进。 */
    var transition: Transition? = null

    fun ensureSystemPrompt(prompt: String) {
        if (messages.none { it.role == Role.SYSTEM }) {
            messages.add(0, AgentMessage(Role.SYSTEM, prompt))
        }
    }

    fun addUser(text: String) {
        messages.add(AgentMessage(Role.USER, text))
    }

    fun addAssistant(text: String, toolCalls: List<ToolCall>) {
        messages.add(AgentMessage(Role.ASSISTANT, text, toolCalls = toolCalls))
    }

    fun addToolResult(toolCallId: String, output: String) {
        messages.add(AgentMessage(Role.TOOL, output, toolCallId = toolCallId))
    }

    fun addSystemNote(text: String) {
        messages.add(AgentMessage(Role.SYSTEM, text))
    }

    /** 新一轮 query 开始时重置 per-query 断路器。 */
    fun resetPerQueryFlags() {
        hasAttemptedReactiveCompact = false
        maxOutputTokensRecoveryCount = 0
        transition = null
    }

    fun reset() {
        messages.clear()
        resetPerQueryFlags()
    }
}

