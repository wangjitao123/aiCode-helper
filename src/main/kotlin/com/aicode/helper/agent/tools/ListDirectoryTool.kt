package com.aicode.helper.agent.tools

import com.aicode.helper.agent.tools.ToolSupport.str
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction

/** 列出目录内容（只读、并发安全）。 */
class ListDirectoryTool : Tool {
    override val name = "list_directory"
    override val description = "列出项目中某个目录下的文件与子目录。参数 path 为相对项目根目录的路径，默认根目录。"
    override val permission = ToolPermission.READ_ONLY

    override fun parameters(): JsonObject = ToolRegistry.objectSchema(
        Triple("path", "string", "目录路径（相对项目根目录），默认为项目根目录")
    )

    override fun isConcurrencySafe(argumentsJson: String): Boolean = true

    override suspend fun execute(argumentsJson: String, ctx: ToolExecutionContext): String {
        val args = ToolSupport.parseArgs(argumentsJson)
        val path = args.str("path") ?: "."

        return ReadAction.compute<String, Exception> {
            val dir = ToolSupport.resolve(ctx.project, path)
                ?: return@compute "错误：找不到目录 $path"
            if (!dir.isDirectory) return@compute "错误：$path 不是目录"

            val children = dir.children
                ?.filterNot { ToolSupport.shouldIgnore(it) }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                ?: emptyList()

            if (children.isEmpty()) return@compute "（空目录）"
            buildString {
                appendLine("目录: ${ToolSupport.relativePath(ctx.project, dir)}")
                for (child in children.take(200)) {
                    if (child.isDirectory) appendLine("  [DIR]  ${child.name}/")
                    else appendLine("  [FILE] ${child.name} (${child.length} bytes)")
                }
                if (children.size > 200) appendLine("  ... 共 ${children.size} 项，已截断")
            }
        }
    }
}

