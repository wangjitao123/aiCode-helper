package com.aicode.helper.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.util.concurrent.ConcurrentHashMap

class AiChatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatPanel = ChatPanel(project)
        val content = ContentFactory.getInstance().createContent(chatPanel, "", false)
        toolWindow.contentManager.addContent(content)
        instances[project] = chatPanel
    }

    companion object {
        private val instances = ConcurrentHashMap<Project, ChatPanel>()

        fun getInstance(project: Project): ChatPanel? = instances[project]
    }
}
