package com.aicode.helper.actions

import com.aicode.helper.service.AiApiService
import com.aicode.helper.settings.AiCodeSettings
import com.aicode.helper.toolwindow.AiChatToolWindowFactory
import com.aicode.helper.utils.ProjectStructureUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

class ProjectStructureAction : AnAction("AI 分析项目结构") {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val settings = AiCodeSettings.getInstance()
        if (settings.apiKey.isBlank()) {
            Messages.showWarningDialog(project, "请先在设置中配置 API Key。\n前往: Settings -> Tools -> AI Code Helper", "AI 分析项目结构")
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI 正在分析项目结构...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "正在收集项目结构信息..."
                val structureInfo = ProjectStructureUtil.analyzeProjectStructure(project)

                indicator.text = "正在调用 AI 分析..."
                try {
                    val apiService = AiApiService()
                    val messages = listOf(
                        AiApiService.Message(
                            "system",
                            "你是一个专业的软件架构分析助手。请根据用户提供的项目目录结构，分析项目的架构模式、技术栈、模块组成，并给出项目概述和架构建议。请用中文回复，输出格式清晰易读。"
                        ),
                        AiApiService.Message(
                            "user",
                            "请分析以下项目结构：\n\n$structureInfo"
                        )
                    )
                    val result = apiService.chat(messages)

                    ApplicationManager.getApplication().invokeLater {
                        showResultInToolWindow(project, structureInfo, result)
                    }
                } catch (ex: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "调用 AI 失败: ${ex.message}", "AI 分析项目结构")
                    }
                }
            }
        })
    }

    private fun showResultInToolWindow(
        project: Project,
        structureInfo: String,
        aiAnalysis: String
    ) {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("AI Code Helper")
        toolWindow?.show()

        val chatPanel = AiChatToolWindowFactory.getInstance(project)
        chatPanel?.appendMessage("助手", "**[项目结构分析]**\n\n$aiAnalysis")
    }
}
