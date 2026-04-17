package com.aicode.helper.toolwindow

import com.aicode.helper.agent.ProjectAnalysisAgent
import com.aicode.helper.service.AiApiService
import com.aicode.helper.service.ChatHistoryService
import com.aicode.helper.settings.AiCodeSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

class ChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val chatMessages = mutableListOf<ChatBubble>()

    data class ChatBubble(val role: String, var content: String, val timestamp: Long = System.currentTimeMillis())

    private val chatDisplay = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        val kit = HTMLEditorKit()
        kit.styleSheet = createStyleSheet()
        editorKit = kit
        border = JBUI.Borders.empty(4)
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
    }

    private val inputArea = JTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font("Microsoft YaHei", Font.PLAIN, 13)
        margin = JBUI.insets(8)
        border = BorderFactory.createEmptyBorder()
    }

    private val sendButton = createStyledButton("发送", isPrimary = true)
    private val clearButton = createStyledButton("清空", isPrimary = false)

    private val historyService = ChatHistoryService.getInstance(project)

    private var currentStreamingBubble: ChatBubble? = null

    private val projectAnalysisKeywords = listOf(
        "项目结构", "项目分析", "分析项目", "分析一下项目", "项目架构",
        "project structure", "analyze project", "项目概述", "项目组成",
        "看看项目", "读一下项目", "了解项目", "了解一下项目", "介绍一下项目",
        "这个项目", "看下项目", "项目是做什么", "项目做什么"
    )

    init {
        setupUI()
        setupListeners()
        addWelcomeBubble()
        renderChat()
    }

    private fun createStyleSheet(): StyleSheet {
        val isDark = UIUtil.isUnderDarcula()
        val bg = if (isDark) "#2b2b2b" else "#f5f5f5"
        val userBg = if (isDark) "#2d5a8e" else "#007AFF"
        val assistantBg = if (isDark) "#3c3f41" else "#ffffff"
        val textColor = if (isDark) "#bbbbbb" else "#333333"
        val userTextColor = "#ffffff"
        val codeBg = if (isDark) "#1e1e1e" else "#f0f0f0"
        val codeBorder = if (isDark) "#555555" else "#d0d0d0"
        val inlineCodeBg = if (isDark) "#383838" else "#e8e8e8"

        return StyleSheet().apply {
            addRule("body { font-family: 'Microsoft YaHei', 'Segoe UI', sans-serif; font-size: 13px; color: $textColor; margin: 0; padding: 4px; background: $bg; }")
            addRule(".chat-container { width: 100%; }")
            addRule(".bubble-row { margin: 4px 8px; padding: 0; }")
            addRule(".bubble-row-user { text-align: right; }")
            addRule(".bubble-row-assistant { text-align: left; }")
            addRule(".bubble-user { background-color: $userBg; color: $userTextColor; padding: 8px 14px; }")
            addRule(".bubble-assistant { background-color: $assistantBg; color: $textColor; padding: 10px 14px; border: 1px solid $codeBorder; }")
            addRule(".bubble-system { background-color: transparent; color: #888888; padding: 6px 14px; text-align: center; font-size: 12px; }")
            addRule(".role-label { font-size: 11px; color: #999999; margin-bottom: 2px; }")
            addRule(".code-block { background-color: $codeBg; border: 1px solid $codeBorder; padding: 8px 10px; margin: 6px 0; font-family: 'JetBrains Mono', 'Consolas', monospace; font-size: 12px; }")
            addRule(".code-block pre { margin: 0; white-space: pre-wrap; }")
            addRule(".inline-code { background-color: $inlineCodeBg; padding: 1px 5px; font-family: 'JetBrains Mono', 'Consolas', monospace; font-size: 12px; }")
            addRule("h2 { font-size: 16px; margin: 8px 0 4px 0; }")
            addRule("h3 { font-size: 15px; margin: 6px 0 3px 0; }")
            addRule("h4 { font-size: 14px; margin: 4px 0 2px 0; }")
            addRule("ul, ol { margin: 4px 0; padding-left: 20px; }")
            addRule("li { margin: 2px 0; }")
        }
    }

    private fun createStyledButton(text: String, isPrimary: Boolean): JButton {
        return JButton(text).apply {
            preferredSize = Dimension(72, 30)
            isFocusPainted = false
            font = Font("Microsoft YaHei", Font.PLAIN, 12)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            if (isPrimary) {
                background = Color(0, 122, 255)
                foreground = Color.WHITE
                isOpaque = true
            }
        }
    }

    private fun setupUI() {
        val chatScrollPane = JBScrollPane(chatDisplay).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        val inputWrapper = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.empty(4, 8, 8, 8),
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color(180, 180, 180), 1, true),
                    JBUI.Borders.empty(2)
                )
            )
            add(JBScrollPane(inputArea).apply {
                border = JBUI.Borders.empty()
                preferredSize = Dimension(400, 70)
            }, BorderLayout.CENTER)
        }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2)).apply {
            isOpaque = false
            add(clearButton)
            add(sendButton)
        }

        val hintLabel = JLabel("Ctrl+Enter 发送").apply {
            font = Font("Microsoft YaHei", Font.PLAIN, 11)
            foreground = Color(160, 160, 160)
            border = JBUI.Borders.emptyLeft(12)
        }

        val bottomBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(hintLabel, BorderLayout.WEST)
            add(buttonPanel, BorderLayout.EAST)
        }

        val bottomPanel = JPanel(BorderLayout()).apply {
            add(inputWrapper, BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
        }

        add(chatScrollPane, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
        border = JBUI.Borders.empty(2)
    }

    private fun setupListeners() {
        sendButton.addActionListener { sendMessage() }

        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                    sendMessage()
                    e.consume()
                }
            }
        })

        clearButton.addActionListener {
            historyService.clear()
            chatMessages.clear()
            addWelcomeBubble()
            renderChat()
        }
    }

    private fun addWelcomeBubble() {
        chatMessages.add(ChatBubble("system",
            "**DEEPWAY CODE** \n\n" +
            "欢迎使用 AI 编程助手！\n" +
            "- 在下方输入框输入问题，按 **Ctrl+Enter** 发送\n" +
            "- 输入「项目结构」等关键词，AI 会逐步读取文件分析项目\n" +
            "- 右键编辑器代码使用「AI 解释代码」「AI 优化代码」\n" +
            "- 在 Settings → Tools → AI Code Helper 配置 API"
        ))
    }

    private fun renderChat() {
        val sb = StringBuilder()
        sb.append("<html><body><div class='chat-container'>")

        for (bubble in chatMessages) {
            val renderedContent = MarkdownRenderer.toHtml(bubble.content)
            when (bubble.role) {
                "user" -> {
                    sb.append("<div class='bubble-row bubble-row-user'>")
                    sb.append("<div class='role-label'>You</div>")
                    sb.append("<div class='bubble-user'>$renderedContent</div>")
                    sb.append("</div>")
                }
                "assistant" -> {
                    sb.append("<div class='bubble-row bubble-row-assistant'>")
                    sb.append("<div class='role-label'>AI Assistant</div>")
                    sb.append("<div class='bubble-assistant'>$renderedContent</div>")
                    sb.append("</div>")
                }
                "system" -> {
                    sb.append("<div class='bubble-row'>")
                    sb.append("<div class='bubble-system'>$renderedContent</div>")
                    sb.append("</div>")
                }
            }
        }

        sb.append("</div></body></html>")

        SwingUtilities.invokeLater {
            chatDisplay.text = sb.toString()
            SwingUtilities.invokeLater {
                chatDisplay.caretPosition = chatDisplay.document.length
            }
        }
    }

    private fun sendMessage() {
        val userInput = inputArea.text.trim()
        if (userInput.isBlank()) return

        val settings = AiCodeSettings.getInstance()
        if (settings.apiKey.isBlank()) {
            chatMessages.add(ChatBubble("system", "请先在 **Settings → Tools → AI Code Helper** 中配置 API Key。"))
            renderChat()
            return
        }

        inputArea.text = ""
        chatMessages.add(ChatBubble("user", userInput))
        renderChat()

        sendButton.isEnabled = false
        sendButton.text = "..."

        if (isProjectAnalysisQuestion(userInput)) {
            startProjectAnalysis(userInput)
        } else {
            startNormalChat(userInput)
        }
    }

    private fun isProjectAnalysisQuestion(input: String): Boolean {
        val lower = input.lowercase()
        return projectAnalysisKeywords.any { lower.contains(it) }
    }

    private fun startProjectAnalysis(userQuestion: String) {
        historyService.addMessage("user", userQuestion)

        val bubble = ChatBubble("assistant", "")
        chatMessages.add(bubble)
        currentStreamingBubble = bubble

        val agent = ProjectAnalysisAgent(
            project = project,
            onStep = { step ->
                SwingUtilities.invokeLater {
                    bubble.content += step
                    renderChat()
                }
            },
            onComplete = { fullResponse ->
                historyService.addMessage("assistant", fullResponse)
                currentStreamingBubble = null
                SwingUtilities.invokeLater {
                    sendButton.isEnabled = true
                    sendButton.text = "发送"
                }
            },
            onError = { error ->
                SwingUtilities.invokeLater {
                    bubble.content += "\n\n错误: $error"
                    renderChat()
                    currentStreamingBubble = null
                    sendButton.isEnabled = true
                    sendButton.text = "发送"
                }
            }
        )
        agent.analyze(userQuestion)
    }

    private fun startNormalChat(userInput: String) {
        historyService.addMessage("user", userInput)

        val bubble = ChatBubble("assistant", "")
        chatMessages.add(bubble)
        currentStreamingBubble = bubble

        val currentMessages = historyService.toApiMessages()
        val apiService = AiApiService()
        val responseBuffer = StringBuilder()

        ApplicationManager.getApplication().executeOnPooledThread {
            apiService.chatStream(
                messages = currentMessages,
                onChunk = { chunk ->
                    responseBuffer.append(chunk)
                    SwingUtilities.invokeLater {
                        bubble.content = responseBuffer.toString()
                        renderChat()
                    }
                },
                onComplete = {
                    val fullResponse = responseBuffer.toString()
                    historyService.addMessage("assistant", fullResponse)
                    currentStreamingBubble = null
                    SwingUtilities.invokeLater {
                        sendButton.isEnabled = true
                        sendButton.text = "发送"
                    }
                },
                onError = { errorMsg ->
                    SwingUtilities.invokeLater {
                        bubble.content += "\n\n错误: $errorMsg"
                        renderChat()
                        currentStreamingBubble = null
                        sendButton.isEnabled = true
                        sendButton.text = "发送"
                    }
                }
            )
        }
    }

    fun appendMessage(role: String, content: String) {
        SwingUtilities.invokeLater {
            chatMessages.add(ChatBubble(role, content))
            renderChat()
        }
    }
}
