package com.aicode.helper.toolwindow

import com.aicode.helper.service.AiApiService
import com.aicode.helper.service.ChatHistoryService
import com.aicode.helper.settings.AiCodeSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.text.DefaultCaret

class ChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val chatDisplay = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        margin = JBUI.insets(8)
        val caret = caret as? DefaultCaret
        caret?.updatePolicy = DefaultCaret.ALWAYS_UPDATE
    }

    private val inputArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        margin = JBUI.insets(6)
    }

    private val sendButton = JButton("发送").apply {
        preferredSize = Dimension(80, 32)
    }

    private val clearButton = JButton("清空").apply {
        preferredSize = Dimension(80, 32)
    }

    private val historyService = ChatHistoryService.getInstance(project)

    init {
        setupUI()
        setupListeners()
        appendWelcomeMessage()
    }

    private fun setupUI() {
        val chatScrollPane = JBScrollPane(chatDisplay).apply {
            border = BorderFactory.createTitledBorder("对话记录")
            preferredSize = Dimension(400, 400)
        }

        val inputScrollPane = JBScrollPane(inputArea).apply {
            border = BorderFactory.createTitledBorder("输入消息")
            preferredSize = Dimension(400, 80)
        }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 4)).apply {
            add(clearButton)
            add(sendButton)
        }

        val bottomPanel = JPanel(BorderLayout()).apply {
            add(inputScrollPane, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.SOUTH)
        }

        add(chatScrollPane, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
        border = JBUI.Borders.empty(4)
    }

    private fun setupListeners() {
        sendButton.addActionListener { sendMessage() }

        inputArea.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER && e.isControlDown) {
                    sendMessage()
                    e.consume()
                }
            }
        })

        clearButton.addActionListener {
            historyService.clear()
            chatDisplay.text = ""
            appendWelcomeMessage()
        }
    }

    private fun appendWelcomeMessage() {
        chatDisplay.text = "=== AI Code Helper 聊天窗口 ===\n\n" +
                "欢迎使用 AI 编程助手！\n" +
                "• 在下方输入框输入问题，点击「发送」或按 Ctrl+Enter 发送\n" +
                "• 右键编辑器中的代码可以使用「AI 解释代码」和「AI 优化代码」功能\n" +
                "• 在 Tools 菜单中可以找到「AI 分析项目结构」功能\n" +
                "• 在 Settings -> Tools -> AI Code Helper 中配置 API 信息\n\n"
    }

    private fun sendMessage() {
        val userInput = inputArea.text.trim()
        if (userInput.isBlank()) return

        val settings = AiCodeSettings.getInstance()
        if (settings.apiKey.isBlank()) {
            appendMessage("系统", "请先在 Settings -> Tools -> AI Code Helper 中配置 API Key。")
            return
        }

        inputArea.text = ""
        appendMessage("用户", userInput)
        historyService.addMessage("user", userInput)

        sendButton.isEnabled = false
        sendButton.text = "发送中..."

        val currentMessages = historyService.toApiMessages()
        val apiService = AiApiService()
        val responseBuffer = StringBuilder()

        appendToDisplay("\n助手: ")

        ApplicationManager.getApplication().executeOnPooledThread {
            apiService.chatStream(
                messages = currentMessages,
                onChunk = { chunk ->
                    responseBuffer.append(chunk)
                    SwingUtilities.invokeLater {
                        appendToDisplay(chunk)
                    }
                },
                onComplete = {
                    val fullResponse = responseBuffer.toString()
                    historyService.addMessage("assistant", fullResponse)
                    SwingUtilities.invokeLater {
                        appendToDisplay("\n\n")
                        sendButton.isEnabled = true
                        sendButton.text = "发送"
                    }
                },
                onError = { errorMsg ->
                    SwingUtilities.invokeLater {
                        appendToDisplay("\n[错误: $errorMsg]\n\n")
                        sendButton.isEnabled = true
                        sendButton.text = "发送"
                    }
                }
            )
        }
    }

    fun appendMessage(role: String, content: String) {
        SwingUtilities.invokeLater {
            val displayRole = when (role) {
                "user" -> "用户"
                "assistant" -> "助手"
                else -> role
            }
            appendToDisplay("$displayRole: $content\n\n")
        }
    }

    private fun appendToDisplay(text: String) {
        chatDisplay.append(text)
        chatDisplay.caretPosition = chatDisplay.document.length
    }
}
