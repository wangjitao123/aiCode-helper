package com.aicode.helper.agent.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/** 工具实现的公共辅助函数。 */
object ToolSupport {

    private val gson = Gson()

    val IGNORED_DIRS = setOf(
        ".git", ".idea", ".gradle", "build", "out", "target",
        "node_modules", "__pycache__", ".venv", "venv", "dist", ".DS_Store"
    )

    fun parseArgs(argumentsJson: String): JsonObject = try {
        gson.fromJson(argumentsJson.ifBlank { "{}" }, JsonObject::class.java) ?: JsonObject()
    } catch (e: Exception) {
        JsonObject()
    }

    fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString

    fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { !it.isJsonNull }?.asInt

    fun baseDir(project: Project): VirtualFile? {
        ProjectRootManager.getInstance(project).contentRoots.firstOrNull()?.let { return it }
        val basePath = project.basePath ?: return null
        return LocalFileSystem.getInstance().findFileByPath(basePath)
    }

    /** 把一个相对/绝对路径解析为 VirtualFile（限制在项目内）。 */
    fun resolve(project: Project, path: String): VirtualFile? {
        val base = baseDir(project) ?: return null
        val normalized = path.trim().removePrefix("./")
        if (normalized.isEmpty() || normalized == ".") return base
        // 先按相对项目根解析
        base.findFileByRelativePath(normalized)?.let { return it }
        // 再尝试绝对路径
        return LocalFileSystem.getInstance().findFileByPath(normalized)
    }

    fun relativePath(project: Project, file: VirtualFile): String {
        val base = baseDir(project) ?: return file.path
        return if (file.path.startsWith(base.path)) {
            file.path.removePrefix(base.path).trimStart('/').ifEmpty { file.name }
        } else file.path
    }

    fun shouldIgnore(file: VirtualFile): Boolean =
        file.name in IGNORED_DIRS || (file.isDirectory && file.name.startsWith("."))
}

