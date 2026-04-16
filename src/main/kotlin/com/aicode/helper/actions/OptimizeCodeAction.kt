package com.aicode.helper.actions

import com.aicode.helper.service.AiApiService
import com.aicode.helper.settings.AiCodeSettings
import com.aicode.helper.toolwindow.AiChatToolWindowFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

class OptimizeCodeAction : AnAction("AI 优化代码") {

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        e.presentation.isEnabledAndVisible = hasSelection
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText

        if (selectedText.isNullOrBlank()) {
            Messages.showWarningDialog(project, "请先选中需要优化的代码。", "AI 优化代码")
            return
        }

        val settings = AiCodeSettings.getInstance()
        if (settings.apiKey.isBlank()) {
            Messages.showWarningDialog(project, "请先在设置中配置 API Key。\n前往: Settings -> Tools -> AI Code Helper", "AI 优化代码")
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI 正在优化代码...", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val apiService = AiApiService()
                    val messages = listOf(
                        AiApiService.Message(
                            "system",
                            "你是一个专业的代码优化助手。请分析用户提供的代码，给出优化建议，并提供优化后的完整代码。" +
                                    "回复格式：先给出优化建议（列表形式），然后提供优化后的代码（使用代码块）。请用中文回复。"
                        ),
                        AiApiService.Message(
                            "user",
                            "请优化以下代码：\n\n```\n$selectedText\n```"
                        )
                    )
                    val result = apiService.chat(messages)

                    ApplicationManager.getApplication().invokeLater {
                        showOptimizationResult(project, editor, selectedText, result)
                    }
                } catch (ex: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "调用 AI 失败: ${ex.message}", "AI 优化代码")
                    }
                }
            }
        })
    }

    private fun showOptimizationResult(
        project: Project,
        editor: Editor,
        originalCode: String,
        optimizationResult: String
    ) {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("AI Code Helper")
        toolWindow?.show()

        val chatPanel = AiChatToolWindowFactory.getInstance(project)
        chatPanel?.appendMessage("助手", "**[代码优化建议]**\n\n$optimizationResult")

        val optimizedCode = extractCodeFromMarkdown(optimizationResult)

        val choice = Messages.showYesNoDialog(
            project,
            "AI 已给出优化建议，详情请查看 AI Code Helper 面板。\n\n是否用优化后的代码替换选中内容？",
            "AI 优化代码",
            "替换代码",
            "仅查看建议",
            Messages.getQuestionIcon()
        )

        if (choice == Messages.YES && optimizedCode.isNotBlank()) {
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.replaceString(
                    editor.selectionModel.selectionStart,
                    editor.selectionModel.selectionEnd,
                    optimizedCode
                )
            }
        }
    }

    private fun extractCodeFromMarkdown(text: String): String {
        val codeBlockRegex = Regex("```(?:\\w+)?\\n?([\\s\\S]*?)```")
        val matches = codeBlockRegex.findAll(text).toList()
        return if (matches.isNotEmpty()) {
            matches.last().groupValues[1].trim()
        } else {
            ""
        }
    }
}
