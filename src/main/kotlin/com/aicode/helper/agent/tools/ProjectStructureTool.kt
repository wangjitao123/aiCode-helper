package com.aicode.helper.agent.tools

import com.aicode.helper.utils.ProjectStructureUtil
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction

/** 分析项目整体目录结构与文件类型分布（只读、并发安全）。复用既有的 ProjectStructureUtil。 */
class ProjectStructureTool : Tool {
    override val name = "project_structure"
    override val description = "获取当前项目的目录结构概览、文件统计与类型分布。无需参数。"
    override val permission = ToolPermission.READ_ONLY

    override fun parameters(): JsonObject = ToolRegistry.objectSchema()

    override fun isConcurrencySafe(argumentsJson: String): Boolean = true

    override suspend fun execute(argumentsJson: String, ctx: ToolExecutionContext): String {
        return ReadAction.compute<String, Exception> {
            ProjectStructureUtil.analyzeProjectStructure(ctx.project)
        }
    }
}

