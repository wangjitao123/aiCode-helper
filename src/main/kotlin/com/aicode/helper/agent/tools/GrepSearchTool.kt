package com.aicode.helper.agent.tools

import com.aicode.helper.agent.tools.ToolSupport.int
import com.aicode.helper.agent.tools.ToolSupport.str
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile

/** 在项目文件内容中搜索文本（只读、并发安全）。 */
class GrepSearchTool : Tool {
    override val name = "grep_search"
    override val description = "在项目源码中按子串搜索，返回匹配的文件、行号与行内容。参数 query 为搜索文本，可选 extension 限定扩展名。"
    override val permission = ToolPermission.READ_ONLY

    private val textExtensions = setOf(
        "kt", "java", "py", "ts", "tsx", "js", "jsx", "go", "rs", "cpp", "c", "h", "cs",
        "swift", "rb", "php", "scala", "xml", "yml", "yaml", "json", "properties", "toml",
        "gradle", "kts", "md", "txt", "html", "css", "sql"
    )

    override fun parameters(): JsonObject = ToolRegistry.objectSchema(
        Triple("query", "string", "要搜索的文本（子串匹配，大小写不敏感）"),
        Triple("extension", "string", "可选：仅搜索该扩展名的文件，如 kt"),
        Triple("max_results", "integer", "最多返回的匹配数，默认 40"),
        required = listOf("query")
    )

    override fun isConcurrencySafe(argumentsJson: String): Boolean = true

    override suspend fun execute(argumentsJson: String, ctx: ToolExecutionContext): String {
        val args = ToolSupport.parseArgs(argumentsJson)
        val query = args.str("query")?.takeIf { it.isNotBlank() } ?: return "错误：缺少参数 query"
        val ext = args.str("extension")?.removePrefix(".")
        val maxResults = args.int("max_results") ?: 40

        return ReadAction.compute<String, Exception> {
            val base = ToolSupport.baseDir(ctx.project) ?: return@compute "错误：未找到项目根目录"
            val matches = mutableListOf<String>()
            var scanned = 0
            val lowerQuery = query.lowercase()

            fun walk(dir: VirtualFile) {
                if (matches.size >= maxResults || scanned > 4000) return
                val children = dir.children ?: return
                for (child in children) {
                    if (matches.size >= maxResults || scanned > 4000) return
                    if (ToolSupport.shouldIgnore(child)) continue
                    if (child.isDirectory) {
                        walk(child)
                    } else {
                        val fileExt = child.extension ?: ""
                        if (ext != null && fileExt != ext) continue
                        if (fileExt !in textExtensions) continue
                        if (child.length > 512 * 1024) continue
                        scanned++
                        try {
                            val text = String(child.contentsToByteArray(), Charsets.UTF_8)
                            text.lineSequence().forEachIndexed { i, lineText ->
                                if (matches.size >= maxResults) return@forEachIndexed
                                if (lineText.lowercase().contains(lowerQuery)) {
                                    val rel = ToolSupport.relativePath(ctx.project, child)
                                    matches.add("$rel:${i + 1}: ${lineText.trim().take(200)}")
                                }
                            }
                        } catch (e: Exception) {
                            // 跳过无法读取的文件
                        }
                    }
                }
            }

            walk(base)
            if (matches.isEmpty()) "未找到匹配 “$query”（已扫描 $scanned 个文件）"
            else "找到 ${matches.size} 处匹配：\n" + matches.joinToString("\n")
        }
    }
}

