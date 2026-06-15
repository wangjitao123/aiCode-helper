package com.aicode.helper.agent.event

/**
 * 决策 #1：AsyncGenerator 核心。
 *
 * Claude Code 的 query() 不是“发请求、拿响应”，而是一个 generator，
 * 通过 yield 流出每一个事件（文本 token、工具调用、工具结果、错误、生命周期）。
 *
 * 在 Kotlin 中我们用冷 [kotlinx.coroutines.flow.Flow] 来对应 AsyncGenerator：
 * - flow{} 体内的 emit() 等价于 yield
 * - 下游 collect 的取消等价于 generator.return()（中途终止整个生成器链）
 *
 * 这里定义所有被 yield 出去的事件类型。
 */
sealed interface AgentEvent {

    /** 模型流式输出的一段助手文本增量。 */
    data class AssistantTextDelta(val text: String) : AgentEvent

    /** 模型请求调用某个工具（tool_use 块解析完成，参数已就绪）。 */
    data class ToolUseRequested(
        val toolCallId: String,
        val toolName: String,
        val argumentsJson: String
    ) : AgentEvent

    /** 某个工具开始执行。 */
    data class ToolExecutionStarted(
        val toolCallId: String,
        val toolName: String
    ) : AgentEvent

    /** 某个工具执行完成，产出结果。 */
    data class ToolResultEvent(
        val toolCallId: String,
        val toolName: String,
        val output: String,
        val isError: Boolean
    ) : AgentEvent

    /** 上下文压缩事件（五层压缩策略中的某一层被触发）。 */
    data class Compaction(
        val layer: String,
        val detail: String
    ) : AgentEvent

    /** 主循环进入新一轮迭代。 */
    data class IterationStart(val iteration: Int) : AgentEvent

    /**
     * 状态机发生了一次“为什么进入下一轮”的转移（断路器）。
     * 对应 State.transition：reactive_compact_retry / max_output_tokens_recovery 等。
     */
    data class Transitioned(val reason: String) : AgentEvent

    /** 整个 query 结束（没有更多工具调用，主循环 return）。 */
    data class QueryComplete(val finalText: String) : AgentEvent

    /** 不可恢复的错误。 */
    data class ErrorEvent(val message: String) : AgentEvent
}

