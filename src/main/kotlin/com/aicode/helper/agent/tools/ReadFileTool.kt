package com.aicode.helper.agent.tools

import com.aicode.helper.agent.tools.ToolSupport.int
import com.aicode.helper.agent.tools.ToolSupport.str
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction

/** 读取单个文件内容（只读、并发安全）。 */
class ReadFileTool : Tool {
    override val name = "read_file"
    override val description = "读取项目中某个文件的文本内容。参数 path 为相对项目根目录的路径。"
    override val permission = ToolPermission.READ_ONLY

    override fun parameters(): JsonObject = ToolRegistry.objectSchema(
        Triple("path", "string", "要读取的文件路径（相对项目根目录）"),
        Triple("max_bytes", "integer", "最多读取的字节数，默认 8000"),
        required = listOf("path")
    )

    override fun isConcurrencySafe(argumentsJson: String): Boolean = true

    override suspend fun execute(argumentsJson: String, ctx: ToolExecutionContext): String {
        val args = ToolSupport.parseArgs(argumentsJson)
        val path = args.str("path") ?: return "错误：缺少参数 path"
        val maxBytes = args.int("max_bytes") ?: 8000

        return ReadAction.compute<String, Exception> {
            val file = ToolSupport.resolve(ctx.project, path)
                ?: return@compute "错误：找不到文件 $path"
            if (file.isDirectory) return@compute "错误：$path 是目录，请使用 list_directory"
            try {
                val bytes = file.contentsToByteArray()
                val text = String(bytes, Charsets.UTF_8)
                if (text.length > maxBytes) {
                    text.take(maxBytes) + "\n... (文件过长，已截断，共 ${text.length} 字符)"
                } else text
            } catch (e: Exception) {
                "错误：读取失败 ${e.message}"
            }
        }
    }
}

