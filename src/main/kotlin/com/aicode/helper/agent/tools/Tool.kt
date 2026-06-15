package com.aicode.helper.agent.tools

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project

/** 工具权限级别（决策 #5 的权限系统：逐个工具的使用权限控制）。 */
enum class ToolPermission {
    /** 只读：天然安全，可自动放行。 */
    READ_ONLY,

    /** 写操作：会修改工作区，需要审批。 */
    WRITE,

    /** 执行外部命令：风险最高，需要审批。 */
    EXEC
}

/** 工具执行上下文。 */
class ToolExecutionContext(val project: Project)

/**
 * 工具接口。每个工具自带：
 * - 权限级别 [permission]
 * - 并发安全声明 [isConcurrencySafe]（决策 #4：调度并发执行时使用）
 * - JSON Schema 参数定义 [parameters]（用于 OpenAI function-calling）
 */
interface Tool {
    val name: String
    val description: String
    val permission: ToolPermission

    /** OpenAI function 的 parameters JSON Schema。 */
    fun parameters(): JsonObject

    /**
     * 决策 #4：该工具在给定输入下是否可与其它并发安全工具并行执行。
     * 只读工具通常返回 true；写/执行类按输入判断或直接 false。
     */
    fun isConcurrencySafe(argumentsJson: String): Boolean

    /** 执行工具，返回给模型看的文本结果。 */
    suspend fun execute(argumentsJson: String, ctx: ToolExecutionContext): String
}

