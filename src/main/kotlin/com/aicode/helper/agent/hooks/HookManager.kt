package com.aicode.helper.agent.hooks

import com.aicode.helper.agent.AgentState
import com.aicode.helper.agent.ToolCall
import com.aicode.helper.agent.tools.Tool
import com.aicode.helper.agent.tools.ToolExecutionContext
import com.intellij.openapi.diagnostic.Logger

/**
 * Hook 分发器（决策 #5）。
 *
 * 关键细节：PostSampling 等观察型 hook 的错误被**吞掉**（只记录、不 rethrow），
 * 监控/日志/评估逻辑出问题不应该把主任务也带崩。
 */
class HookManager {

    private val log = Logger.getInstance(HookManager::class.java)

    private val preToolUse = mutableListOf<PreToolUseHook>()
    private val postToolUse = mutableListOf<PostToolUseHook>()
    private val postSampling = mutableListOf<PostSamplingHook>()
    private val preCompact = mutableListOf<PreCompactHook>()
    private val postCompact = mutableListOf<PostCompactHook>()

    fun addPreToolUse(h: PreToolUseHook) = apply { preToolUse.add(h) }
    fun addPostToolUse(h: PostToolUseHook) = apply { postToolUse.add(h) }
    fun addPostSampling(h: PostSamplingHook) = apply { postSampling.add(h) }
    fun addPreCompact(h: PreCompactHook) = apply { preCompact.add(h) }
    fun addPostCompact(h: PostCompactHook) = apply { postCompact.add(h) }

    /** 取所有 PreToolUse hook 中最严格的决策；hook 抛错时按拒绝处理（安全默认）。 */
    suspend fun firePreToolUse(call: ToolCall, tool: Tool?, ctx: ToolExecutionContext): PermissionDecision {
        var decision: PermissionDecision = PermissionDecision.Allow
        for (h in preToolUse) {
            val d = try {
                h.onPreToolUse(call, tool, ctx)
            } catch (e: Exception) {
                log.warn("PreToolUse hook 异常，按拒绝处理: ${e.message}")
                PermissionDecision.Deny("权限检查异常: ${e.message}")
            }
            when (d) {
                is PermissionDecision.Deny -> return d
                is PermissionDecision.Ask -> decision = PermissionDecision.Ask
                is PermissionDecision.Allow -> { /* keep */ }
            }
        }
        return decision
    }

    suspend fun firePostToolUse(call: ToolCall, output: String, isError: Boolean, ctx: ToolExecutionContext) {
        for (h in postToolUse) {
            try {
                h.onPostToolUse(call, output, isError, ctx)
            } catch (e: Exception) {
                log.warn("PostToolUse hook 异常（已忽略）: ${e.message}")
            }
        }
    }

    /** 错误被吞掉，绝不 rethrow。 */
    suspend fun firePostSampling(state: AgentState, assistantText: String, toolCalls: List<ToolCall>) {
        for (h in postSampling) {
            try {
                h.onPostSampling(state, assistantText, toolCalls)
            } catch (e: Exception) {
                log.warn("PostSampling hook 异常（已忽略，不影响主循环）: ${e.message}")
            }
        }
    }

    suspend fun firePreCompact(layer: CompactionLayer, state: AgentState) {
        for (h in preCompact) {
            try {
                h.onPreCompact(layer, state)
            } catch (e: Exception) {
                log.warn("PreCompact hook 异常（已忽略）: ${e.message}")
            }
        }
    }

    suspend fun firePostCompact(layer: CompactionLayer, state: AgentState) {
        for (h in postCompact) {
            try {
                h.onPostCompact(layer, state)
            } catch (e: Exception) {
                log.warn("PostCompact hook 异常（已忽略）: ${e.message}")
            }
        }
    }
}

