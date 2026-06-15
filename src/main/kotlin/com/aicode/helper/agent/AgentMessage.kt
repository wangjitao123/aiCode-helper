package com.aicode.helper.agent

/**
 * 对话消息模型。比原来的 ChatHistoryService.ChatMessage 更丰富，
 * 因为 agent 的历史里要携带 tool_use（助手发起的工具调用）和 tool 结果。
 */
enum class Role(val api: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool")
}

/** 一次工具调用（OpenAI tool_calls 中的一项）。 */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/**
 * 单条消息。
 * - assistant 消息可携带 [toolCalls]
 * - tool 消息携带 [toolCallId]，表示它是对某次工具调用的结果
 */
data class AgentMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null
)

