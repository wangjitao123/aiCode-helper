package com.aicode.helper.agent

import com.aicode.helper.agent.context.ContextManager
import com.aicode.helper.agent.hooks.HookManager
import com.aicode.helper.agent.hooks.PermissionHook
import com.aicode.helper.agent.tools.GrepSearchTool
import com.aicode.helper.agent.tools.ListDirectoryTool
import com.aicode.helper.agent.tools.ProjectStructureTool
import com.aicode.helper.agent.tools.ReadFileTool
import com.aicode.helper.agent.tools.ToolRegistry
import com.aicode.helper.agent.tools.WriteFileTool
import com.aicode.helper.service.LlmClient
import com.intellij.openapi.project.Project

/**
 * 一个 agent 会话：持有跨多轮对话共享的 [AgentState]，
 * 并负责组装工具注册中心、hook、上下文管理器与 LLM 客户端。
 */
class AgentSession(private val project: Project) {

    val state = AgentState()

    private val registry: ToolRegistry = ToolRegistry()
        .register(ProjectStructureTool())
        .register(ListDirectoryTool())
        .register(ReadFileTool())
        .register(GrepSearchTool())
        .register(WriteFileTool())

    private val hooks: HookManager = HookManager()
        .addPreToolUse(PermissionHook())

    private val llm = LlmClient()
    private val context = ContextManager(llm, hooks)

    /** 为一次用户输入构建查询引擎（与会话共享 state，从而支持多轮上下文）。 */
    fun newQuery(): QueryEngine =
        QueryEngine(project, state, registry, hooks, context, llm)

    fun reset() {
        state.reset()
    }
}

