package com.aicode.helper.utils

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile

object ProjectStructureUtil {

    fun analyzeProjectStructure(project: Project): String {
        val sb = StringBuilder()
        sb.appendLine("# 项目结构分析")
        sb.appendLine()
        sb.appendLine("**项目名称:** ${project.name}")
        sb.appendLine()

        val roots = ProjectRootManager.getInstance(project).contentRoots
        if (roots.isEmpty()) {
            sb.appendLine("未找到项目内容根目录。")
            return sb.toString()
        }

        sb.appendLine("## 目录结构")
        sb.appendLine()
        sb.appendLine("```")
        roots.forEach { root ->
            appendDirectory(sb, root, 0, maxDepth = 4, maxFiles = 200)
        }
        sb.appendLine("```")

        val stats = collectStats(roots)
        sb.appendLine()
        sb.appendLine("## 统计信息")
        sb.appendLine()
        sb.appendLine("- 总目录数: ${stats.dirCount}")
        sb.appendLine("- 总文件数: ${stats.fileCount}")
        sb.appendLine()
        sb.appendLine("## 文件类型分布")
        sb.appendLine()
        stats.extensionCount
            .entries
            .sortedByDescending { it.value }
            .take(15)
            .forEach { (ext, count) ->
                val label = if (ext.isEmpty()) "(无扩展名)" else ".$ext"
                sb.appendLine("- $label: $count 个文件")
            }

        return sb.toString()
    }

    private fun appendDirectory(
        sb: StringBuilder,
        dir: VirtualFile,
        depth: Int,
        maxDepth: Int,
        maxFiles: Int,
        counter: Counter = Counter()
    ) {
        if (depth > maxDepth || counter.total >= maxFiles) return

        val indent = "  ".repeat(depth)
        if (depth == 0) {
            sb.appendLine("${dir.name}/")
        }

        val children = dir.children?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return

        for (child in children) {
            if (counter.total >= maxFiles) {
                sb.appendLine("$indent  ... (更多文件已省略)")
                break
            }
            if (shouldIgnore(child)) continue

            counter.total++
            if (child.isDirectory) {
                sb.appendLine("$indent  ${child.name}/")
                appendDirectory(sb, child, depth + 1, maxDepth, maxFiles, counter)
            } else {
                sb.appendLine("$indent  ${child.name}")
            }
        }
    }

    private fun shouldIgnore(file: VirtualFile): Boolean {
        val name = file.name
        val ignoredDirs = setOf(
            ".git", ".idea", ".gradle", "build", "out", "target",
            "node_modules", "__pycache__", ".venv", "venv", ".DS_Store"
        )
        return name in ignoredDirs || name.startsWith(".")
    }

    private data class Stats(
        val dirCount: Int,
        val fileCount: Int,
        val extensionCount: Map<String, Int>
    )

    private class Counter(var total: Int = 0)

    private fun collectStats(roots: Array<VirtualFile>): Stats {
        var dirCount = 0
        var fileCount = 0
        val extensionCount = mutableMapOf<String, Int>()

        fun traverse(file: VirtualFile) {
            if (shouldIgnore(file)) return
            if (file.isDirectory) {
                dirCount++
                file.children?.forEach { traverse(it) }
            } else {
                fileCount++
                val ext = file.extension ?: ""
                extensionCount[ext] = (extensionCount[ext] ?: 0) + 1
            }
        }

        roots.forEach { traverse(it) }
        return Stats(dirCount, fileCount, extensionCount)
    }
}
