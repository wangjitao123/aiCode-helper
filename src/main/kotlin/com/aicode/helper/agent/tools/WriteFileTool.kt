package com.aicode.helper.agent.tools

import com.aicode.helper.agent.tools.ToolSupport.str
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager

/**
 * 写文件工具（WRITE 权限、非并发安全）。
 *
 * 用来演示决策 #4 的并发调度（写工具必须独占执行）与决策 #5 的权限系统
 * （默认被 PermissionHook 拒绝，需在设置中显式开启“允许写工具”）。
 */
class WriteFileTool : Tool {
    override val name = "write_file"
    override val description = "覆盖写入项目中某个已存在文件的全部内容。参数 path 与 content。这是写操作，需要用户授权。"
    override val permission = ToolPermission.WRITE

    override fun parameters(): JsonObject = ToolRegistry.objectSchema(
        Triple("path", "string", "目标文件路径（相对项目根目录，必须已存在）"),
        Triple("content", "string", "要写入的完整新内容"),
        required = listOf("path", "content")
    )

    // 写操作必须独占执行
    override fun isConcurrencySafe(argumentsJson: String): Boolean = false

    override suspend fun execute(argumentsJson: String, ctx: ToolExecutionContext): String {
        val args = ToolSupport.parseArgs(argumentsJson)
        val path = args.str("path") ?: return "错误：缺少参数 path"
        val content = args.str("content") ?: return "错误：缺少参数 content"

        val result = StringBuilder()
        ApplicationManager.getApplication().invokeAndWait {
            val file = ToolSupport.resolve(ctx.project, path)
            if (file == null || file.isDirectory) {
                result.append("错误：找不到可写文件 $path")
                return@invokeAndWait
            }
            WriteCommandAction.runWriteCommandAction(ctx.project) {
                val document = FileDocumentManager.getInstance().getDocument(file)
                if (document == null) {
                    result.append("错误：无法获取文档 $path")
                    return@runWriteCommandAction
                }
                document.setText(content)
                result.append("已写入 $path（${content.length} 字符）")
            }
        }
        return result.toString()
    }
}

