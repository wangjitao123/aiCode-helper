package com.aicode.helper.agent

import com.aicode.helper.service.AiApiService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * 项目分析 Agent - 模拟逐步读取文件的过程，类似 AI Agent 的工作方式
 */
class ProjectAnalysisAgent(
    private val project: Project,
    private val onStep: (String) -> Unit,       // 每一步的状态回调（流式显示在聊天框）
    private val onComplete: (String) -> Unit,    // 完成后的回调
    private val onError: (String) -> Unit
) {

    private val ignoredDirs = setOf(
        ".git", ".idea", ".gradle", "build", "out", "target",
        "node_modules", "__pycache__", ".venv", "venv", ".DS_Store"
    )

    private val keyFileNames = setOf(
        "build.gradle.kts", "build.gradle", "pom.xml", "package.json",
        "settings.gradle.kts", "settings.gradle", "gradle.properties",
        "Cargo.toml", "go.mod", "requirements.txt", "pyproject.toml",
        "Makefile", "Dockerfile", "docker-compose.yml", "docker-compose.yaml",
        ".env.example", "README.md", "readme.md"
    )

    private val keyConfigExtensions = setOf("xml", "yml", "yaml", "properties", "toml", "json")

    fun analyze(userQuestion: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                doAnalyze(userQuestion)
            } catch (e: Exception) {
                onError("分析失败: ${e.message}")
            }
        }
    }

    private fun doAnalyze(userQuestion: String) {
        onStep("🔍 开始分析项目: ${project.name}\n\n")

        // Step 1: 扫描目录结构
        onStep("📂 **Step 1** - 扫描项目目录结构...\n")
        val roots = ReadAction.compute<Array<VirtualFile>, Exception> {
            ProjectRootManager.getInstance(project).contentRoots
        }
        if (roots.isEmpty()) {
            onError("未找到项目内容根目录。")
            return
        }

        val treeBuilder = StringBuilder()
        val allFiles = mutableListOf<VirtualFile>()
        ReadAction.run<Exception> {
            roots.forEach { root ->
                buildTree(root, 0, treeBuilder, allFiles)
            }
        }
        val treeStr = treeBuilder.toString()
        onStep("```\n$treeStr```\n\n")
        Thread.sleep(300)

        // Step 2: 统计信息
        onStep("📊 **Step 2** - 统计文件信息...\n")
        val stats = collectStats(allFiles)
        onStep("  - 总目录数: ${stats.dirCount}\n")
        onStep("  - 总文件数: ${stats.fileCount}\n")
        onStep("  - 主要文件类型: ${stats.topExtensions}\n\n")
        Thread.sleep(300)

        // Step 3: 逐个读取关键文件
        onStep("📖 **Step 3** - 读取关键配置文件...\n\n")
        val keyFiles = findKeyFiles(allFiles)
        val fileContents = StringBuilder()

        for (file in keyFiles) {
            val relativePath = getRelativePath(roots, file)
            onStep("  ➜ 正在读取: `$relativePath` ...\n")
            Thread.sleep(200)

            val content = ReadAction.compute<String?, Exception> {
                try {
                    val text = String(file.contentsToByteArray(), Charsets.UTF_8)
                    if (text.length > 3000) text.take(3000) + "\n... (文件过长，已截断)" else text
                } catch (e: Exception) {
                    null
                }
            }

            if (content != null) {
                fileContents.appendLine("--- $relativePath ---")
                fileContents.appendLine(content)
                fileContents.appendLine()
                onStep("    ✅ 已读取 (${content.lines().size} 行)\n")
            } else {
                onStep("    ⚠️ 无法读取\n")
            }
        }
        onStep("\n")
        Thread.sleep(300)

        // Step 4: 读取源码入口文件
        onStep("🔎 **Step 4** - 读取核心源码文件...\n\n")
        val sourceFiles = findSourceFiles(allFiles)
        for (file in sourceFiles) {
            val relativePath = getRelativePath(roots, file)
            onStep("  ➜ 正在读取: `$relativePath` ...\n")
            Thread.sleep(150)

            val content = ReadAction.compute<String?, Exception> {
                try {
                    val text = String(file.contentsToByteArray(), Charsets.UTF_8)
                    if (text.length > 2000) text.take(2000) + "\n... (已截断)" else text
                } catch (e: Exception) {
                    null
                }
            }

            if (content != null) {
                fileContents.appendLine("--- $relativePath ---")
                fileContents.appendLine(content)
                fileContents.appendLine()
                onStep("    ✅ 已读取 (${content.lines().size} 行)\n")
            }
        }
        onStep("\n")
        Thread.sleep(300)

        // Step 5: 调用 AI 分析
        onStep("🤖 **Step 5** - 正在调用 AI 进行深度分析...\n\n")

        val prompt = buildString {
            appendLine("以下是一个项目的信息，请根据这些信息回答用户的问题。")
            appendLine()
            appendLine("## 项目目录结构")
            appendLine("```")
            append(treeStr)
            appendLine("```")
            appendLine()
            appendLine("## 统计")
            appendLine("目录数: ${stats.dirCount}, 文件数: ${stats.fileCount}")
            appendLine()
            appendLine("## 关键文件内容")
            appendLine(fileContents.toString())
            appendLine()
            appendLine("## 用户问题")
            appendLine(userQuestion)
        }

        val apiService = AiApiService()
        val messages = listOf(
            AiApiService.Message(
                "system",
                "你是一个专业的软件架构分析助手。你已经逐步读取了项目的目录结构和关键文件内容。" +
                        "请根据这些信息详细回答用户的问题。用中文回复，格式清晰易读。"
            ),
            AiApiService.Message("user", prompt)
        )

        val responseBuffer = StringBuilder()
        apiService.chatStream(
            messages = messages,
            onChunk = { chunk ->
                responseBuffer.append(chunk)
                onStep(chunk)
            },
            onComplete = {
                onStep("\n")
                onComplete(responseBuffer.toString())
            },
            onError = { error ->
                onError("AI 调用失败: $error")
            }
        )
    }

    private fun buildTree(
        dir: VirtualFile, depth: Int, sb: StringBuilder,
        allFiles: MutableList<VirtualFile>, maxDepth: Int = 5, counter: IntArray = intArrayOf(0)
    ) {
        if (depth > maxDepth || counter[0] > 300) return

        val indent = "  ".repeat(depth)
        if (depth == 0) sb.appendLine("${dir.name}/")

        val children = dir.children?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return
        for (child in children) {
            if (counter[0] > 300) {
                sb.appendLine("$indent  ... (更多文件已省略)")
                break
            }
            if (shouldIgnore(child)) continue
            counter[0]++
            allFiles.add(child)
            if (child.isDirectory) {
                sb.appendLine("$indent  ${child.name}/")
                buildTree(child, depth + 1, sb, allFiles, maxDepth, counter)
            } else {
                sb.appendLine("$indent  ${child.name}")
            }
        }
    }

    private fun shouldIgnore(file: VirtualFile): Boolean {
        return file.name in ignoredDirs || file.name.startsWith(".")
    }

    private fun findKeyFiles(allFiles: List<VirtualFile>): List<VirtualFile> {
        return allFiles
            .filter { !it.isDirectory && it.name in keyFileNames }
            .take(10)
    }

    private fun findSourceFiles(allFiles: List<VirtualFile>): List<VirtualFile> {
        val sourceExts = setOf("kt", "java", "py", "ts", "js", "go", "rs", "cpp", "c", "swift")
        return allFiles
            .filter { !it.isDirectory && it.extension in sourceExts }
            .take(8)
    }

    private fun getRelativePath(roots: Array<VirtualFile>, file: VirtualFile): String {
        for (root in roots) {
            val rootPath = root.path
            if (file.path.startsWith(rootPath)) {
                return file.path.removePrefix(rootPath).trimStart('/')
            }
        }
        return file.name
    }

    private data class FileStats(
        val dirCount: Int,
        val fileCount: Int,
        val topExtensions: String
    )

    private fun collectStats(allFiles: List<VirtualFile>): FileStats {
        var dirCount = 0
        var fileCount = 0
        val extMap = mutableMapOf<String, Int>()
        for (f in allFiles) {
            if (f.isDirectory) dirCount++ else {
                fileCount++
                val ext = f.extension ?: ""
                extMap[ext] = (extMap[ext] ?: 0) + 1
            }
        }
        val top = extMap.entries.sortedByDescending { it.value }.take(5)
            .joinToString(", ") { ".${it.key}(${it.value})" }
        return FileStats(dirCount, fileCount, top)
    }
}

