package com.aicode.helper.toolwindow

import com.aicode.helper.agent.AgentSession
import com.aicode.helper.agent.event.AgentEvent
import com.aicode.helper.settings.AiCodeSettings
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet
import kotlin.coroutines.cancellation.CancellationException

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

    /** Agent 会话：持有跨多轮共享的 AgentState 与工具/hook/压缩组装。 */
    private val agentSession = AgentSession(project)

    /** 收集 QueryEngine 事件流的协程作用域（决策 #1：消费 AsyncGenerator）。 */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentJob: Job? = null

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
        sendButton.addActionListener { onSendOrStop() }

        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                    onSendOrStop()
                    e.consume()
                }
            }
        })

        clearButton.addActionListener {
            currentJob?.cancel()
            agentSession.reset()
            chatMessages.clear()
            addWelcomeBubble()
            renderChat()
        }
    }

    private fun addWelcomeBubble() {
        chatMessages.add(ChatBubble("system",
            "**DEEPWAY CODE** · Agentic Harness \n\n" +
            "我是一个具备工具调用能力的 AI Agent：\n" +
            "- 直接提问，我会按需调用 `project_structure` / `list_directory` / `read_file` / `grep_search` 等工具逐步探索项目再作答\n" +
            "- 你能实时看到「调用工具 → 工具结果 → 推理」的过程（ReAct 主循环）\n" +
            "- 运行中「发送」按钮会变为「停止」，可随时中断（取消整个生成器链）\n" +
            "- 在 Settings → Tools → AI Code Helper 配置 API 与 Agent 选项"
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

    private fun onSendOrStop() {
        // 运行中：把「发送」当作「停止」——取消整个事件流（≈ generator.return()）
        if (currentJob?.isActive == true) {
            currentJob?.cancel()
            return
        }

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
        val bubble = ChatBubble("assistant", "")
        chatMessages.add(bubble)
        renderChat()
        setRunning(true)

        val engine = agentSession.newQuery()
        currentJob = uiScope.launch {
            try {
                engine.query(userInput).collect { event ->
                    handleEvent(event, bubble)
                }
            } catch (e: CancellationException) {
                appendToBubble(bubble, "\n\n_⏹ 已停止_")
            } catch (e: Exception) {
                appendToBubble(bubble, "\n\n**错误:** ${e.message}")
            } finally {
                SwingUtilities.invokeLater { setRunning(false) }
            }
        }
    }

    /** 决策 #1：把生成器流出的事件渲染成可见的 ReAct 过程。 */
    private fun handleEvent(event: AgentEvent, bubble: ChatBubble) {
        when (event) {
            is AgentEvent.AssistantTextDelta -> appendToBubble(bubble, event.text)
            is AgentEvent.ToolUseRequested ->
                appendToBubble(bubble, "\n\n🔧 **调用工具** `${event.toolName}` ${shorten(event.argumentsJson, 160)}\n")
            is AgentEvent.ToolResultEvent -> {
                val icon = if (event.isError) "⚠️" else "✅"
                appendToBubble(bubble, "$icon `${event.toolName}` 结果：\n```\n${shorten(event.output, 600)}\n```\n")
            }
            is AgentEvent.Compaction ->
                appendToBubble(bubble, "\n🗜️ _上下文压缩 [${event.layer}]：${event.detail}_\n")
            is AgentEvent.Transitioned ->
                appendToBubble(bubble, "\n🔁 _状态转移：${event.reason}_\n")
            is AgentEvent.IterationStart ->
                if (event.iteration > 1) appendToBubble(bubble, "\n\n— 第 ${event.iteration} 轮 —\n")
            is AgentEvent.QueryComplete -> { /* 文本已通过增量逐步渲染，无需额外处理 */ }
            is AgentEvent.ErrorEvent -> appendToBubble(bubble, "\n\n**错误:** ${event.message}")
            is AgentEvent.ToolExecutionStarted -> { /* 可选，暂不展示 */ }
        }
    }

    private fun appendToBubble(bubble: ChatBubble, text: String) {
        SwingUtilities.invokeLater {
            bubble.content += text
            renderChat()
        }
    }

    private fun shorten(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + " …(已截断)"

    private fun setRunning(running: Boolean) {
        sendButton.text = if (running) "停止" else "发送"
        if (running) {
            sendButton.background = Color(220, 70, 70)
        } else {
            sendButton.background = Color(0, 122, 255)
        }
    }

    fun appendMessage(role: String, content: String) {
        SwingUtilities.invokeLater {
            chatMessages.add(ChatBubble(role, content))
            renderChat()
        }
    }
}
