package com.aicode.helper.agent.hooks

import com.aicode.helper.agent.ToolCall
import com.aicode.helper.agent.tools.Tool
import com.aicode.helper.agent.tools.ToolExecutionContext
import com.aicode.helper.agent.tools.ToolPermission
import com.aicode.helper.settings.AiCodeSettings

/**
 * 决策 #5 的权限策略，实现为一个 PreToolUse hook：
 * - 只读工具：自动放行（前提是设置里开启了自动放行只读工具）
 * - 写/执行工具：默认拒绝，除非设置里显式开启“允许写工具”
 */
class PermissionHook : PreToolUseHook {

    override suspend fun onPreToolUse(
        call: ToolCall,
        tool: Tool?,
        ctx: ToolExecutionContext
    ): PermissionDecision {
        if (tool == null) return PermissionDecision.Deny("未知工具: ${call.name}")
        val settings = AiCodeSettings.getInstance()
        return when (tool.permission) {
            ToolPermission.READ_ONLY ->
                if (settings.autoApproveReadOnlyTools) PermissionDecision.Allow else PermissionDecision.Ask
            ToolPermission.WRITE, ToolPermission.EXEC ->
                if (settings.allowWriteTools) PermissionDecision.Allow
                else PermissionDecision.Deny("写/执行类工具默认禁用，请在设置中开启「允许写工具」")
        }
    }
}

