package com.aicode.helper.actions

import com.aicode.helper.service.AiApiService
import com.aicode.helper.settings.AiCodeSettings
import com.aicode.helper.toolwindow.AiChatToolWindowFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

class ExplainCodeAction : AnAction("AI 解释代码") {

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
            Messages.showWarningDialog(project, "请先选中需要解释的代码。", "AI 解释代码")
            return
        }

        val settings = AiCodeSettings.getInstance()
        if (settings.apiKey.isBlank()) {
            Messages.showWarningDialog(project, "请先在设置中配置 API Key。\n前往: Settings -> Tools -> AI Code Helper", "AI 解释代码")
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI 正在解释代码...", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val apiService = AiApiService()
                    val messages = listOf(
                        AiApiService.Message(
                            "system",
                            "你是一个专业的代码解释助手。请用清晰、简洁的中文解释用户提供的代码，包括：代码的功能、实现逻辑、关键点以及注意事项。"
                        ),
                        AiApiService.Message(
                            "user",
                            "请解释以下代码：\n\n```\n$selectedText\n```"
                        )
                    )
                    val result = apiService.chat(messages)
                    ApplicationManager.getApplication().invokeLater {
                        showResultInToolWindow(project, "代码解释", result)
                    }
                } catch (ex: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "调用 AI 失败: ${ex.message}", "AI 解释代码")
                    }
                }
            }
        })
    }

    private fun showResultInToolWindow(project: Project, title: String, content: String) {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("DEEPWAY CODE")
        toolWindow?.show()

        val chatPanel = AiChatToolWindowFactory.getInstance(project)
        chatPanel?.appendMessage("assistant", "**[$title]**\n\n$content")
    }
}
