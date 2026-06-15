package com.aicode.helper.agent.hooks

import com.aicode.helper.agent.AgentState
import com.aicode.helper.agent.ToolCall
import com.aicode.helper.agent.tools.Tool
import com.aicode.helper.agent.tools.ToolExecutionContext

/**
 * 决策 #5：Hook 架构（generator-evaluator 分离）。
 *
 * 通过 hook 点，可以在不改动核心循环的前提下观察/调整 agent 行为：
 * PreToolUse、PostToolUse、PostSampling、PreCompact、PostCompact。
 */

/** PreToolUse 的返回：放行 / 询问 / 拒绝。 */
sealed class PermissionDecision {
    data object Allow : PermissionDecision()
    data object Ask : PermissionDecision()
    data class Deny(val reason: String) : PermissionDecision()
}

/** 压缩层级（供 Pre/PostCompact hook 使用）。 */
enum class CompactionLayer { MICROCOMPACT, SNIP, AUTOCOMPACT, REACTIVE_COMPACT, CONTEXT_COLLAPSE }

interface PreToolUseHook {
    suspend fun onPreToolUse(call: ToolCall, tool: Tool?, ctx: ToolExecutionContext): PermissionDecision
}

interface PostToolUseHook {
    suspend fun onPostToolUse(call: ToolCall, output: String, isError: Boolean, ctx: ToolExecutionContext)
}

interface PostSamplingHook {
    /** 每次模型生成完成后触发，用于观察主循环但**不应**干扰它。 */
    suspend fun onPostSampling(state: AgentState, assistantText: String, toolCalls: List<ToolCall>)
}

interface PreCompactHook {
    suspend fun onPreCompact(layer: CompactionLayer, state: AgentState)
}

interface PostCompactHook {
    suspend fun onPostCompact(layer: CompactionLayer, state: AgentState)
}

